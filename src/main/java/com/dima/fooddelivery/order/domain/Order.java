package com.dima.fooddelivery.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Заказ вместе с позициями — то, что модуль Order отдаёт наружу из слоя persistence.
 *
 * <p>Не путать с {@code OrderRow}: там плоская строка join'а, где поля заказа продублированы
 * для каждой позиции. Схлопывание дубликатов — работа репозитория, и наружу она не протекает.
 */
public record Order(
        Long id,
        Long cartId,
        Long customerId,
        Long restaurantId,
        OrderStatus status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<OrderItem> items
) {
}
