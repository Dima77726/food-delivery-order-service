package com.dima.fooddelivery.order.domain;

import java.time.OffsetDateTime;

/**
 * Запись в истории заказа. История только дописывается — существующие события не меняются
 * и не удаляются, иначе она перестаёт быть доказательством того, что происходило.
 */
public record OrderEvent(
        Long id,
        Long orderId,
        OrderEventType eventType,
        String description,
        OffsetDateTime createdAt
) {
}
