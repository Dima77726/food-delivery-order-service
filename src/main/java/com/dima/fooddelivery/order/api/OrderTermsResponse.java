package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.pricing.OrderTermsRepository.OrderTerms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Из чего сложилась сумма заказа. {@code null} у заказов, оформленных до появления промокодов и цены доставки.
 *
 * @param surgeMultiplier коэффициент спроса, по которому посчитана доставка (зафиксирован при оформлении)
 * @param scheduledFor    к какому времени доставить; {@code null} — как можно скорее
 */
public record OrderTermsResponse(
        BigDecimal itemsTotal,
        String promoCode,
        BigDecimal discount,
        BigDecimal deliveryFee,
        BigDecimal surgeMultiplier,
        BigDecimal tipAmount,
        OffsetDateTime scheduledFor
) {

    static OrderTermsResponse of(OrderTerms terms) {
        return terms == null ? null : new OrderTermsResponse(terms.itemsTotal(), terms.promoCode(), terms.discount(),
                terms.deliveryFee(), terms.surgeMultiplier(), terms.tipAmount(), terms.scheduledFor());
    }
}
