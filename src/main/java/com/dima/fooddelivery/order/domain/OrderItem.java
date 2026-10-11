package com.dima.fooddelivery.order.domain;

import java.math.BigDecimal;

/**
 * Позиция заказа.
 *
 * <p>{@code menuItemName} и {@code price} скопированы из меню в момент оформления намеренно:
 * заказ обязан помнить, что и почём клиент купил, даже если блюдо потом переименуют,
 * подорожают или удалят из меню.
 */
public record OrderItem(
        Long id,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal
) {
}
