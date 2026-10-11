package com.dima.fooddelivery.cart.domain;

import java.math.BigDecimal;
import java.util.List;

public record Cart(
        Long id,
        Long customerId,
        Long restaurantId,
        CartStatus status,
        List<CartItem> items
) {

    public BigDecimal totalAmount() {
        return items.stream()
                .map(CartItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
