package com.dima.fooddelivery.order.api;

import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(
        Long id,
        Long cartId,
        Long customerId,
        Long restaurantId,
        String status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items
) {
}
