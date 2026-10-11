package com.dima.fooddelivery.support;

import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.order.domain.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Собирает состояние базы для интеграционных тестов.
 *
 * <p>Данные вставляются напрямую через SQL, а не через публичные методы сервисов. Это сделано
 * намеренно: тест доставки должен падать из-за доставки, а не из-за того, что сломался
 * {@code CartService}. Подготовка данных не должна быть ещё одним тестируемым кодом.
 *
 * <p>Рестораны, блюда и пользователи — не в базе: они живут в других сервисах. Их заменяют
 * {@link TestCatalog}, {@link TestRestaurantAccess} и генератор идентификаторов.
 */
public class TestDataFactory {

    /**
     * Уникальные суффиксы для имён. Таблицы меню и категорий имеют unique-констрейнты по имени,
     * а откат транзакции не сбрасывает последовательности — поэтому счётчик, а не константа.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    /** Идентификаторы пользователей: в базе сервиса заказов их выдавать некому. */
    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 2_000_000_000L);

    private final JdbcTemplate jdbcTemplate;
    private final TestCatalog catalog;
    private final TestRestaurantAccess restaurantAccess;

    public TestDataFactory(JdbcTemplate jdbcTemplate, TestCatalog catalog, TestRestaurantAccess restaurantAccess) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalog = catalog;
        this.restaurantAccess = restaurantAccess;
    }

    public long uniqueSuffix() {
        return SEQUENCE.incrementAndGet();
    }

    /**
     * Пользователь — это только идентификатор. Пользователи живут в identity-service, в базе
     * сервиса заказов их нет, а заказы, корзины и доставки хранят их идентификаторы без внешних ключей.
     * Роли значения не имеют: в тестах их несёт токен ({@link TestJwt}).
     */
    public Long insertUser(String emailPrefix, String... roles) {
        return USER_IDS.incrementAndGet();
    }

    public Long insertCustomer() {
        return insertUser("customer", "CUSTOMER");
    }

    public Long insertCourier() {
        return insertUser("courier", "COURIER");
    }

    /** Управляющий ресторана — в тестовых правах вместо identity-service. */
    public void assignRestaurantManager(Long userId, Long restaurantId) {
        restaurantAccess.assignManager(userId, restaurantId);
    }

    public Long insertRestaurant() {
        return insertRestaurant(true);
    }

    /** Ресторан — в тестовом каталоге: ресторанов в базе заказов нет. */
    public Long insertRestaurant(boolean active) {
        return catalog.addRestaurant(active);
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price) {
        return insertMenuItem(restaurantId, price, true);
    }

    public Long insertMenuItem(Long restaurantId, BigDecimal price, boolean available) {
        return catalog.addItem(restaurantId, "Test Item " + uniqueSuffix(), price, available);
    }

    public Long insertCart(Long customerId, Long restaurantId, String status) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO cart (customer_id, restaurant_id, status)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                customerId,
                restaurantId,
                status
        );
    }

    public Long insertCartItem(Long cartId, Long menuItemId, int quantity, BigDecimal price) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO cart_item (cart_id, menu_item_id, menu_item_name, quantity, price)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                cartId,
                menuItemId,
                "Test Item " + menuItemId,
                quantity,
                price
        );
    }

    public Long insertOrder(Long cartId, Long customerId, Long restaurantId, OrderStatus status, BigDecimal totalAmount) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO customer_order (cart_id, customer_id, restaurant_id, status, total_amount)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                cartId,
                customerId,
                restaurantId,
                status.getDbValue(),
                totalAmount
        );
    }

    public Long insertOrderItem(Long orderId, Long menuItemId, int quantity, BigDecimal price) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO customer_order_item (
                            order_id, menu_item_id, menu_item_name, quantity, price, line_total
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                orderId,
                menuItemId,
                "Test Item",
                quantity,
                price,
                price.multiply(BigDecimal.valueOf(quantity))
        );
    }

    public Long insertDelivery(Long orderId, Long courierId, DeliveryStatus status) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO delivery (order_id, courier_id, status)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                orderId,
                courierId,
                status.getDbValue()
        );
    }

    /**
     * Заказ со всей обязательной обвязкой: ресторан, блюдо, оформленная корзина, одна позиция.
     * Возвращает контекст, чтобы тест мог достать любой из созданных идентификаторов.
     */
    public OrderContext insertOrderInStatus(Long customerId, OrderStatus status) {
        BigDecimal price = new BigDecimal("450.00");

        Long restaurantId = insertRestaurant();
        Long menuItemId = insertMenuItem(restaurantId, price);
        Long cartId = insertCart(customerId, restaurantId, "CHECKED_OUT");
        Long orderId = insertOrder(cartId, customerId, restaurantId, status, price);
        Long orderItemId = insertOrderItem(orderId, menuItemId, 1, price);

        return new OrderContext(restaurantId, menuItemId, cartId, orderId, orderItemId, price);
    }

    public String currentOrderStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?",
                String.class,
                orderId
        );
    }

    public String currentDeliveryStatus(Long deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM delivery WHERE id = ?",
                String.class,
                deliveryId
        );
    }

    public Integer countOrderEvents(Long orderId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_order_event WHERE order_id = ? AND event_type = ?",
                Integer.class,
                orderId,
                eventType
        );
    }

    /** Команды сервису платежей, лежащие в outbox по заказу. */
    public Integer countPaymentCommands(Long orderId, String commandType) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM order_event_outbox
                        WHERE aggregate_id = ? AND topic = 'food-delivery.payment-commands' AND event_type = ?
                        """,
                Integer.class,
                orderId,
                commandType
        );
    }

    public record OrderContext(
            Long restaurantId,
            Long menuItemId,
            Long cartId,
            Long orderId,
            Long orderItemId,
            BigDecimal price
    ) {
    }
}
