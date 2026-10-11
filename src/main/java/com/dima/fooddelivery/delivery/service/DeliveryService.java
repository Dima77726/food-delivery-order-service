package com.dima.fooddelivery.delivery.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.api.DeliveryResponseMapper;
import com.dima.fooddelivery.delivery.domain.Delivery;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.delivery.persistence.DeliveryRepository;
import com.dima.fooddelivery.delivery.persistence.DeliveryRepository.DeliveryTimestampColumn;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Модуль Delivery.
 *
 * <p>Ключевое отличие от прежней версии: этот сервис больше не пишет в {@code customer_order}
 * и {@code customer_order_event}. Статус заказа меняется через {@link OrderStatusService} —
 * публичный вход модуля Order. Своя транзакция при этом одна на оба модуля, поэтому
 * «доставка завершена, а заказ остался в пути» невозможно.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final OrderStatusService orderStatusService;

    @Transactional
    public DeliveryResponse createDeliveryForOrder(Long orderId) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (!order.status().canCreateDelivery()) {
            throw new BusinessRuleViolationException(
                    "Доставку можно создать только для заказа в статусе READY_FOR_DELIVERY. Текущий статус="
                            + order.status().getDbValue()
            );
        }

        Long deliveryId;
        try {
            deliveryId = deliveryRepository.insert(orderId, DeliveryStatus.CREATED);
        } catch (DuplicateKeyException exception) {
            // uq_delivery_order: одна доставка на заказ. Гонку ловит база, а не проверка перед вставкой.
            log.warn("Доставка для заказа уже существует: orderId={}", orderId);

            throw new BusinessRuleViolationException(
                    "Доставка для заказа с id=" + orderId + " уже существует"
            );
        }

        log.info("Доставка создана: deliveryId={}, orderId={}", deliveryId, orderId);

        return DeliveryResponseMapper.toResponse(requireDelivery(deliveryId));
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> getAvailableDeliveries() {
        return DeliveryResponseMapper.toResponses(deliveryRepository.findAvailableForAssignment());
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> getDeliveriesForCourier(Long courierId) {
        return DeliveryResponseMapper.toResponses(deliveryRepository.findByCourierId(courierId));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse getDeliveryForCourier(Long courierId, Long deliveryId) {
        return DeliveryResponseMapper.toResponse(requireCourierDelivery(courierId, deliveryId));
    }

    /**
     * Доставка по идентификатору заказа.
     *
     * <p>Публичный вход для модуля Tracking: чтобы показать клиенту, где едет его заказ, нужно
     * превратить orderId в deliveryId, а таблица доставок принадлежит этому модулю. Проверку,
     * что заказ действительно клиента, делает вызывающий — здесь о правах ничего не известно,
     * и решать за него было бы неверно.
     *
     * @throws ResourceNotFoundException если доставки для заказа ещё нет
     */
    @Transactional(readOnly = true)
    public DeliveryResponse getDeliveryForOrder(Long orderId) {
        return DeliveryResponseMapper.toResponse(
                deliveryRepository.findByOrderId(orderId)
                        .orElseThrow(() -> {
                            log.warn("Доставка для заказа не найдена: orderId={}", orderId);

                            return new ResourceNotFoundException(
                                    "Доставка для заказа с id=" + orderId + " ещё не создана"
                            );
                        })
        );
    }

    @Transactional
    public DeliveryResponse assignCourierToDelivery(Long courierId, Long deliveryId) {
        Delivery delivery = requireDelivery(deliveryId);

        if (!delivery.status().canBeAssignedToCourier()) {
            throw new BusinessRuleViolationException(
                    "Курьера можно назначить только на доставку в статусе CREATED. Текущий статус="
                            + delivery.status().getDbValue()
            );
        }

        int updated = deliveryRepository.assignCourier(deliveryId, courierId);

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось назначить курьера на доставку с id=" + deliveryId
                            + ". Возможно, её уже взял другой курьер"
            );
        }

        log.info("Курьер назначен на доставку: deliveryId={}, courierId={}", deliveryId, courierId);

        return DeliveryResponseMapper.toResponse(requireDelivery(deliveryId));
    }

    @Transactional
    public DeliveryResponse pickUpDelivery(Long courierId, Long deliveryId) {
        Delivery delivery = requireCourierDelivery(courierId, deliveryId);

        if (!delivery.status().canBePickedUpByCourier()) {
            throw new BusinessRuleViolationException(
                    "Забрать можно только доставку в статусе ASSIGNED. Текущий статус="
                            + delivery.status().getDbValue()
            );
        }

        int updated = deliveryRepository.compareAndSetStatus(
                deliveryId,
                DeliveryStatus.ASSIGNED,
                DeliveryStatus.PICKED_UP,
                DeliveryTimestampColumn.PICKED_UP_AT
        );

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось отметить доставку как забранную: deliveryId=" + deliveryId
                            + ". Возможно, доставка уже изменила статус"
            );
        }

        // Заказ переводит модуль Order: проверку READY_FOR_DELIVERY -> IN_DELIVERY делает он же,
        // здесь дублировать её не нужно.
        orderStatusService.markInDelivery(delivery.orderId());

        log.info(
                "Курьер забрал заказ: deliveryId={}, courierId={}, orderId={}",
                deliveryId,
                courierId,
                delivery.orderId()
        );

        return DeliveryResponseMapper.toResponse(requireDelivery(deliveryId));
    }

    @Transactional
    public DeliveryResponse deliverDelivery(Long courierId, Long deliveryId) {
        Delivery delivery = requireCourierDelivery(courierId, deliveryId);

        if (!delivery.status().canBeDeliveredByCourier()) {
            throw new BusinessRuleViolationException(
                    "Завершить можно только доставку в статусе PICKED_UP. Текущий статус="
                            + delivery.status().getDbValue()
            );
        }

        int updated = deliveryRepository.compareAndSetStatus(
                deliveryId,
                DeliveryStatus.PICKED_UP,
                DeliveryStatus.DELIVERED,
                DeliveryTimestampColumn.DELIVERED_AT
        );

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось завершить доставку: deliveryId=" + deliveryId
                            + ". Возможно, доставка уже изменила статус"
            );
        }

        orderStatusService.markDelivered(delivery.orderId());

        log.info(
                "Доставка завершена: deliveryId={}, courierId={}, orderId={}",
                deliveryId,
                courierId,
                delivery.orderId()
        );

        return DeliveryResponseMapper.toResponse(requireDelivery(deliveryId));
    }

    private Delivery requireDelivery(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> {
                    log.warn("Доставка не найдена: deliveryId={}", deliveryId);
                    return new ResourceNotFoundException("Доставка с id=" + deliveryId + " не найдена");
                });
    }

    private Delivery requireCourierDelivery(Long courierId, Long deliveryId) {
        Delivery delivery = requireDelivery(deliveryId);

        if (!delivery.assignedTo(courierId)) {
            log.warn(
                    "Курьер обратился к чужой доставке: deliveryId={}, courierId={}",
                    deliveryId,
                    courierId
            );

            throw new AccessDeniedForResourceException("Эта доставка назначена другому курьеру");
        }

        return delivery;
    }
}
