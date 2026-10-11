package com.dima.fooddelivery.delivery.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryStatusTest {

    @Test
    void shouldResolveStatusFromDatabaseValue() {
        assertSame(DeliveryStatus.CREATED, DeliveryStatus.fromDbValue("CREATED"));
        assertSame(DeliveryStatus.PICKED_UP, DeliveryStatus.fromDbValue("PICKED_UP"));
        assertSame(DeliveryStatus.CANCELED, DeliveryStatus.fromDbValue("CANCELED"));
    }

    @Test
    void shouldRejectUnknownDatabaseValue() {
        assertThrows(IllegalArgumentException.class, () -> DeliveryStatus.fromDbValue("UNKNOWN"));
    }

    @Test
    void shouldAllowOnlyValidTransitions() {
        assertTrue(DeliveryStatus.CREATED.canBeAssignedToCourier());
        assertTrue(DeliveryStatus.ASSIGNED.canBePickedUpByCourier());
        assertTrue(DeliveryStatus.PICKED_UP.canBeDeliveredByCourier());

        assertFalse(DeliveryStatus.ASSIGNED.canBeAssignedToCourier());
        assertFalse(DeliveryStatus.CREATED.canBeDeliveredByCourier());
        assertFalse(DeliveryStatus.DELIVERED.canBePickedUpByCourier());
    }
}
