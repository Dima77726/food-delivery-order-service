package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Сообщает процессу оплаты, что клиент отменил заказ, — чтобы процесс завершился сразу,
 * а не висел до таймера.
 *
 * <p>После коммита: отмену, которая откатилась, процессу знать не нужно. Сбой здесь не страшен —
 * процесс дождётся исхода или таймера, а оба задания увидят отменённый заказ и ничего не сделают.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.saga.engine", havingValue = "camunda")
public class OrderCanceledMessageListener {

    private final PaymentProcess paymentProcess;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OrderStatusChangedEvent event) {
        if (event.newStatus() != OrderStatus.CANCELED || event.previousStatus() != OrderStatus.PAYMENT_PENDING) {
            return;
        }

        try {
            paymentProcess.publishOrderCanceled(event.orderId());
        } catch (RuntimeException exception) {
            log.warn("Не удалось сообщить процессу оплаты об отмене: orderId={}", event.orderId(), exception);
        }
    }
}
