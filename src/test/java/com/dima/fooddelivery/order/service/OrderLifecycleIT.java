package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.service.DeliveryService;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.api.PayOrderRequest;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.PaymentMethod;
import com.dima.fooddelivery.order.saga.PaymentSaga;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestPayments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полный путь заказа через публичные методы сервисов — от корзины до доставки.
 *
 * <p>Этот тест намеренно проходит через все модули сразу: он проверяет не отдельный метод,
 * а то, что модули стыкуются между собой. Если кто-то сломает контракт между Order и Delivery,
 * упадёт именно он, а не десять узких тестов по одному.
 *
 * <p>Сервиса платежей здесь нет: его ответ подставляет {@link TestPayments} — ровно то
 * сообщение, которое пришло бы из Kafka. Путь через настоящий брокер проверяет
 * {@code PaymentSagaKafkaIT}.
 */
class OrderLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentSaga paymentSaga;

    @Autowired
    private TestPayments payments;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Создание заказа обязано попасть в журнал: статус не меняется, и слушатель смены статуса его не увидит. */
    @Test
    void shouldAuditOrderCreation() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.00"));
        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));

        Long orderId = orderService.createOrderFromActiveCart(customerId, restaurantId).id();

        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'ORDER_CREATED' AND resource_type = 'ORDER' AND resource_id = ?",
                Integer.class, String.valueOf(orderId));

        assertEquals(1, records);
    }

    @Test
    void shouldCarryOrderFromCartToDelivered() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 2));

        OrderResponse created = orderService.createOrderFromActiveCart(customerId, restaurantId);

        assertAll(
                () -> assertEquals(OrderStatus.CREATED.getDbValue(), created.status()),
                () -> assertEquals(new BigDecimal("900.00"), created.totalAmount()),
                () -> assertEquals(1, created.items().size()),
                () -> assertEquals(2, created.items().get(0).quantity())
        );

        Long orderId = created.id();

        OrderResponse pending = paymentSaga.requestPayment(customerId, orderId, cardPayment());
        assertEquals(OrderStatus.PAYMENT_PENDING.getDbValue(), pending.status());

        payments.succeeded(orderId);
        assertEquals(OrderStatus.PAID.getDbValue(), testData.currentOrderStatus(orderId));

        orderService.acceptOrder(restaurantId, orderId);
        orderService.startCookingOrder(restaurantId, orderId);
        orderService.markOrderReadyForDelivery(restaurantId, orderId);
        assertEquals(OrderStatus.READY_FOR_DELIVERY.getDbValue(), testData.currentOrderStatus(orderId));

        DeliveryResponse delivery = deliveryService.createDeliveryForOrder(orderId);
        deliveryService.assignCourierToDelivery(courierId, delivery.id());
        deliveryService.pickUpDelivery(courierId, delivery.id());
        assertEquals(OrderStatus.IN_DELIVERY.getDbValue(), testData.currentOrderStatus(orderId));

        deliveryService.deliverDelivery(courierId, delivery.id());

        assertAll(
                () -> assertEquals(OrderStatus.DELIVERED.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(
                        8,
                        orderService.getOrderEventsForCustomer(customerId, orderId).size(),
                        "в истории должны быть создание, запрос оплаты, оплата и пять переходов"
                ),
                // Уведомления проверяются не здесь. С переездом на Kafka они рождаются
                // у потребителя топика — после коммита и после публикации, а этот тест
                // работает в откатываемой транзакции с выключенным публикатором, поэтому
                // ни одного уведомления тут не появится по построению. Сквозной путь
                // до уведомления закрывает OrderOutboxKafkaIT.
                //
                // Что действительно обязано быть видно отсюда — след в outbox: он пишется
                // в той же транзакции, что и смена статуса, и это ровно та гарантия,
                // которая раньше держалась на внутрипроцессном слушателе.
                () -> assertTrue(
                        outboxEventCount(orderId) > 0,
                        "переходы заказа обязаны оставить события в outbox"
                ),
                () -> assertEquals(
                        "DELIVERED",
                        lastOutboxStatus(orderId),
                        "последним в outbox должен лежать перевод в DELIVERED"
                )
        );
    }

    @Test
    void shouldNotAllowRestaurantToAcceptUnpaidOrder() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("300.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));
        OrderResponse created = orderService.createOrderFromActiveCart(customerId, restaurantId);

        // Появление модуля Payment сделало PAID обязательным шагом: до этого ресторан
        // принимал заказ сразу из CREATED и начинал готовить за свой счёт.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> orderService.acceptOrder(restaurantId, created.id())
        );
    }

    @Test
    void shouldRefundMoneyWhenPaidOrderIsCanceled() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("500.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));
        OrderResponse created = orderService.createOrderFromActiveCart(customerId, restaurantId);

        paymentSaga.requestPayment(customerId, created.id(), cardPayment());
        payments.succeeded(created.id());
        orderService.cancelOrder(customerId, created.id());

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(created.id())),
                () -> assertEquals(
                        1,
                        testData.countPaymentCommands(created.id(), "REFUND_REQUESTED"),
                        "за отменённый оплаченный заказ обязана уйти команда на возврат"
                )
        );
    }

    @Test
    void shouldNotCreateSecondOrderFromSameCart() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("200.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));
        orderService.createOrderFromActiveCart(customerId, restaurantId);

        // Корзина ушла в CHECKED_OUT, активной больше нет — второй заказ создать не из чего.
        assertThrows(
                RuntimeException.class,
                () -> orderService.createOrderFromActiveCart(customerId, restaurantId)
        );
    }

    private PayOrderRequest cardPayment() {
        return new PayOrderRequest(PaymentMethod.CARD, UUID.randomUUID().toString());
    }

    private int outboxEventCount(Long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_event_outbox WHERE aggregate_id = ?",
                Integer.class,
                orderId
        );

        return count == null ? 0 : count;
    }

    /**
     * Тело события лежит в TEXT, поэтому приведение к JSONB — самый дешёвый способ
     * достать поле, не разбирая строку вручную и не завися от порядка ключей.
     */
    private String lastOutboxStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT CAST(payload AS JSONB) ->> 'newStatus'
                        FROM order_event_outbox
                        WHERE aggregate_id = ?
                          AND topic = 'food-delivery.order-events'
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                String.class,
                orderId
        );
    }
}
