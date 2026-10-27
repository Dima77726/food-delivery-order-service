package com.dima.fooddelivery.pricing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderTermsRepository {

    private static final RowMapper<OrderTerms> TERMS = (rs, n) -> new OrderTerms(
            rs.getLong("order_id"),
            rs.getBigDecimal("items_total"),
            rs.getString("promo_code"),
            rs.getBigDecimal("discount"),
            rs.getBigDecimal("delivery_fee"),
            rs.getBigDecimal("surge_multiplier"),
            rs.getBigDecimal("tip_amount"),
            rs.getObject("scheduled_for", OffsetDateTime.class));

    private static final String SELECT = """
            SELECT order_id, items_total, promo_code, discount, delivery_fee, surge_multiplier, tip_amount, scheduled_for
            FROM order_terms
            """;

    private final JdbcTemplate jdbc;

    public void insert(OrderTerms terms) {
        jdbc.update("""
                INSERT INTO order_terms (order_id, items_total, promo_code, discount, delivery_fee, surge_multiplier, tip_amount, scheduled_for)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, terms.orderId(), terms.itemsTotal(), terms.promoCode(), terms.discount(), terms.deliveryFee(),
                terms.surgeMultiplier(), terms.tipAmount(), terms.scheduledFor());
    }

    public Optional<OrderTerms> find(long orderId) {
        return jdbc.query(SELECT + " WHERE order_id = ?", TERMS, orderId).stream().findFirst();
    }

    /** Заказы ко времени, которым пора напомнить «готовьте»: срок ближе, чем {@code window}. */
    public List<Due> findDueForPrep(java.time.Duration window, int limit) {
        return jdbc.query("""
                SELECT t.order_id, t.scheduled_for, o.restaurant_id
                FROM order_terms t JOIN customer_order o ON o.id = t.order_id
                WHERE t.scheduled_for IS NOT NULL AND t.prep_reminded_at IS NULL
                  AND t.scheduled_for <= CURRENT_TIMESTAMP + CAST(? AS INTERVAL)
                  AND o.status IN ('PAID', 'ACCEPTED')
                ORDER BY t.scheduled_for
                LIMIT ?
                """, (rs, n) -> new Due(rs.getLong("order_id"), rs.getObject("scheduled_for", OffsetDateTime.class), rs.getLong("restaurant_id")),
                window.toSeconds() + " seconds", limit);
    }

    /** @return {@code true}, если напоминание отмечено этим вызовом (а не параллельным) */
    public boolean markReminded(long orderId) {
        return jdbc.update("UPDATE order_terms SET prep_reminded_at = CURRENT_TIMESTAMP WHERE order_id = ? AND prep_reminded_at IS NULL", orderId) == 1;
    }

    /** Чаевые курьера за доставленные заказы. */
    public BigDecimal tipsOfCourier(long courierId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(t.tip_amount), 0) FROM order_terms t
                JOIN delivery d ON d.order_id = t.order_id
                WHERE d.courier_id = ? AND d.status = 'DELIVERED'
                """, BigDecimal.class, courierId);
    }

    /**
     * Из чего сложилась сумма заказа. Итог (customer_order.total_amount) = itemsTotal − discount + deliveryFee + tipAmount.
     *
     * @param surgeMultiplier коэффициент спроса, по которому посчитана доставка, — цена зафиксирована
     * @param scheduledFor    к какому времени доставить; {@code null} — как можно скорее
     */
    public record OrderTerms(
            long orderId,
            BigDecimal itemsTotal,
            String promoCode,
            BigDecimal discount,
            BigDecimal deliveryFee,
            BigDecimal surgeMultiplier,
            BigDecimal tipAmount,
            OffsetDateTime scheduledFor
    ) {
        public BigDecimal total() {
            return itemsTotal.subtract(discount).add(deliveryFee).add(tipAmount);
        }
    }

    public record Due(long orderId, OffsetDateTime scheduledFor, long restaurantId) {
    }
}
