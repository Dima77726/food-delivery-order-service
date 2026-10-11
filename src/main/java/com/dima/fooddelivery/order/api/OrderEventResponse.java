package com.dima.fooddelivery.order.api;

import java.time.OffsetDateTime;

public record OrderEventResponse(
        Long id,
        Long orderId,
        String eventType,
        String description,
        OffsetDateTime createAt
) {
}
