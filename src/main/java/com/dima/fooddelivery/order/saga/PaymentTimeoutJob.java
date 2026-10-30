package com.dima.fooddelivery.order.saga;

import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Отменяет заказы, которые не дождались оплаты.
 *
 * <p>Без этой задачи сага могла бы зависнуть навсегда: сервис платежей лёг, команда легла
 * в его топик, ответа нет — и заказ вечно в PAYMENT_PENDING, а клиент не может ни оплатить
 * его, ни понять, что происходит. Таймаут превращает «ответа нет» в определённый исход.
 *
 * <p>Несколько экземпляров сервиса могут выполнять задачу одновременно: отмена — это
 * compare-and-set по статусу, и второй экземпляр просто проиграет гонку.
 *
 * <p>С {@code app.saga.engine=camunda} оплаченный, но не дождавшийся исхода заказ (PAYMENT_PENDING)
 * отменяет таймер процесса {@code payment-saga}, а задача следит только за заказами, оплату
 * которых так и не запросили (CREATED): процесса у них нет.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.saga.timeout-check-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentTimeoutJob {

    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final PaymentSaga paymentSaga;
    private final Duration timeout;
    private final List<OrderStatus> statuses;

    public PaymentTimeoutJob(
            OrderRepository orderRepository,
            PaymentSaga paymentSaga,
            @Value("${app.saga.payment-timeout:PT15M}") Duration timeout,
            @Value("${app.saga.engine:native}") String engine
    ) {
        this.orderRepository = orderRepository;
        this.paymentSaga = paymentSaga;
        this.timeout = timeout;
        this.statuses = "camunda".equals(engine)
                ? List.of(OrderStatus.CREATED)
                : List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING);
    }

    @Scheduled(fixedDelayString = "${app.saga.timeout-check-interval:PT1M}")
    public void cancelExpired() {
        List<OrderAccess> expired = orderRepository.findAwaitingPaymentSince(
                OffsetDateTime.now().minus(timeout),
                statuses,
                BATCH_SIZE
        );

        for (OrderAccess order : expired) {
            try {
                paymentSaga.expire(order, timeout);
            } catch (RuntimeException exception) {
                // Один сломанный заказ не должен останавливать отмену остальных.
                log.error("Не удалось отменить заказ по таймауту оплаты: orderId={}", order.orderId(), exception);
            }
        }
    }
}
