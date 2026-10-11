package com.dima.fooddelivery.cart.api;

import java.math.BigDecimal;

public record CartItemResponse(
        Long id,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal
) {
}
