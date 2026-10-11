package com.dima.fooddelivery.order.domain;

import java.math.BigDecimal;

/**
 * Минимум, нужный сервису, чтобы решить две вещи: вправе ли вызывающий трогать заказ
 * и допустим ли переход из текущего статуса.
 *
 * <p>Раньше для этого грузился весь заказ с позициями либо писался отдельный запрос под каждую
 * роль. Здесь один запрос обслуживает и клиента, и ресторан, и курьера — решение о том,
 * кто вправе, принимает сервис, а не WHERE в SQL.
 */
public record OrderAccess(
        Long orderId,
        Long customerId,
        Long restaurantId,
        OrderStatus status,
        BigDecimal totalAmount
) {

    public boolean belongsToCustomer(Long candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    public boolean belongsToRestaurant(Long candidateRestaurantId) {
        return restaurantId.equals(candidateRestaurantId);
    }
}
