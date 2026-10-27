package com.dima.fooddelivery.refund;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Частичные возвраты: сумма по ценам заказа, лимит по позициям, автоодобрение и ручное, команда в outbox. */
class RefundIT extends AbstractIntegrationTest {

    @Autowired
    private RefundService refundService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldAutoApproveSmallRefundAndPutPartialCommandIntoOutbox() {
        Delivered order = delivered(2, "150.00");

        RefundService.RefundRequest request = refundService.request(order.customerId(), order.orderId(),
                List.of(new RefundService.RequestedItem(order.itemId(), 1)), "Не привезли напиток");

        assertEquals("APPROVED", request.status());
        assertEquals(new BigDecimal("150.00"), request.amount());
        String payload = jdbc.queryForObject("""
                SELECT payload FROM order_event_outbox
                WHERE aggregate_id = ? AND topic = 'food-delivery.payment-commands' AND event_type = 'REFUND_REQUESTED'
                """, String.class, order.orderId());
        assertTrue(payload.contains("\"amount\":150.00"), payload);
        assertTrue(payload.contains("partial-refund:" + request.commandId()), payload);
    }

    @Test
    void shouldSendLargeRefundToSupportAndApproveOnlyOnce() {
        Delivered order = delivered(1, "900.00");

        RefundService.RefundRequest request = refundService.request(order.customerId(), order.orderId(),
                List.of(new RefundService.RequestedItem(order.itemId(), 1)), "Холодное");
        assertEquals("PENDING", request.status());
        assertEquals(0, testData.countPaymentCommands(order.orderId(), "REFUND_REQUESTED"));

        refundService.approve(request.id(), 1L);
        assertThrows(BusinessRuleViolationException.class, () -> refundService.approve(request.id(), 1L));
        assertEquals(1, testData.countPaymentCommands(order.orderId(), "REFUND_REQUESTED"));
    }

    @Test
    void shouldNotRefundMoreThanOrderedOrForUndeliveredOrder() {
        Delivered order = delivered(2, "100.00");
        refundService.request(order.customerId(), order.orderId(), List.of(new RefundService.RequestedItem(order.itemId(), 1)), "раз");
        refundService.request(order.customerId(), order.orderId(), List.of(new RefundService.RequestedItem(order.itemId(), 1)), "два");

        assertThrows(BusinessRuleViolationException.class, () -> refundService.request(order.customerId(), order.orderId(),
                List.of(new RefundService.RequestedItem(order.itemId(), 1)), "три — больше, чем было"));

        long customer = testData.insertCustomer();
        long paidOrder = testData.insertOrderInStatus(customer, OrderStatus.PAID).orderId();
        assertThrows(BusinessRuleViolationException.class, () -> refundService.request(customer, paidOrder, List.of(), "рано"));
    }

    private Delivered delivered(int quantity, String price) {
        long customer = testData.insertCustomer();
        long restaurant = testData.insertRestaurant();
        long menuItem = testData.insertMenuItem(restaurant, new BigDecimal(price));
        long cart = testData.insertCart(customer, restaurant, "CHECKED_OUT");
        BigDecimal total = new BigDecimal(price).multiply(BigDecimal.valueOf(quantity));
        long order = testData.insertOrder(cart, customer, restaurant, OrderStatus.DELIVERED, total);
        long item = testData.insertOrderItem(order, menuItem, quantity, new BigDecimal(price));
        long delivery = testData.insertDelivery(order, testData.insertCourier(), DeliveryStatus.DELIVERED);
        jdbc.update("UPDATE delivery SET delivered_at = CURRENT_TIMESTAMP WHERE id = ?", delivery);
        return new Delivered(customer, order, item);
    }

    private record Delivered(long customerId, long orderId, long itemId) {
    }
}
