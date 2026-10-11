package com.dima.fooddelivery.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Заказ без позиций, для списка заказов клиента.
 *
 * <p>Отдельный тип вместо {@link Order} с пустым списком: список заказов не должен тянуть из базы
 * все позиции всех заказов ради того, чтобы показать их количество.
 */
public record OrderSummary(
        Long id,
        Long cartId,
        Long customerId,
        Long restaurantId,
        OrderStatus status,
        BigDecimal totalAmount,
        long itemsCount,
        OffsetDateTime createdAt
) {
}
