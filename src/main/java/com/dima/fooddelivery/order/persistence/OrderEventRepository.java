package com.dima.fooddelivery.order.persistence;

import com.dima.fooddelivery.order.domain.OrderEvent;
import com.dima.fooddelivery.order.domain.OrderEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * История заказа. Только запись и чтение — обновления и удаления нет сознательно: журнал,
 * который можно править задним числом, ничего не доказывает.
 *
 * <p>Раньше метод записи события был продублирован в {@code OrderService} и
 * {@code DeliveryService}. Дублирование было не причиной, а симптомом: модуль Delivery писал
 * в таблицу чужого модуля напрямую. Теперь единственный вход сюда — через модуль Order.
 */
@Repository
@RequiredArgsConstructor
public class OrderEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public int append(Long orderId, OrderEventType eventType, String description) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("eventType", eventType.getDbValue())
                .addValue("description", description);

        return jdbc.update(
                """
                        INSERT INTO customer_order_event (order_id, event_type, description)
                        VALUES (:orderId, :eventType, :description)
                        """,
                params
        );
    }

    public List<OrderEvent> findByOrderId(Long orderId) {
        return jdbc.query(
                """
                        SELECT e.id, e.order_id, e.event_type, e.description, e.created_at
                        FROM customer_order_event e
                        WHERE e.order_id = :orderId
                        ORDER BY e.created_at ASC, e.id ASC
                        """,
                new MapSqlParameterSource("orderId", orderId),
                OrderRowMappers.ORDER_EVENT
        );
    }
}
