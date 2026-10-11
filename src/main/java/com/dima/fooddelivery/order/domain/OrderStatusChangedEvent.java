package com.dima.fooddelivery.order.domain;

import java.math.BigDecimal;

/**
 * Событие смены статуса заказа внутри приложения.
 *
 * <p>Через него модуль Order сообщает о произошедшем, не зная, кто слушает. Иначе
 * {@code OrderStatusService} пришлось бы дать ссылку на {@code NotificationService},
 * а потом на аудит, потом на аналитику — и модуль-владелец заказа собрал бы вокруг себя
 * зависимости на половину системы.
 *
 * <p>Оно осталось внутренним и после появления Kafka. Наружу уходит отдельный тип —
 * {@code OrderIntegrationEvent}, а мостом между ними служит {@code OrderOutboxListener}.
 * Разделение нужно потому, что у этих событий разные правила изменения: внутреннее
 * переписывается вместе с кодом, внешнее читают чужие потребители, и его форма
 * меняется только совместимо.
 *
 * <p>Подписчики внутреннего события — те, чья работа обязана попасть в ту же транзакцию,
 * что и смена статуса: аудит, метрики и запись в outbox. Всё, что отправляется наружу,
 * подписано уже на топик.
 */
public record OrderStatusChangedEvent(
        Long orderId,
        Long customerId,
        Long restaurantId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        OrderEventType eventType,
        BigDecimal totalAmount
) {
}
