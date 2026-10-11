package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Пишет в журнал каждую смену статуса заказа.
 *
 * <p>Аудит подписан на то же событие, что и уведомления, но обрабатывает все переходы без
 * исключения: журнал не имеет права быть выборочным.
 */
@Component
@RequiredArgsConstructor
public class OrderAuditListener {

    private static final String RESOURCE_TYPE = "ORDER";

    private final AuditService auditService;

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        auditService.recordSuccess(
                event.eventType().getDbValue(),
                RESOURCE_TYPE,
                String.valueOf(event.orderId()),
                "Статус: " + event.previousStatus().getDbValue() + " -> " + event.newStatus().getDbValue()
        );
    }
}
