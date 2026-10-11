package com.dima.fooddelivery.delivery.persistence;

import com.dima.fooddelivery.delivery.domain.Delivery;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DeliveryRepository {

    private static final RowMapper<Delivery> DELIVERY = (rs, rowNum) -> new Delivery(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getObject("courier_id", Long.class),
            DeliveryStatus.fromDbValue(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("picked_up_at", OffsetDateTime.class),
            rs.getObject("delivered_at", OffsetDateTime.class)
    );

    private static final String SELECT_DELIVERY = """
            SELECT d.id, d.order_id, d.courier_id, d.status,
                   d.created_at, d.updated_at, d.picked_up_at, d.delivered_at
            FROM delivery d
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<Delivery> findById(Long deliveryId) {
        return jdbc.query(
                SELECT_DELIVERY + " WHERE d.id = :deliveryId",
                new MapSqlParameterSource("deliveryId", deliveryId),
                DELIVERY
        ).stream().findFirst();
    }

    public Optional<Delivery> findByOrderId(Long orderId) {
        return jdbc.query(
                SELECT_DELIVERY + " WHERE d.order_id = :orderId",
                new MapSqlParameterSource("orderId", orderId),
                DELIVERY
        ).stream().findFirst();
    }

    public List<Delivery> findByCourierId(Long courierId) {
        return jdbc.query(
                SELECT_DELIVERY + " WHERE d.courier_id = :courierId ORDER BY d.created_at DESC, d.id DESC",
                new MapSqlParameterSource("courierId", courierId),
                DELIVERY
        );
    }

    public List<Delivery> findAvailableForAssignment() {
        return jdbc.query(
                SELECT_DELIVERY + " WHERE d.status = :status ORDER BY d.created_at, d.id",
                new MapSqlParameterSource("status", DeliveryStatus.CREATED.getDbValue()),
                DELIVERY
        );
    }

    public Long insert(Long orderId, DeliveryStatus status) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("status", status.getDbValue());

        return jdbc.queryForObject(
                """
                        INSERT INTO delivery (order_id, status)
                        VALUES (:orderId, :status)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    /**
     * Назначение курьера — это одновременно и смена статуса CREATED -> ASSIGNED.
     * Обе записи в одном UPDATE, чтобы не возникло состояния «курьер есть, статус старый».
     */
    public int assignCourier(Long deliveryId, Long courierId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId)
                .addValue("courierId", courierId)
                .addValue("expected", DeliveryStatus.CREATED.getDbValue())
                .addValue("next", DeliveryStatus.ASSIGNED.getDbValue());

        return jdbc.update(
                """
                        UPDATE delivery
                        SET courier_id = :courierId,
                            status = :next,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :deliveryId
                          AND status = :expected
                        """,
                params
        );
    }

    /**
     * Смена статуса доставки с одновременной простановкой отметки времени.
     *
     * <p>Имя колонки подставляется в SQL строкой — единственное место в проекте, где это
     * допущено. Значение приходит не от пользователя, а из {@link DeliveryTimestampColumn},
     * то есть множество вариантов закрыто enum'ом и внедрить сюда произвольный SQL нельзя.
     */
    public int compareAndSetStatus(
            Long deliveryId,
            DeliveryStatus expected,
            DeliveryStatus next,
            DeliveryTimestampColumn timestampColumn
    ) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId)
                .addValue("expected", expected.getDbValue())
                .addValue("next", next.getDbValue());

        String timestampAssignment = timestampColumn == DeliveryTimestampColumn.NONE
                ? ""
                : ", " + timestampColumn.getColumnName() + " = CURRENT_TIMESTAMP";

        return jdbc.update(
                """
                        UPDATE delivery
                        SET status = :next,
                            updated_at = CURRENT_TIMESTAMP%s
                        WHERE id = :deliveryId
                          AND status = :expected
                        """.formatted(timestampAssignment),
                params
        );
    }

    public enum DeliveryTimestampColumn {

        NONE(null),
        PICKED_UP_AT("picked_up_at"),
        DELIVERED_AT("delivered_at");

        private final String columnName;

        DeliveryTimestampColumn(String columnName) {
            this.columnName = columnName;
        }

        public String getColumnName() {
            return columnName;
        }
    }
}
