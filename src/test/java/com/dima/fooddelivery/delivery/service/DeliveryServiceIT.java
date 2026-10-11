package com.dima.fooddelivery.delivery.service;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Тесты доставки на настоящей базе.
 *
 * <p>Именно этот класс поймал бы исходный баг: в {@code findDeliveryCompletion} был запрос
 * {@code FROM delivery} без схемы, и {@code deliverDelivery} падал с 500 на любом вызове.
 * Юнит-тест с моком {@code JdbcTemplate} такое пропускает — мок не проверяет текст SQL.
 */
class DeliveryServiceIT extends AbstractIntegrationTest {

    @Autowired
    private DeliveryService deliveryService;

    @Test
    void shouldCompleteDeliveryAndMoveOrderToDelivered() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.IN_DELIVERY);
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, DeliveryStatus.PICKED_UP);

        DeliveryResponse response = deliveryService.deliverDelivery(courierId, deliveryId);

        assertAll(
                () -> assertEquals(DeliveryStatus.DELIVERED.getDbValue(), response.status()),
                () -> assertNotNull(response.deliveredAt(), "delivered_at должен проставиться"),
                () -> assertEquals(
                        OrderStatus.DELIVERED.getDbValue(),
                        testData.currentOrderStatus(order.orderId()),
                        "заказ обязан уйти в DELIVERED вместе с доставкой"
                ),
                () -> assertEquals(
                        1,
                        testData.countOrderEvents(order.orderId(), OrderEventType.ORDER_DELIVERED.getDbValue()),
                        "в историю заказа должно попасть ровно одно событие о доставке"
                )
        );
    }

    @Test
    void shouldMoveOrderToInDeliveryWhenCourierPicksUp() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order =
                testData.insertOrderInStatus(customerId, OrderStatus.READY_FOR_DELIVERY);
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, DeliveryStatus.ASSIGNED);

        DeliveryResponse response = deliveryService.pickUpDelivery(courierId, deliveryId);

        assertAll(
                () -> assertEquals(DeliveryStatus.PICKED_UP.getDbValue(), response.status()),
                () -> assertNotNull(response.pickedUpAt()),
                () -> assertEquals(OrderStatus.IN_DELIVERY.getDbValue(), testData.currentOrderStatus(order.orderId()))
        );
    }

    @Test
    void shouldRejectDeliveryOfUnknownDelivery() {
        Long courierId = testData.insertCourier();

        assertThrows(
                ResourceNotFoundException.class,
                () -> deliveryService.deliverDelivery(courierId, 999_999L)
        );
    }

    @Test
    void shouldRejectDeliveryByForeignCourier() {
        Long customerId = testData.insertCustomer();
        Long assignedCourierId = testData.insertCourier();
        Long foreignCourierId = testData.insertCourier();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.IN_DELIVERY);
        Long deliveryId = testData.insertDelivery(order.orderId(), assignedCourierId, DeliveryStatus.PICKED_UP);

        // Чужая доставка — это 403, а не 409: раньше обе ситуации давали один и тот же ответ,
        // потому что проверка курьера стояла в WHERE у UPDATE.
        assertThrows(
                AccessDeniedForResourceException.class,
                () -> deliveryService.deliverDelivery(foreignCourierId, deliveryId)
        );

        assertEquals(
                DeliveryStatus.PICKED_UP.getDbValue(),
                testData.currentDeliveryStatus(deliveryId),
                "неудачная попытка не должна ничего менять"
        );
    }

    @Test
    void shouldRejectDeliveryFromWrongStatus() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();

        TestDataFactory.OrderContext order =
                testData.insertOrderInStatus(customerId, OrderStatus.READY_FOR_DELIVERY);
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, DeliveryStatus.ASSIGNED);

        assertThrows(
                BusinessRuleViolationException.class,
                () -> deliveryService.deliverDelivery(courierId, deliveryId)
        );

        assertEquals(OrderStatus.READY_FOR_DELIVERY.getDbValue(), testData.currentOrderStatus(order.orderId()));
    }

    @Test
    void shouldRejectSecondDeliveryForSameOrder() {
        Long customerId = testData.insertCustomer();

        TestDataFactory.OrderContext order =
                testData.insertOrderInStatus(customerId, OrderStatus.READY_FOR_DELIVERY);

        deliveryService.createDeliveryForOrder(order.orderId());

        // uq_delivery_order: у заказа не может быть двух доставок. Проверяем, что нарушение
        // констрейнта превращается в понятную 409, а не в 500 из глубины JDBC.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> deliveryService.createDeliveryForOrder(order.orderId())
        );
    }

    @Test
    void shouldRejectDeliveryCreationForOrderThatIsNotReady() {
        Long customerId = testData.insertCustomer();

        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.COOKING);

        assertThrows(
                BusinessRuleViolationException.class,
                () -> deliveryService.createDeliveryForOrder(order.orderId())
        );
    }
}
