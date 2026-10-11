package com.dima.fooddelivery.order.saga;

import com.dima.fooddelivery.order.domain.OrderAccess;
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
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.saga.timeout-check-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentTimeoutJob {

    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final PaymentSaga paymentSaga;
    private final Duration timeout;

    public PaymentTimeoutJob(
            OrderRepository orderRepository,
            PaymentSaga paymentSaga,
            @Value("${app.saga.payment-timeout:PT15M}") Duration timeout
    ) {
        this.orderRepository = orderRepository;
        this.paymentSaga = paymentSaga;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${app.saga.timeout-check-interval:PT1M}")
    public void cancelExpired() {
        List<OrderAccess> expired = orderRepository.findAwaitingPaymentSince(
                OffsetDateTime.now().minus(timeout),
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
