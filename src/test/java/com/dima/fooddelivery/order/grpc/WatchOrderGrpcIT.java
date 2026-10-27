package com.dima.fooddelivery.order.grpc;

import com.dima.fooddelivery.grpc.order.v1.OrderEventView;
import com.dima.fooddelivery.grpc.order.v1.OrderQueryServiceGrpc;
import com.dima.fooddelivery.grpc.order.v1.WatchOrderRequest;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Server streaming {@code WatchOrder}: история, живые события по сигналу PostgreSQL и закрытие
 * потока сервером на финальном статусе. Без транзакции теста — NOTIFY приходит только после коммита.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WatchOrderGrpcIT extends AbstractIntegrationTest {

    @LocalGrpcPort
    private int grpcPort;

    @Autowired
    private OrderStatusService orderStatusService;

    private ManagedChannel channel;

    @AfterEach
    void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    void shouldStreamHistoryThenLiveEventsAndCompleteOnFinalStatus() throws Exception {
        long orderId = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.PAID).orderId();
        orderStatusService.recordEvent(orderId, OrderEventType.ORDER_PAID, "Оплачен");

        List<OrderEventView> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        asyncStub().watchOrder(WatchOrderRequest.newBuilder().setOrderId(orderId).build(), new StreamObserver<>() {
            @Override public void onNext(OrderEventView value) { received.add(value); }
            @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
            @Override public void onCompleted() { completed.complete(null); }
        });

        awaitSize(received, 1);
        orderStatusService.transition(orderId, OrderStatus.PAID, OrderStatus.ACCEPTED, OrderEventType.ORDER_ACCEPTED, "Принят");
        awaitSize(received, 2);
        orderStatusService.transition(orderId, OrderStatus.ACCEPTED, OrderStatus.CANCELED, OrderEventType.ORDER_CANCELED, "Отменён");

        completed.get(10, TimeUnit.SECONDS);
        assertEquals(List.of("ORDER_PAID", "ORDER_ACCEPTED", "ORDER_CANCELED"), received.stream().map(OrderEventView::getEventType).toList());
        assertEquals("CANCELED", received.getLast().getStatus());
    }

    @Test
    void shouldResumeAfterGivenEventId() {
        long orderId = testData.insertOrderInStatus(testData.insertCustomer(), OrderStatus.CANCELED).orderId();
        orderStatusService.recordEvent(orderId, OrderEventType.ORDER_PAID, "первое");
        orderStatusService.recordEvent(orderId, OrderEventType.ORDER_CANCELED, "второе");

        Iterator<OrderEventView> all = blockingStub().watchOrder(WatchOrderRequest.newBuilder().setOrderId(orderId).build());
        long firstId = all.next().getEventId();

        Iterator<OrderEventView> resumed = blockingStub().watchOrder(
                WatchOrderRequest.newBuilder().setOrderId(orderId).setAfterEventId(firstId).build());
        assertEquals("второе", resumed.next().getDescription());
    }

    @Test
    void shouldAnswerNotFoundForUnknownOrder() {
        Iterator<OrderEventView> stream = blockingStub().watchOrder(WatchOrderRequest.newBuilder().setOrderId(Long.MAX_VALUE).build());

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, stream::hasNext);
        assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
    }

    private ManagedChannel channel() {
        if (channel == null) {
            channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
        }
        return channel;
    }

    private OrderQueryServiceGrpc.OrderQueryServiceStub asyncStub() {
        return OrderQueryServiceGrpc.newStub(channel()).withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private OrderQueryServiceGrpc.OrderQueryServiceBlockingStub blockingStub() {
        return OrderQueryServiceGrpc.newBlockingStub(channel()).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(size, list.size(), "не пришло ожидаемое число событий");
    }
}
