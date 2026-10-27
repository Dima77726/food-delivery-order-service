package com.dima.fooddelivery.promo;

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
public class PromoRepository {

    private static final RowMapper<PromoCode> PROMO = (rs, n) -> new PromoCode(
            rs.getString("code"),
            PromoCode.Type.valueOf(rs.getString("discount_type")),
            rs.getBigDecimal("discount_value"),
            rs.getBigDecimal("max_discount"),
            rs.getBigDecimal("min_order_amount"),
            rs.getObject("restaurant_id", Long.class),
            rs.getInt("max_uses"),
            rs.getInt("used_count"),
            rs.getObject("valid_from", OffsetDateTime.class),
            rs.getObject("valid_until", OffsetDateTime.class),
            rs.getBoolean("active"));

    private static final String SELECT = """
            SELECT code, discount_type, discount_value, max_discount, min_order_amount, restaurant_id,
                   max_uses, used_count, valid_from, valid_until, active
            FROM promo_code
            """;

    private final JdbcTemplate jdbc;

    public Optional<PromoCode> find(String code) {
        return jdbc.query(SELECT + " WHERE code = ?", PROMO, code).stream().findFirst();
    }

    public List<PromoCode> findAll() {
        return jdbc.query(SELECT + " ORDER BY created_at DESC", PROMO);
    }

    public List<String> allCodes() {
        return jdbc.queryForList("SELECT code FROM promo_code", String.class);
    }

    public void insert(PromoCode promo) {
        jdbc.update("""
                INSERT INTO promo_code (code, discount_type, discount_value, max_discount, min_order_amount, restaurant_id,
                                        max_uses, valid_from, valid_until, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?, TRUE)
                """, promo.code(), promo.type().name(), promo.value(), promo.maxDiscount(), promo.minOrderAmount(),
                promo.restaurantId(), promo.maxUses(), promo.validFrom(), promo.validUntil());
    }

    public void deactivate(String code) {
        jdbc.update("UPDATE promo_code SET active = FALSE WHERE code = ?", code);
    }

    /**
     * Занять одно использование. Проверка лимита — внутри UPDATE: два одновременных последних применения
     * не пройдут оба, второе затронет ноль строк. «Прочитать used_count, сравнить, записать +1» в двух
     * транзакциях продало бы последний экземпляр дважды.
     *
     * @return {@code false}, если лимит исчерпан
     */
    public boolean tryConsume(String code) {
        return jdbc.update("""
                UPDATE promo_code SET used_count = used_count + 1
                WHERE code = ? AND active AND used_count < max_uses
                  AND valid_from <= CURRENT_TIMESTAMP AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, code) == 1;
    }

    /** Один код — один раз на клиента: нарушение первичного ключа (code, customer_id) и есть отказ. */
    public void insertRedemption(String code, long customerId, long orderId) {
        jdbc.update("INSERT INTO promo_redemption (code, customer_id, order_id) VALUES (?, ?, ?)", code, customerId, orderId);
    }

    public boolean usedBy(String code, long customerId) {
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM promo_redemption WHERE code = ? AND customer_id = ?)",
                Boolean.class, code, customerId);
        return Boolean.TRUE.equals(used);
    }

    public record PromoCode(
            String code,
            Type type,
            BigDecimal value,
            BigDecimal maxDiscount,
            BigDecimal minOrderAmount,
            Long restaurantId,
            int maxUses,
            int usedCount,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            boolean active
    ) {
        public enum Type { PERCENT, FIXED }
    }
}
