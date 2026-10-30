package com.dima.fooddelivery.order.saga;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.api.OrderResponseMapper;
import com.dima.fooddelivery.order.api.PayOrderRequest;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.persistence.InboxRepository;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import com.dima.fooddelivery.order.saga.camunda.OrderLifecycleProcess;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.order.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Сага оплаты заказа. Сервис заказов — её оркестратор: он решает, что делать дальше,
 * а сервис платежей только исполняет команды и сообщает исход.
 *
 * <pre>
 *   клиент: «оплатить»   ─► CREATED → PAYMENT_PENDING + команда PAYMENT_REQUESTED
 *   PAYMENT_SUCCEEDED    ─► PAYMENT_PENDING → PAID
 *   PAYMENT_FAILED       ─► PAYMENT_PENDING → CANCELED
 *   клиент: «отменить»   ─► PAYMENT_PENDING|PAID → CANCELED + команда REFUND_REQUESTED
 *   таймаут оплаты       ─► CREATED|PAYMENT_PENDING → CANCELED (+ REFUND_REQUESTED, если оплату запросили)
 *   PAYMENT_REFUNDED     ─► запись в историю заказа
 * </pre>
 *
 * <p><b>Почему оркестрация, а не хореография.</b> Участника пока два, и разницы почти нет.
 * Но вся логика саги — какие исходы бывают и что делать с каждым — собрана здесь, в одном
 * классе, а не размазана реакциями по сервисам. Когда добавится третий участник (например,
 * доставка, которой может не найтись курьера), его шаг допишется сюда же.
 *
 * <p><b>Компенсация вместо отката.</b> Общей транзакции у двух баз нет. Если заказ отменён
 * после списания, деньги не «откатываются», а возвращаются отдельным действием — командой
 * REFUND_REQUESTED.
 *
 * <p><b>Опоздавшие исходы — норма.</b> Заказ могли отменить, пока шло списание. Тогда
 * PAYMENT_SUCCEEDED приходит к отменённому заказу и ничего не меняет: команда на возврат
 * ушла при отмене, и сервис платежей обработает её после списания — команды одного заказа
 * идут в одной партиции, по порядку.
 *
 * <p><b>Два движка.</b> По умолчанию ({@code app.saga.engine=native}) сага — этот класс и
 * {@link PaymentTimeoutJob}. С {@code app.saga.engine=camunda} ожидание исхода ведёт процесс
 * {@code order-lifecycle} в Camunda 8 ({@link OrderLifecycleProcess}): исходы из Kafka становятся
 * сообщениями процесса, таймаут — его таймером, а реакции — заданиями, которые вызывают
 * {@link #markPaid}, {@link #cancelOnPaymentFailure} и {@link #expireIfAwaiting}. Переходы
 * статусов и outbox в обоих режимах одни и те же.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSaga {

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final OrderService orderService;
    private final PaymentCommandWriter paymentCommands;
    private final InboxRepository inbox;
    private final ObjectProvider<OrderLifecycleProcess> lifecycleProcess;

    /**
     * Клиент просит оплатить заказ. Ответ — заказ в статусе PAYMENT_PENDING: сколько займёт
     * списание, сервис заказов не знает, и держать HTTP-запрос до ответа сервиса платежей
     * значило бы вернуть синхронную связь, от которой сага и избавляет.
     *
     * <p>Повтор с тем же ключом идемпотентности возвращает заказ как есть, не запуская
     * вторую оплату: двойной клик и повтор после таймаута выглядят для сервера одинаково.
     */
    @Transactional
    public OrderResponse requestPayment(Long customerId, Long orderId, PayOrderRequest request) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (!order.belongsToCustomer(customerId)) {
            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        // Проверка ключа идёт до проверки статуса: повтор запроса уже обработанной оплаты
        // с тем же ключом — это не ошибка, а тот же самый запрос.
        var existing = orderRepository.findPaymentRequest(orderId);
        if (existing.isPresent()) {
            if (existing.get().idempotencyKey().equals(request.idempotencyKey())) {
                log.info("Повторный запрос оплаты по тому же ключу: orderId={}", orderId);

                return OrderResponseMapper.toResponse(orderService.requireOrder(orderId));
            }

            throw new BusinessRuleViolationException("Оплата этого заказа уже запрошена");
        }

        if (!order.status().canBePaidByCustomer()) {
            throw new BusinessRuleViolationException(
                    "Оплатить можно только заказ в статусе CREATED. Текущий статус="
                            + order.status().getDbValue()
            );
        }

        boolean requested;
        try {
            requested = orderStatusService.markPaymentRequested(orderId, request.method(), request.idempotencyKey());
        } catch (DuplicateKeyException duplicate) {
            // Сработал uq_customer_order_payment_idempotency_key.
            throw new BusinessRuleViolationException("Ключ идемпотентности уже использован для другого заказа");
        }

        if (!requested) {
            throw new BusinessRuleViolationException(
                    "Заказ с id=" + orderId + " уже изменил статус. Обновите заказ и повторите"
            );
        }

        // Сумма — из заказа, а не из запроса: иначе клиент платил бы столько, сколько захочет.
        paymentCommands.requestPayment(order, request.method(), request.idempotencyKey());

        // В той же транзакции: если Camunda недоступна, запрос откатится целиком, и заказ
        // не останется в PAYMENT_PENDING без процесса, который довёл бы его до исхода.
        lifecycleProcess.ifAvailable(process -> process.start(orderId));

        log.info("Оплата запрошена: orderId={}, method={}, amount={}", orderId, request.method(), order.totalAmount());

        return OrderResponseMapper.toResponse(orderService.requireOrder(orderId));
    }

    /**
     * Исход платежа из Kafka.
     *
     * <p>Отметка в inbox и вся реакция — в одной транзакции. Повтор сообщения упирается
     * в уже записанный идентификатор и ничего не делает; сбой посреди обработки откатывает
     * и отметку, и сообщение придёт снова.
     */
    @Transactional
    public void handle(PaymentEvent event, String topic) {
        if (!inbox.markProcessed(event.eventId(), topic)) {
            log.info("Исход платежа уже обработан, пропускаем: eventId={}", event.eventId());
            return;
        }

        OrderLifecycleProcess process = lifecycleProcess.getIfAvailable();
        if (process != null && process.handles(event)) {
            // Реакцию выполнит задание процесса; здесь исход только передаётся ему. Сбой
            // публикации откатит и отметку в inbox — сообщение из Kafka придёт снова.
            process.publishOutcome(event);
            return;
        }

        switch (event.eventType()) {
            case PaymentEvent.PAYMENT_SUCCEEDED -> markPaid(event.orderId(), event.paymentId());
            case PaymentEvent.PAYMENT_FAILED -> cancelOnPaymentFailure(event.orderId(), event.failureReason());
            case PaymentEvent.PAYMENT_REFUNDED -> onRefunded(event);
            // Новый тип исхода, которого эта версия не знает. Пропустить его безопасно:
            // сага от него не зависит, а падение отправило бы его в DLT без пользы.
            default -> log.warn("Неизвестный исход платежа, пропускаем: eventType={}, orderId={}",
                    event.eventType(), event.orderId());
        }
    }

    /**
     * Заказ не дождался оплаты. Вызывается задачей {@link PaymentTimeoutJob} для каждого
     * просроченного заказа, каждый раз в своей транзакции: сбой на одном не откатывает остальные.
     */
    @Transactional
    public void expire(OrderAccess order, Duration timeout) {
        boolean canceled = orderStatusService.tryTransition(
                order.orderId(),
                order.status(),
                OrderStatus.CANCELED,
                OrderEventType.ORDER_CANCELED,
                "Заказ отменён: не дождался оплаты за " + timeout.toMinutes() + " мин"
        );

        if (!canceled) {
            // Пока задача собиралась, заказ успели оплатить или отменить — это и есть ответ.
            return;
        }

        if (order.status().requiresRefundOnCancel()) {
            paymentCommands.requestRefund(order);
        }

        log.info("Заказ отменён по таймауту оплаты: orderId={}, status={}", order.orderId(), order.status());
    }

    /**
     * Таймаут из процесса Camunda. Действует, только если заказ всё ещё ждёт исхода: таймер мог
     * сработать у процесса, чья транзакция запроса оплаты откатилась, или опередить отмену.
     */
    @Transactional
    public void expireIfAwaiting(Long orderId, Duration timeout) {
        OrderAccess order = orderStatusService.requireAccess(orderId);

        if (order.status() != OrderStatus.PAYMENT_PENDING) {
            log.info("Таймаут оплаты пропущен: заказ уже не ждёт исхода, orderId={}, status={}",
                    orderId, order.status());
            return;
        }

        expire(order, timeout);
    }

    @Transactional
    public void markPaid(Long orderId, Long paymentId) {
        boolean paid = orderStatusService.tryTransition(
                orderId,
                OrderStatus.PAYMENT_PENDING,
                OrderStatus.PAID,
                OrderEventType.ORDER_PAID,
                "Оплата прошла: paymentId=" + paymentId
        );

        if (!paid) {
            log.info("Оплата пришла к заказу не в статусе PAYMENT_PENDING — заказ отменён раньше, "
                            + "возврат уже запрошен: orderId={}, status={}",
                    orderId, orderStatusService.requireAccess(orderId).status());
        }
    }

    @Transactional
    public void cancelOnPaymentFailure(Long orderId, String failureReason) {
        orderStatusService.recordEvent(
                orderId,
                OrderEventType.ORDER_PAYMENT_FAILED,
                "Оплата не прошла: " + failureReason
        );

        orderStatusService.tryTransition(
                orderId,
                OrderStatus.PAYMENT_PENDING,
                OrderStatus.CANCELED,
                OrderEventType.ORDER_CANCELED,
                "Заказ отменён: оплата не прошла"
        );
    }

    private void onRefunded(PaymentEvent event) {
        orderStatusService.recordEvent(
                event.orderId(),
                OrderEventType.ORDER_REFUNDED,
                "Возврат средств: " + event.amount()
        );

        log.info("Деньги за заказ возвращены: orderId={}, paymentId={}", event.orderId(), event.paymentId());
    }
}
