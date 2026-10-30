package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Передаёт процессу {@code order-lifecycle} смену статуса заказа: ресторан принял, готовит,
 * курьер забрал, доставлено, клиент отменил.
 *
 * <p>После коммита: смену, которая откатилась, процессу знать не нужно. Сбой публикации статус
 * заказа не трогает — он уже сохранён, а процесс останется на прежнем шаге, что будет видно
 * в Operate. Для отмены во время оплаты это безопасно: процесс дождётся исхода или таймера,
 * а оба задания увидят отменённый заказ и ничего не сделают.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.saga.engine", havingValue = "camunda")
public class OrderStatusMessageListener {

    private final OrderLifecycleProcess process;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OrderStatusChangedEvent event) {
        try {
            process.publishStatus(event.orderId(), event.newStatus())
                    .ifPresent(message -> log.debug("Статус заказа передан процессу: orderId={}, message={}",
                            event.orderId(), message));
        } catch (RuntimeException exception) {
            log.warn("Не удалось передать процессу статус заказа: orderId={}, status={}",
                    event.orderId(), event.newStatus(), exception);
        }
    }
}
