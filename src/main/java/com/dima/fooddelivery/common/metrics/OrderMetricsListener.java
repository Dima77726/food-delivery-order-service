package com.dima.fooddelivery.common.metrics;

import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Считает переходы заказа по тому же событию, на которое подписаны уведомления и аудит.
 *
 * <p>Третий подписчик на одно событие — хорошая проверка того, что развязка через события
 * себя оправдала: модуль Order по-прежнему не знает ни про метрики, ни про почту, ни про
 * журнал, и добавление четвёртого потребителя его не тронет.
 */
@Component
@RequiredArgsConstructor
public class OrderMetricsListener {

    private final BusinessMetrics metrics;

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.newStatus() == OrderStatus.CANCELED) {
            metrics.orderCanceled();
        } else if (event.newStatus() == OrderStatus.DELIVERED) {
            metrics.orderDelivered();
        }
    }
}
