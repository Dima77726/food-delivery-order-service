package com.dima.fooddelivery.delivery.api;

import com.dima.fooddelivery.order.api.DeliveryAddressResponse;

import java.time.OffsetDateTime;

public record DeliveryResponse(
        Long id,
        Long orderId,
        Long courierId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime pickedUpAt,
        OffsetDateTime deliveredAt,
        DeliveryAddressResponse deliveryAddress
) {
}
