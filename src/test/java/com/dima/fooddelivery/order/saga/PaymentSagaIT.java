package com.dima.fooddelivery.order.saga;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.api.PayOrderRequest;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.PaymentMethod;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory.OrderContext;
import com.dima.fooddelivery.support.TestPayments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Сага оплаты со стороны сервиса заказов: команды, которые он отправляет, и реакции
 * на исходы, которые приходят в ответ.
 *
 * <p>Ответы сервиса платежей подставляет {@link TestPayments}; команды проверяются по outbox —
 * ровно там они и лежат до отправки в Kafka.
 */
class PaymentSagaIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentSaga paymentSaga;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private TestPayments payments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPutOrderIntoPaymentPendingAndSendPaymentCommand() {
        Long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        OrderResponse response = paymentSaga.requestPayment(customerId, order.orderId(), card());

        assertAll(
                () -> assertEquals(OrderStatus.PAYMENT_PENDING.getDbValue(), response.status()),
                () -> assertEquals(1, testData.countPaymentCommands(order.orderId(), "PAYMENT_REQUESTED")),
                // Сумма в команде — из заказа, а не из запроса клиента.
                () -> assertEquals("450.00", commandField(order.orderId(), "PAYMENT_REQUESTED", "amount")),
                () -> assertEquals(1, testData.countOrderEvents(order.orderId(), "ORDER_PAYMENT_REQUESTED"))
        );
    }

    /** Двойной клик: второй запрос с тем же ключом не запускает вторую оплату. */
    @Test
    void shouldNotSendSecondCommandForRepeatedRequestWithSameKey() {
        Long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        PayOrderRequest request = card();

        paymentSaga.requestPayment(customerId, order.orderId(), request);
        OrderResponse repeated = paymentSaga.requestPayment(customerId, order.orderId(), request);

        assertAll(
                () -> assertEquals(OrderStatus.PAYMENT_PENDING.getDbValue(), repeated.status()),
                () -> assertEquals(1, testData.countPaymentCommands(order.orderId(), "PAYMENT_REQUESTED"))
        );
    }

    @Test
    void shouldRejectSecondPaymentWithDifferentKey() {
        Long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        paymentSaga.requestPayment(customerId, order.orderId(), card());

        assertThrows(
                BusinessRuleViolationException.class,
                () -> paymentSaga.requestPayment(customerId, order.orderId(), card())
        );
    }

    @Test
    void shouldRejectPaymentForForeignOrder() {
        OrderContext order = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.CREATED);

        assertThrows(
                AccessDeniedForResourceException.class,
                () -> paymentSaga.requestPayment(testData.insertCustomer(), order.orderId(), card())
        );
    }

    @Test
    void shouldMarkOrderPaidWhenPaymentSucceeded() {
        Long orderId = pendingOrder();

        payments.succeeded(orderId);

        assertAll(
                () -> assertEquals(OrderStatus.PAID.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(1, testData.countOrderEvents(orderId, "ORDER_PAID"))
        );
    }

    /** Отказ банка отменяет заказ: ждать больше нечего, а повторная оплата — новый заказ. */
    @Test
    void shouldCancelOrderWhenPaymentFailed() {
        Long orderId = pendingOrder();

        payments.failed(orderId, "Недостаточно средств на карте");

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(1, testData.countOrderEvents(orderId, "ORDER_PAYMENT_FAILED")),
                // Денег не списали — возвращать нечего.
                () -> assertEquals(0, testData.countPaymentCommands(orderId, "REFUND_REQUESTED"))
        );
    }

    /** Повторная доставка того же исхода ничего не меняет второй раз. */
    @Test
    void shouldIgnoreRedeliveredPaymentEvent() {
        Long orderId = pendingOrder();

        PaymentEvent failed = payments.failed(orderId, "Недостаточно средств на карте");
        payments.redeliver(failed);

        assertEquals(1, testData.countOrderEvents(orderId, "ORDER_PAYMENT_FAILED"));
    }

    @Test
    void shouldRequestRefundWhenPaidOrderIsCanceled() {
        Long customerId = testData.insertCustomer();
        Long orderId = pendingOrder(customerId);
        payments.succeeded(orderId);

        orderService.cancelOrder(customerId, orderId);

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(1, testData.countPaymentCommands(orderId, "REFUND_REQUESTED"))
        );
    }

    /**
     * Отмена во время списания: возврат просится сразу, не дожидаясь исхода. Пришедшая потом
     * «оплата прошла» заказ из отмены не возвращает.
     */
    @Test
    void shouldKeepOrderCanceledWhenPaymentSucceedsAfterCancel() {
        Long customerId = testData.insertCustomer();
        Long orderId = pendingOrder(customerId);

        orderService.cancelOrder(customerId, orderId);
        payments.succeeded(orderId);

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(1, testData.countPaymentCommands(orderId, "REFUND_REQUESTED")),
                () -> assertEquals(0, testData.countOrderEvents(orderId, "ORDER_PAID"))
        );
    }

    @Test
    void shouldNotRequestRefundWhenUnpaidOrderIsCanceled() {
        Long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        orderService.cancelOrder(customerId, order.orderId());

        assertEquals(0, testData.countPaymentCommands(order.orderId(), "REFUND_REQUESTED"));
    }

    @Test
    void shouldRecordRefundInOrderHistory() {
        Long customerId = testData.insertCustomer();
        Long orderId = pendingOrder(customerId);
        payments.succeeded(orderId);
        orderService.cancelOrder(customerId, orderId);

        payments.refunded(orderId);

        assertEquals(1, testData.countOrderEvents(orderId, "ORDER_REFUNDED"));
    }

    /** Таймаут в PAYMENT_PENDING: заказ отменяется, и на всякий случай просится возврат. */
    @Test
    void shouldCancelAndRefundOrderThatTimedOutWhilePaymentPending() {
        Long orderId = pendingOrder();
        OrderAccess access = orderStatusService.requireAccess(orderId);

        paymentSaga.expire(access, Duration.ofMinutes(15));

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(orderId)),
                () -> assertEquals(1, testData.countPaymentCommands(orderId, "REFUND_REQUESTED"))
        );
    }

    @Test
    void shouldCancelUnpaidOrderOnTimeoutWithoutRefund() {
        OrderContext order = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.CREATED);
        OrderAccess access = orderStatusService.requireAccess(order.orderId());

        paymentSaga.expire(access, Duration.ofMinutes(15));

        assertAll(
                () -> assertEquals(OrderStatus.CANCELED.getDbValue(), testData.currentOrderStatus(order.orderId())),
                () -> assertEquals(0, testData.countPaymentCommands(order.orderId(), "REFUND_REQUESTED"))
        );
    }

    /** Таймаут проиграл гонку: заказ успели оплатить — отменять нечего. */
    @Test
    void shouldNotExpireOrderThatWasPaidMeanwhile() {
        Long orderId = pendingOrder();
        OrderAccess staleAccess = orderStatusService.requireAccess(orderId);
        payments.succeeded(orderId);

        paymentSaga.expire(staleAccess, Duration.ofMinutes(15));

        assertEquals(OrderStatus.PAID.getDbValue(), testData.currentOrderStatus(orderId));
    }

    private Long pendingOrder() {
        return pendingOrder(testData.insertCustomer());
    }

    private Long pendingOrder(Long customerId) {
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        paymentSaga.requestPayment(customerId, order.orderId(), card());

        return order.orderId();
    }

    private static PayOrderRequest card() {
        return new PayOrderRequest(PaymentMethod.CARD, UUID.randomUUID().toString());
    }

    private String commandField(Long orderId, String commandType, String field) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT CAST(payload AS JSONB) ->> ?
                        FROM order_event_outbox
                        WHERE aggregate_id = ? AND topic = 'food-delivery.payment-commands' AND event_type = ?
                        """,
                String.class,
                field,
                orderId,
                commandType
        );
    }
}
