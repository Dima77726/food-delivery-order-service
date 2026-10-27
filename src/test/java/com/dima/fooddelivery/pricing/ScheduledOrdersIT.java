package com.dima.fooddelivery.pricing;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory.OrderContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Заказ ко времени: готовить — только в окне перед сроком, напоминание ресторану — ровно одно. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduledOrdersIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ScheduledOrders scheduledOrders;

    @Autowired
    private OrderTermsRepository terms;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldNotStartCookingBeforeWindowButAllowInsideIt() {
        OrderContext order = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.ACCEPTED);
        schedule(order, OffsetDateTime.now().plusHours(3));

        assertThrows(BusinessRuleViolationException.class, () -> orderService.startCookingOrder(order.restaurantId(), order.orderId()));

        jdbc.update("UPDATE order_terms SET scheduled_for = ? WHERE order_id = ?", OffsetDateTime.now().plusMinutes(40), order.orderId());
        assertEquals("COOKING", orderService.startCookingOrder(order.restaurantId(), order.orderId()).status());
    }

    @Test
    void shouldRemindRestaurantOnceWhenScheduledTimeApproaches() {
        OrderContext soon = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.PAID);
        OrderContext later = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.PAID);
        schedule(soon, OffsetDateTime.now().plusMinutes(50));
        schedule(later, OffsetDateTime.now().plusHours(5));

        scheduledOrders.remindRestaurants();
        scheduledOrders.remindRestaurants();

        assertEquals(1, reminders(soon.orderId()), "напоминание — ровно одно, даже если задача прошла дважды");
        assertEquals(0, reminders(later.orderId()));
    }

    private void schedule(OrderContext order, OffsetDateTime at) {
        terms.insert(new OrderTermsRepository.OrderTerms(order.orderId(), order.price(), null, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ZERO, at));
    }

    private int reminders(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customer_order_event WHERE order_id = ? AND description LIKE 'Пора готовить%'",
                Integer.class, orderId);
    }
}
