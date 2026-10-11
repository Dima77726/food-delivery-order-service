package com.dima.fooddelivery.common.db;

import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Проверяет, что миграции доезжают до последнего changeset и что денежные констрейнты реально
 * работают на уровне базы, а не только в коде.
 */
class LiquibaseMigrationIT extends AbstractIntegrationTest {

    private static final String LATEST_CHANGESET_ID = "001-create-orders";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyLatestMigrationAndUseTwoDecimalPlacesForOrderItemPrice() {
        Integer appliedChangesets = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM databasechangelog
                        WHERE id = ?
                        """,
                Integer.class,
                LATEST_CHANGESET_ID
        );

        MoneyColumnDefinition priceDefinition = jdbcTemplate.queryForObject(
                """
                        SELECT numeric_precision, numeric_scale
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'customer_order_item'
                          AND column_name = 'price'
                        """,
                (resultSet, rowNum) -> new MoneyColumnDefinition(
                        resultSet.getInt("numeric_precision"),
                        resultSet.getInt("numeric_scale")
                )
        );

        assertAll(
                () -> assertEquals(1, appliedChangesets),
                () -> assertEquals(10, priceDefinition.precision()),
                () -> assertEquals(2, priceDefinition.scale())
        );
    }

    @Test
    void shouldRejectNegativeOrderItemLineTotal() {
        Long customerId = 100_001L;
        var order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        // Отдельное блюдо: на позицию из фикстуры сработал бы unique-констрейнт
        // (order_id, menu_item_id), и тест прошёл бы не из-за проверки line_total.
        Long otherMenuItemId = testData.insertMenuItem(order.restaurantId(), new BigDecimal("99.99"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                                INSERT INTO customer_order_item (
                                    order_id, menu_item_id, menu_item_name, quantity, price, line_total
                                )
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                        order.orderId(),
                        otherMenuItemId,
                        "Invalid test item",
                        1,
                        new BigDecimal("99.99"),
                        new BigDecimal("-99.99")
                )
        );
    }

    private record MoneyColumnDefinition(int precision, int scale) {
    }
}
