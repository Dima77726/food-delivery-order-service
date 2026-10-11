package com.dima.fooddelivery.cart.domain;

import java.math.BigDecimal;

public record CartItem(
        Long id,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price
) {

    /**
     * Стоимость позиции считается, а не хранится: в {@code cart_item} нет колонки line_total,
     * и заводить её означало бы держать в базе значение, которое обязано совпадать
     * с {@code price * quantity}. Такие поля рано или поздно расходятся.
     */
    public BigDecimal lineTotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
