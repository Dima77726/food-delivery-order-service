package com.dima.fooddelivery.delivery.domain;

import com.dima.fooddelivery.order.domain.DeliveryAddress;

import java.time.OffsetDateTime;

public record Delivery(
        Long id,
        Long orderId,
        Long courierId,
        DeliveryStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime pickedUpAt,
        OffsetDateTime deliveredAt,
        DeliveryAddress address
) {

    public boolean assignedTo(Long candidateCourierId) {
        return courierId != null && courierId.equals(candidateCourierId);
    }
}
