package com.dima.fooddelivery.order.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record OrderSummaryResponse(
        Long id,
        Long cartId,
        Long customerId,
        Long restaurantId,
        String status,
        BigDecimal totalAmount,
        Long itemsCount,
        OffsetDateTime createdAt
) {
}
