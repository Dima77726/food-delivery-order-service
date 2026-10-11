package com.dima.fooddelivery.cart.persistence;

import com.dima.fooddelivery.cart.domain.Cart;
import com.dima.fooddelivery.cart.domain.CartItem;
import com.dima.fooddelivery.cart.domain.CartStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CartRepository {

    private static final RowMapper<CartRow> CART_ROW = (rs, rowNum) -> new CartRow(
            rs.getLong("cart_id"),
            rs.getLong("customer_id"),
            rs.getLong("restaurant_id"),
            CartStatus.fromDbValue(rs.getString("cart_status")),
            rs.getObject("cart_item_id", Long.class),
            rs.getObject("menu_item_id", Long.class),
            rs.getString("menu_item_name"),
            rs.getObject("quantity", Integer.class),
            rs.getBigDecimal("price")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<Cart> findActiveCart(Long customerId, Long restaurantId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("restaurantId", restaurantId)
                .addValue("status", CartStatus.ACTIVE.getDbValue());

        List<CartRow> rows = jdbc.query(
                """
                        SELECT
                            c.id           AS cart_id,
                            c.customer_id,
                            c.restaurant_id,
                            c.status       AS cart_status,
                            ci.id          AS cart_item_id,
                            ci.menu_item_id,
                            ci.menu_item_name,
                            ci.quantity,
                            ci.price
                        FROM cart c
                        LEFT JOIN cart_item ci ON ci.cart_id = c.id
                        WHERE c.customer_id = :customerId
                          AND c.restaurant_id = :restaurantId
                          AND c.status = :status
                        ORDER BY ci.created_at, ci.id
                        """,
                params,
                CART_ROW
        );

        return assemble(rows);
    }

    public Optional<Long> findActiveCartId(Long customerId, Long restaurantId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("restaurantId", restaurantId)
                .addValue("status", CartStatus.ACTIVE.getDbValue());

        return jdbc.query(
                """
                        SELECT c.id
                        FROM cart c
                        WHERE c.customer_id = :customerId
                          AND c.restaurant_id = :restaurantId
                          AND c.status = :status
                        """,
                params,
                (rs, rowNum) -> rs.getLong("id")
        ).stream().findFirst();
    }

    public Long createActiveCart(Long customerId, Long restaurantId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("restaurantId", restaurantId)
                .addValue("status", CartStatus.ACTIVE.getDbValue());

        return jdbc.queryForObject(
                """
                        INSERT INTO cart (customer_id, restaurant_id, status)
                        VALUES (:customerId, :restaurantId, :status)
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    /**
     * Добавляет позицию или увеличивает количество уже существующей.
     *
     * <p>Сделано одним UPSERT'ом, а не парой SELECT + INSERT/UPDATE: при двух параллельных
     * запросах «проверить и вставить» второй упал бы на unique-констрейнте
     * {@code (cart_id, menu_item_id)}. ON CONFLICT перекладывает эту гонку на базу.
     */
    /**
     * Название и цена — снимок из каталога на момент добавления. Меню живёт в другом сервисе,
     * и join'ить его здесь нечем; а по смыслу клиент и должен видеть в корзине то, что выбрал.
     *
     * <p>Повторное добавление того же блюда увеличивает количество, но снимок не обновляет:
     * цена в корзине фиксируется первым добавлением, и название вместе с ней.
     */
    public int addOrIncreaseItem(Long cartId, Long menuItemId, String menuItemName, Integer quantity, BigDecimal price) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("cartId", cartId)
                .addValue("menuItemId", menuItemId)
                .addValue("menuItemName", menuItemName)
                .addValue("quantity", quantity)
                .addValue("price", price);

        return jdbc.update(
                """
                        INSERT INTO cart_item (cart_id, menu_item_id, menu_item_name, quantity, price)
                        VALUES (:cartId, :menuItemId, :menuItemName, :quantity, :price)
                        ON CONFLICT (cart_id, menu_item_id)
                        DO UPDATE SET
                            quantity = cart_item.quantity + EXCLUDED.quantity,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                params
        );
    }

    public int updateItemQuantity(Long cartId, Long cartItemId, Integer quantity) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("cartId", cartId)
                .addValue("cartItemId", cartItemId)
                .addValue("quantity", quantity);

        return jdbc.update(
                """
                        UPDATE cart_item
                        SET quantity = :quantity,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :cartItemId
                          AND cart_id = :cartId
                        """,
                params
        );
    }

    public int deleteItem(Long cartId, Long cartItemId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("cartId", cartId)
                .addValue("cartItemId", cartItemId);

        return jdbc.update(
                "DELETE FROM cart_item WHERE id = :cartItemId AND cart_id = :cartId",
                params
        );
    }

    public int touch(Long cartId) {
        return jdbc.update(
                "UPDATE cart SET updated_at = CURRENT_TIMESTAMP WHERE id = :cartId",
                new MapSqlParameterSource("cartId", cartId)
        );
    }

    /**
     * Тот же compare-and-set, что и у заказа: корзину можно оформить только из состояния ACTIVE,
     * и только один раз. Повторный вызов вернёт 0 вместо того, чтобы создать второй заказ
     * из той же корзины.
     */
    public int compareAndSetStatus(Long cartId, CartStatus expected, CartStatus next) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("cartId", cartId)
                .addValue("expected", expected.getDbValue())
                .addValue("next", next.getDbValue());

        return jdbc.update(
                """
                        UPDATE cart
                        SET status = :next,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :cartId
                          AND status = :expected
                        """,
                params
        );
    }

    private Optional<Cart> assemble(List<CartRow> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        CartRow first = rows.get(0);

        Map<Long, CartItem> items = new LinkedHashMap<>();
        for (CartRow row : rows) {
            if (row.cartItemId() != null) {
                items.putIfAbsent(row.cartItemId(), new CartItem(
                        row.cartItemId(),
                        row.menuItemId(),
                        row.menuItemName(),
                        row.quantity(),
                        row.price()
                ));
            }
        }

        return Optional.of(new Cart(
                first.cartId(),
                first.customerId(),
                first.restaurantId(),
                first.cartStatus(),
                new ArrayList<>(items.values())
        ));
    }

    private record CartRow(
            Long cartId,
            Long customerId,
            Long restaurantId,
            CartStatus cartStatus,
            Long cartItemId,
            Long menuItemId,
            String menuItemName,
            Integer quantity,
            BigDecimal price
    ) {
    }
}
