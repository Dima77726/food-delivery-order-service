package com.dima.fooddelivery.order.persistence;

import com.dima.fooddelivery.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Плоская строка join'а заказа с позициями: поля заказа повторяются для каждой позиции.
 *
 * <p>Тип package-private намеренно — это форма результата запроса, а не объект предметной
 * области. Схлопывание дубликатов в {@code Order} происходит внутри репозитория, и наружу
 * эта форма не выходит.
 */
record OrderRow(
        Long orderId,
        Long cartId,
        Long customerId,
        Long restaurantId,
        OrderStatus orderStatus,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long orderItemId,
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal
) {
}
