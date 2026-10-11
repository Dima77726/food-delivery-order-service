package com.dima.fooddelivery.cart.api;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        Long id,
        Long customerId,
        Long restaurantId,
        String status,
        List<CartItemResponse> items,
        BigDecimal totalAmount
) {
}
