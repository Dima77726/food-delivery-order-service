package com.dima.fooddelivery.live;

import com.dima.fooddelivery.delivery.service.DeliveryService;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory.OrderContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Живые обновления целиком: HTTP-запрос, {@code pg_notify} в транзакции, слушатель базы, кадр
 * в потоке.
 *
 * <p>Без транзакции теста ({@code NOT_SUPPORTED}): {@code NOTIFY} доставляется только после
 * коммита, а откатываемая транзакция базового класса не закоммитила бы ни одного изменения.
 * Данные остаются в базе — у каждого теста свои заказы и пользователи, мешать они не будут.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiveUpdatesIT extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private OrderStreams orderStreams;

    @Test
    void shouldReplayHistoryThenPushStatusChangeToCustomerStream() throws Exception {
        long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        orderStatusService.recordEvent(order.orderId(), OrderEventType.ORDER_PAID, "Оплачен");

        MvcResult stream = openOrderStream(customerId, order.orderId(), null);

        awaitContent(stream, "event:order-event");
        awaitContent(stream, "\"status\":\"PAID\"");

        orderStatusService.transition(order.orderId(), OrderStatus.PAID, OrderStatus.ACCEPTED,
                OrderEventType.ORDER_ACCEPTED, "Ресторан принял заказ");

        awaitContent(stream, "Ресторан принял заказ");
        awaitContent(stream, "\"status\":\"ACCEPTED\"");
    }

    @Test
    void shouldContinueFromLastEventIdWithoutRepeatingSeenEvents() throws Exception {
        long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        orderStatusService.recordEvent(order.orderId(), OrderEventType.ORDER_PAID, "Первое событие");
        orderStatusService.recordEvent(order.orderId(), OrderEventType.ORDER_PAID, "Второе событие");

        MvcResult first = openOrderStream(customerId, order.orderId(), null);
        awaitContent(first, "Второе событие");
        long firstEventId = Long.parseLong(first.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .lines().filter(line -> line.startsWith("id:")).findFirst().orElseThrow().substring(3).trim());

        MvcResult resumed = openOrderStream(customerId, order.orderId(), firstEventId);
        awaitContent(resumed, "Второе событие");

        assertFalse(resumed.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("Первое событие"),
                "событие, которое клиент уже видел, не должно прийти повторно");
    }

    @Test
    void shouldEndStreamWhenOrderReachesFinalStatus() throws Exception {
        long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        MvcResult stream = openOrderStream(customerId, order.orderId(), null);
        awaitContent(stream, "\"status\":\"CREATED\"");

        orderStatusService.transition(order.orderId(), OrderStatus.CREATED, OrderStatus.CANCELED,
                OrderEventType.ORDER_CANCELED, "Клиент отменил заказ");

        awaitContent(stream, "event:end");
        awaitTrue(() -> orderStreams.openStreams(order.orderId()) == 0, "поток должен быть забыт");
    }

    @Test
    void shouldHideForeignOrderStream() throws Exception {
        long customerId = testData.insertCustomer();
        long strangerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);

        mockMvc.perform(get("/api/v1/customers/{c}/orders/{o}/stream", strangerId, order.orderId())
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor(strangerId, "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPushStatusChangeToRestaurantBoard() throws Exception {
        long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        long ownerId = testData.insertUser("owner", "RESTAURANT_OWNER");
        testData.assignRestaurantManager(ownerId, order.restaurantId());

        MvcResult board = mockMvc.perform(get("/api/v1/restaurants/{r}/orders/stream", order.restaurantId())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor(ownerId, "RESTAURANT_OWNER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        awaitContent(board, "event:ready");

        orderStatusService.transition(order.orderId(), OrderStatus.PAID, OrderStatus.ACCEPTED,
                OrderEventType.ORDER_ACCEPTED, "Ресторан принял заказ");

        awaitContent(board, "{\"orderId\":" + order.orderId() + ",\"status\":\"ACCEPTED\"}");
    }

    @Test
    void shouldAnswerWaitingCourierAsSoonAsDeliveryAppears() throws Exception {
        long courierId = testData.insertCourier();
        String token = "Bearer " + jwt.tokenFor(courierId, "COURIER");

        String etag = asyncResponse(mockMvc.perform(get("/api/v1/deliveries/available/poll")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andReturn())
                .getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag, "первый ответ обязан нести версию списка");

        MvcResult waiting = mockMvc.perform(get("/api/v1/deliveries/available/poll")
                        .param("timeoutSeconds", "20")
                        .header(HttpHeaders.IF_NONE_MATCH, etag)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(request().asyncStarted())
                .andReturn();

        long customerId = testData.insertCustomer();
        OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.READY_FOR_DELIVERY);
        Instant created = Instant.now();
        deliveryService.createDeliveryForOrder(order.orderId());

        MvcResult answered = asyncResponse(waiting);

        assertEquals(200, answered.getResponse().getStatus());
        assertNotEquals(etag, answered.getResponse().getHeader(HttpHeaders.ETAG));
        assertTrue(answered.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"orderId\":" + order.orderId()));
        assertTrue(Duration.between(created, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0,
                "ответ должен прийти по сигналу, а не по таймауту");
    }

    @Test
    void shouldAnswerNotModifiedWhenNothingChangesBeforeTimeout() throws Exception {
        long courierId = testData.insertCourier();
        String token = "Bearer " + jwt.tokenFor(courierId, "COURIER");

        String etag = asyncResponse(mockMvc.perform(get("/api/v1/deliveries/available/poll")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andReturn())
                .getResponse().getHeader(HttpHeaders.ETAG);

        MvcResult waiting = mockMvc.perform(get("/api/v1/deliveries/available/poll")
                        .param("timeoutSeconds", "1")
                        .header(HttpHeaders.IF_NONE_MATCH, etag)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Таймаут DeferredResult в MockMvc сам не срабатывает: его запускает контейнер.
        // Здесь контейнера нет, поэтому событие таймаута отправляется вручную.
        ((MockAsyncContext) waiting.getRequest().getAsyncContext()).getListeners().forEach(listener -> {
            try {
                listener.onTimeout(null);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        mockMvc.perform(asyncDispatch(waiting))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag));
    }

    @Test
    void shouldRejectStreamForAnonymousClient() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants/{r}/orders/stream", 1).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult openOrderStream(long customerId, long orderId, Long lastEventId) throws Exception {
        var request = get("/api/v1/customers/{c}/orders/{o}/stream", customerId, orderId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor(customerId, "CUSTOMER"));
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }

        return mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
    }

    private MvcResult asyncResponse(MvcResult started) throws Exception {
        started.getAsyncResult(WAIT.toMillis());
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(jsonPath("$").isArray())
                .andReturn();
    }

    private static void awaitContent(MvcResult result, String expected) throws Exception {
        awaitTrue(() -> result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains(expected),
                "в потоке нет «" + expected + "»");
    }

    private static void awaitTrue(CheckedCondition condition, String message) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.test()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }
}
