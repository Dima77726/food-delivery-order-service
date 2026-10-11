package com.dima.fooddelivery.order.persistence;

import com.dima.fooddelivery.order.domain.OrderEvent;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;

/**
 * Мапперы строк модуля Order.
 *
 * <p>Раньше это были {@code @Component}-бины, которые внедрялись в сервис. Теперь это константы
 * внутри слоя persistence: маппер — деталь реализации репозитория, Spring-бином ему быть незачем,
 * а сервис о его существовании знать не должен.
 */
final class OrderRowMappers {

    static final RowMapper<OrderRow> ORDER_WITH_ITEMS = (rs, rowNum) -> new OrderRow(
            rs.getLong("order_id"),
            rs.getLong("cart_id"),
            rs.getLong("customer_id"),
            rs.getLong("restaurant_id"),
            OrderStatus.fromDbValue(rs.getString("order_status")),
            rs.getBigDecimal("total_amount"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("order_item_id", Long.class),
            rs.getObject("menu_item_id", Long.class),
            rs.getString("menu_item_name"),
            rs.getObject("quantity", Integer.class),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("line_total")
    );

    static final RowMapper<OrderEvent> ORDER_EVENT = (rs, rowNum) -> new OrderEvent(
            rs.getLong("id"),
            rs.getLong("order_id"),
            OrderEventType.fromDbValue(rs.getString("event_type")),
            rs.getString("description"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private OrderRowMappers() {
    }
}
