package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.domain.DeliveryAddress;

import java.math.BigDecimal;

public record DeliveryAddressResponse(
        String address,
        String details,
        BigDecimal latitude,
        BigDecimal longitude
) {

    public static DeliveryAddressResponse from(DeliveryAddress address) {
        return address == null
                ? null
                : new DeliveryAddressResponse(address.address(), address.details(), address.latitude(), address.longitude());
    }
}
