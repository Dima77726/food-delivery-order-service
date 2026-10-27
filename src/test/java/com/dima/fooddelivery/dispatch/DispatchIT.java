package com.dima.fooddelivery.dispatch;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory.OrderContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Автоназначение: ближайшему, после отказа — следующему, истёкшее — снова в оборот, принятое — назначение.
 * Каждый тест ставит доставку в свой город, чтобы курьеры соседних тестов не попадали в радиус 5 км.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DispatchIT extends AbstractIntegrationTest {

    @Autowired
    private DispatchService dispatch;

    @Autowired
    private CourierPresence presence;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldOfferToNearestThenToNextAfterDecline() {
        long delivery = waitingDelivery(59.9343, 30.3351); // Петербург
        long near = courier(59.9350, 30.3360);
        long far = courier(59.9500, 30.3600);

        dispatch.tick();
        DispatchService.Offer first = single(near);
        assertEquals(delivery, first.deliveryId());
        assertTrue(dispatch.offersFor(far).isEmpty(), "одно действующее предложение на доставку");

        dispatch.decline(near, first.offerId());
        dispatch.tick();

        assertEquals(delivery, single(far).deliveryId());
        dispatch.tick();
        assertTrue(dispatch.offersFor(near).isEmpty(), "отказавшемуся то же не предлагают");
    }

    @Test
    void shouldExpireUnansweredOfferAndPassItOn() {
        long delivery = waitingDelivery(56.8389, 60.6057); // Екатеринбург
        long slow = courier(56.8390, 60.6060);
        long other = courier(56.8450, 60.6100);

        dispatch.tick();
        DispatchService.Offer offer = single(slow);
        jdbc.update("UPDATE delivery_offer SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", offer.offerId());

        dispatch.tick();

        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM delivery_offer WHERE id = ?", String.class, offer.offerId()));
        assertEquals(delivery, single(other).deliveryId());
        assertThrows(BusinessRuleViolationException.class, () -> dispatch.accept(slow, offer.offerId()), "опоздавший не может принять");
    }

    @Test
    void shouldAssignOnAcceptAndSkipBusyAndOfflineCouriers() {
        long delivery = waitingDelivery(55.7963, 49.1088); // Казань
        long offline = courier(55.7964, 49.1089);
        presence.offline(offline);
        long courier = courier(55.7970, 49.1100);

        dispatch.tick();
        DispatchService.Offer offer = single(courier);

        assertEquals("ASSIGNED", dispatch.accept(courier, offer.offerId()).status());
        assertEquals(DeliveryStatus.ASSIGNED.getDbValue(), testData.currentDeliveryStatus(delivery));

        long next = waitingDelivery(55.7965, 49.1090);
        dispatch.tick();
        assertTrue(dispatch.offersFor(courier).isEmpty(), "курьеру с доставкой в работе новое не предлагают");
        assertTrue(dispatch.offersFor(offline).isEmpty(), "ушедшему с линии не предлагают");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_offer WHERE delivery_id = ?", Integer.class, next));
    }

    private long waitingDelivery(double latitude, double longitude) {
        OrderContext order = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.COOKING);
        jdbc.update("UPDATE customer_order SET delivery_address = 'ул. Тестовая, 1', delivery_latitude = ?, delivery_longitude = ? WHERE id = ?",
                latitude, longitude, order.orderId());
        return testData.insertDelivery(order.orderId(), null, DeliveryStatus.CREATED);
    }

    private long courier(double latitude, double longitude) {
        long courierId = testData.insertCourier();
        presence.online(courierId, latitude, longitude);
        return courierId;
    }

    private DispatchService.Offer single(long courierId) {
        List<DispatchService.Offer> offers = dispatch.offersFor(courierId);
        assertEquals(1, offers.size(), "предложения курьеру " + courierId);
        return offers.getFirst();
    }
}
