package com.dima.fooddelivery.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurgePricingTest {

    private static final BigDecimal MAX = new BigDecimal("2.0");

    @Test
    void shouldStayAtOneWhenSupplyCoversDemand() {
        assertEquals(new BigDecimal("1.0"), SurgePricing.next(new BigDecimal("1.0"), 3, 5, MAX));
        assertEquals(new BigDecimal("1.0"), SurgePricing.next(new BigDecimal("1.0"), 0, 0, MAX));
    }

    @Test
    void shouldRiseGraduallyNotJump() {
        // Спрос втрое выше предложения: сырой 2.0, но сглаживание даёт полпути.
        BigDecimal first = SurgePricing.next(new BigDecimal("1.0"), 9, 3, MAX);
        BigDecimal second = SurgePricing.next(first, 9, 3, MAX);

        assertEquals(new BigDecimal("1.5"), first);
        assertEquals(new BigDecimal("1.8"), second);
    }

    @Test
    void shouldNeverExceedCap() {
        BigDecimal value = new BigDecimal("1.0");
        for (int i = 0; i < 20; i++) {
            value = SurgePricing.next(value, 100, 1, MAX);
        }
        assertEquals(new BigDecimal("2.0"), value);
    }

    @Test
    void shouldComeBackDownWhenCouriersArrive() {
        assertEquals(new BigDecimal("1.5"), SurgePricing.next(new BigDecimal("2.0"), 1, 10, MAX));
    }
}
