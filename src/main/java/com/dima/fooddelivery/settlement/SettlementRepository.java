package com.dima.fooddelivery.settlement;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class SettlementRepository {

    private static final RowMapper<SettlementLine> LINE = (rs, n) -> new SettlementLine(
            rs.getObject("settlement_date", LocalDate.class),
            rs.getLong("restaurant_id"),
            rs.getInt("orders_count"),
            rs.getBigDecimal("revenue"),
            rs.getBigDecimal("commission"),
            rs.getBigDecimal("payout"));

    private static final String SELECT = """
            SELECT settlement_date, restaurant_id, orders_count, revenue, commission, payout FROM settlement_line
            """;

    private final JdbcTemplate jdbc;

    public List<SettlementLine> findByDate(LocalDate date) {
        return jdbc.query(SELECT + " WHERE settlement_date = ? ORDER BY restaurant_id", LINE, date);
    }

    public List<SettlementLine> findByRestaurant(long restaurantId, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT + " WHERE restaurant_id = ? AND settlement_date BETWEEN ? AND ? ORDER BY settlement_date DESC",
                LINE, restaurantId, from, to);
    }
}
