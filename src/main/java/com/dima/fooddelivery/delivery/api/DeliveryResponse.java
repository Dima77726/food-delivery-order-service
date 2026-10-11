package com.dima.fooddelivery.delivery.api;

import java.time.OffsetDateTime;

public record DeliveryResponse(
        Long id,
        Long orderId,
        Long courierId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime pickedUpAt,
        OffsetDateTime deliveredAt
) {
}
