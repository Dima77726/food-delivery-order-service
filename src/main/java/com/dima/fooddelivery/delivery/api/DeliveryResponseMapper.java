package com.dima.fooddelivery.delivery.api;

import com.dima.fooddelivery.delivery.domain.Delivery;

import java.util.List;

public final class DeliveryResponseMapper {

    public static DeliveryResponse toResponse(Delivery delivery) {
        return new DeliveryResponse(
                delivery.id(),
                delivery.orderId(),
                delivery.courierId(),
                delivery.status().getDbValue(),
                delivery.createdAt(),
                delivery.updatedAt(),
                delivery.pickedUpAt(),
                delivery.deliveredAt()
        );
    }

    public static List<DeliveryResponse> toResponses(List<Delivery> deliveries) {
        return deliveries.stream().map(DeliveryResponseMapper::toResponse).toList();
    }

    private DeliveryResponseMapper() {
    }
}
