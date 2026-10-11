package com.dima.fooddelivery.delivery.domain;

import java.time.OffsetDateTime;

public record Delivery(
        Long id,
        Long orderId,
        Long courierId,
        DeliveryStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime pickedUpAt,
        OffsetDateTime deliveredAt
) {

    public boolean assignedTo(Long candidateCourierId) {
        return courierId != null && courierId.equals(candidateCourierId);
    }
}
