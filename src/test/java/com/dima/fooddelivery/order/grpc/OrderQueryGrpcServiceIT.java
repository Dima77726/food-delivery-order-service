package com.dima.fooddelivery.order.grpc;

import com.dima.fooddelivery.grpc.order.v1.GetDeliveryRequest;
import com.dima.fooddelivery.grpc.order.v1.GetOrderRequest;
import com.dima.fooddelivery.grpc.order.v1.ListOrdersAfterRequest;
import com.dima.fooddelivery.grpc.order.v1.OrderQueryServiceGrpc;
import com.dima.fooddelivery.grpc.order.v1.OrderView;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Чтение заказов по gRPC — так, как его видит монолит.
 *
 * <p>Без {@code @Transactional}: gRPC-сервер отвечает из своих потоков и видит только
 * зафиксированные данные.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderQueryGrpcServiceIT extends AbstractIntegrationTest {

    @LocalGrpcPort
    private int grpcPort;

    private ManagedChannel channel;

    @AfterEach
    void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void shouldReturnOrderWithItemsAndDelivery() {
        Long customerId = testData.insertCustomer();
        Long courierId = testData.insertCourier();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.IN_DELIVERY);
        Long deliveryId = testData.insertDelivery(order.orderId(), courierId, DeliveryStatus.PICKED_UP);

        OrderView view = orders().getOrder(GetOrderRequest.newBuilder().setOrderId(order.orderId()).build());

        assertAll(
                () -> assertEquals(customerId, view.getCustomerId()),
                () -> assertEquals("IN_DELIVERY", view.getStatus()),
                () -> assertEquals("450.00", view.getTotalAmount()),
                () -> assertEquals(1, view.getItemsCount()),
                () -> assertEquals(order.menuItemId(), view.getItems(0).getMenuItemId()),
                () -> assertTrue(view.hasDelivery()),
                () -> assertEquals(deliveryId, view.getDelivery().getId()),
                () -> assertEquals(courierId, view.getDelivery().getCourierId()),
                () -> assertEquals("PICKED_UP", view.getDelivery().getStatus())
        );
    }

    @Test
    void shouldLeaveDeliveryEmptyUntilItIsCreated() {
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.PAID);

        OrderView view = orders().getOrder(GetOrderRequest.newBuilder().setOrderId(order.orderId()).build());

        assertFalse(view.hasDelivery());
    }

    @Test
    void shouldListOrdersAfterCursorInAscendingOrder() {
        Long customerId = testData.insertCustomer();
        Long first = testData.insertOrderInStatus(customerId, OrderStatus.DELIVERED).orderId();
        Long second = testData.insertOrderInStatus(customerId, OrderStatus.CANCELED).orderId();

        var page = orders().listOrdersAfter(ListOrdersAfterRequest.newBuilder()
                .setAfterOrderId(first - 1)
                .setLimit(2)
                .build());

        assertAll(
                () -> assertEquals(first, page.getOrders(0).getId()),
                () -> assertEquals(second, page.getOrders(1).getId()),
                // Отменённые тоже отдаются: решать, нужны ли они, — потребителю.
                () -> assertEquals("CANCELED", page.getOrders(1).getStatus())
        );
    }

    @Test
    void shouldAnswerNotFoundForUnknownOrderAndDelivery() {
        StatusRuntimeException order = assertThrows(
                StatusRuntimeException.class,
                () -> orders().getOrder(GetOrderRequest.newBuilder().setOrderId(Long.MAX_VALUE).build())
        );
        StatusRuntimeException delivery = assertThrows(
                StatusRuntimeException.class,
                () -> orders().getDelivery(GetDeliveryRequest.newBuilder().setDeliveryId(Long.MAX_VALUE).build())
        );

        assertAll(
                () -> assertEquals(Status.Code.NOT_FOUND, order.getStatus().getCode()),
                () -> assertEquals(Status.Code.NOT_FOUND, delivery.getStatus().getCode())
        );
    }

    private OrderQueryServiceGrpc.OrderQueryServiceBlockingStub orders() {
        if (channel == null) {
            channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
        }

        return OrderQueryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }
}
