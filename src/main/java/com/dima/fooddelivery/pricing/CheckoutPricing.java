package com.dima.fooddelivery.pricing;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.pricing.OrderTermsRepository.OrderTerms;
import com.dima.fooddelivery.promo.PromoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Сумма заказа при оформлении: блюда − скидка по промокоду + доставка по текущему спросу + чаевые.
 *
 * <p>Доставка берётся, только если указан адрес: заказ без адреса — прежний сценарий «самовывоза», и его
 * сумма не меняется. Все суммы фиксируются в {@code order_terms} в момент оформления; дальнейшие
 * изменения спроса и промокода заказ не трогают.
 */
@Component
@RequiredArgsConstructor
public class CheckoutPricing {

    private final SurgePricing surge;
    private final PromoService promoService;
    private final PricingProperties properties;

    /** Проверить параметры и посчитать всё, кроме погашения промокода (для него нужен id заказа). */
    public Draft draft(long customerId, long restaurantId, BigDecimal itemsTotal, boolean withDelivery, Options options) {
        BigDecimal tip = options.tipAmount() == null ? BigDecimal.ZERO : options.tipAmount().setScale(2, RoundingMode.HALF_UP);
        if (tip.signum() < 0 || tip.compareTo(properties.maxTip()) > 0) {
            throw new BusinessRuleViolationException("Чаевые — от 0 до " + properties.maxTip().toPlainString() + " ₽");
        }
        if (options.scheduledFor() != null) {
            OffsetDateTime now = OffsetDateTime.now();
            if (options.scheduledFor().isBefore(now.plus(properties.scheduleMinLead()))) {
                throw new BusinessRuleViolationException("Ко времени — не раньше чем через " + properties.scheduleMinLead().toMinutes() + " минут");
            }
            if (options.scheduledFor().isAfter(now.plus(properties.scheduleMaxAhead()))) {
                throw new BusinessRuleViolationException("Ко времени — не дальше чем на " + properties.scheduleMaxAhead().toDays() + " дней вперёд");
            }
        }

        BigDecimal discount = options.promoCode() == null || options.promoCode().isBlank()
                ? BigDecimal.ZERO.setScale(2)
                : promoService.preview(options.promoCode(), customerId, restaurantId, itemsTotal);

        SurgePricing.Quote quote = surge.current();
        BigDecimal fee = withDelivery ? quote.fee() : BigDecimal.ZERO.setScale(2);
        BigDecimal multiplier = withDelivery ? quote.multiplier() : BigDecimal.ONE.setScale(1);

        return new Draft(itemsTotal, PromoService.normalize(options.promoCode()), discount, fee, multiplier, tip, options.scheduledFor());
    }

    /**
     * @param promoCode   промокод или {@code null}
     * @param tipAmount   чаевые курьеру
     * @param scheduledFor к какому времени доставить, {@code null} — как можно скорее
     */
    public record Options(String promoCode, BigDecimal tipAmount, OffsetDateTime scheduledFor) {
        public static final Options NONE = new Options(null, null, null);
    }

    public record Draft(BigDecimal itemsTotal, String promoCode, BigDecimal discount, BigDecimal deliveryFee,
                        BigDecimal surgeMultiplier, BigDecimal tipAmount, OffsetDateTime scheduledFor) {

        public BigDecimal total() {
            return itemsTotal.subtract(discount).add(deliveryFee).add(tipAmount);
        }

        public OrderTerms toTerms(long orderId, BigDecimal redeemedDiscount) {
            return new OrderTerms(orderId, itemsTotal, promoCode, redeemedDiscount, deliveryFee, surgeMultiplier, tipAmount, scheduledFor);
        }

        public boolean hasPromo() {
            return promoCode != null && !promoCode.isBlank();
        }
    }
}
