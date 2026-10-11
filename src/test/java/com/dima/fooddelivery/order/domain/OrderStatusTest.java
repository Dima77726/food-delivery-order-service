package com.dima.fooddelivery.order.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    @Test
    void shouldResolveStatusFromDatabaseValue() {
        assertSame(OrderStatus.CREATED, OrderStatus.fromDbValue("CREATED"));
        assertSame(OrderStatus.READY_FOR_DELIVERY, OrderStatus.fromDbValue("READY_FOR_DELIVERY"));
    }

    @Test
    void shouldRejectUnknownDatabaseValue() {
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.fromDbValue("UNKNOWN"));
    }

    @Test
    void shouldAllowOnlyValidTransitions() {
        assertTrue(OrderStatus.CREATED.canBePaidByCustomer());
        assertTrue(OrderStatus.PAID.canBeAcceptedByRestaurant());
        assertTrue(OrderStatus.ACCEPTED.canStartCookingByRestaurant());
        assertTrue(OrderStatus.COOKING.canBeMarkedReadyForDeliveryByRestaurant());
        assertTrue(OrderStatus.READY_FOR_DELIVERY.canCreateDelivery());
        assertTrue(OrderStatus.READY_FOR_DELIVERY.canBeMovedToInDeliveryByCourier());
        assertTrue(OrderStatus.IN_DELIVERY.canBeCompletedByCourier());

        assertFalse(OrderStatus.COOKING.canBeAcceptedByRestaurant());
        assertFalse(OrderStatus.CREATED.canCreateDelivery());
        assertFalse(OrderStatus.DELIVERED.canBeCompletedByCourier());
        assertFalse(OrderStatus.PAID.canBePaidByCustomer());
    }

    /**
     * Появление модуля Payment сделало PAID обязательным шагом: ресторан больше не принимает
     * заказ прямо из CREATED, иначе кухня начинала бы работать до оплаты.
     */
    @Test
    void shouldRequirePaymentBeforeRestaurantAcceptsOrder() {
        assertFalse(OrderStatus.CREATED.canBeAcceptedByRestaurant());
        assertTrue(OrderStatus.PAID.canBeAcceptedByRestaurant());
    }

    @Test
    void shouldAllowCancellationOnlyBeforeRestaurantStartsWorking() {
        assertTrue(OrderStatus.CREATED.canBeCanceledByCustomer());
        assertTrue(OrderStatus.PAID.canBeCanceledByCustomer());

        assertFalse(OrderStatus.ACCEPTED.canBeCanceledByCustomer());
        assertFalse(OrderStatus.COOKING.canBeCanceledByCustomer());
        assertFalse(OrderStatus.DELIVERED.canBeCanceledByCustomer());
    }

    @Test
    void shouldMarkTerminalStatuses() {
        assertTrue(OrderStatus.DELIVERED.isFinal());
        assertTrue(OrderStatus.CANCELED.isFinal());

        assertFalse(OrderStatus.CREATED.isFinal());
        assertFalse(OrderStatus.IN_DELIVERY.isFinal());
    }
}
