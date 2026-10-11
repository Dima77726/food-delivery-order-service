package com.dima.fooddelivery.order.api;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal
) {

}
