package com.dima.fooddelivery.soap;

import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.TestDataFactory;
import com.dima.fooddelivery.support.TestJwt;
import com.dima.fooddelivery.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Партнёрский SOAP API заказов по настоящему HTTP: тот же путь, которым пойдёт касса ресторана.
 *
 * <p>Без транзакции теста — запрос обслуживает другой поток и незафиксированных данных не увидит.
 * Каждый тест заводит свой ресторан, поэтому данные не пересекаются.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.outbox.publish-enabled=false",
                "app.audit.publish-enabled=false",
                "app.saga.timeout-check-enabled=false",
                "logging.level.liquibase=WARN",
                "logging.level.org.testcontainers=WARN",
                "logging.level.com.zaxxer.hikari=WARN"
        })
@Import(TestcontainersConfiguration.class)
class OrderPartnerSoapIT {

    private static final String NS = "urn:fooddelivery:partner:v1";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataFactory testData;

    @Autowired
    private TestJwt jwt;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void shouldPublishWsdlAnonymouslyWithOnlyOrderOperations() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/ws/orders.wsdl")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String wsdl = response.body();

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(wsdl.contains("OrderPartnerService")),
                () -> assertTrue(wsdl.contains("ListOpenOrders") && wsdl.contains("GetOrderStatus")
                        && wsdl.contains("AcceptOrder")),
                () -> assertTrue(wsdl.contains("PartnerFault")),
                () -> assertFalse(wsdl.contains("SetItemAvailability"), "операции каталога принадлежат другому сервису"),
                () -> assertTrue(wsdl.contains("/ws/orders\""))
        );
    }

    @Test
    void shouldListOnlyOpenOrdersOfTheRestaurant() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext paid = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Manager manager = manager(paid.restaurantId());
        // В том же ресторане: доставленный заказ не «открыт», новый (до оплаты) ресторану ещё не виден.
        Long deliveredCart = testData.insertCart(customerId, paid.restaurantId(), "CHECKED_OUT");
        Long delivered = testData.insertOrder(deliveredCart, customerId, paid.restaurantId(), OrderStatus.DELIVERED, paid.price());
        Long createdCart = testData.insertCart(customerId, paid.restaurantId(), "CHECKED_OUT");
        Long created = testData.insertOrder(createdCart, customerId, paid.restaurantId(), OrderStatus.CREATED, paid.price());
        // Чужой ресторан: его открытый заказ не должен попасть в ответ.
        TestDataFactory.OrderContext foreign = testData.insertOrderInStatus(customerId, OrderStatus.PAID);

        HttpResponse<String> response = soap(manager.token, "<ListOpenOrdersRequest xmlns=\"" + NS + "\"><restaurantId>"
                + paid.restaurantId() + "</restaurantId></ListOpenOrdersRequest>");

        String body = response.body();

        assertAll(
                () -> assertEquals(200, response.statusCode(), body),
                () -> assertTrue(containsOrderId(body, paid.orderId()), body),
                () -> assertFalse(containsOrderId(body, delivered), "доставленный не открыт"),
                () -> assertFalse(containsOrderId(body, created), "неоплаченный ресторану не виден"),
                () -> assertFalse(containsOrderId(body, foreign.orderId()), "чужой ресторан")
        );
    }

    @Test
    void shouldReturnOrderStatus() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.COOKING);
        Manager manager = manager(order.restaurantId());

        HttpResponse<String> response = soap(manager.token, "<GetOrderStatusRequest xmlns=\"" + NS + "\"><restaurantId>"
                + order.restaurantId() + "</restaurantId><orderId>" + order.orderId() + "</orderId></GetOrderStatusRequest>");

        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("COOKING"), response.body()),
                () -> assertTrue(response.body().contains("450.00"), response.body())
        );
    }

    @Test
    void shouldAcceptPaidOrder() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Manager manager = manager(order.restaurantId());

        HttpResponse<String> response = soap(manager.token, "<AcceptOrderRequest xmlns=\"" + NS + "\"><restaurantId>"
                + order.restaurantId() + "</restaurantId><orderId>" + order.orderId() + "</orderId></AcceptOrderRequest>");

        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("ACCEPTED"), response.body()),
                () -> assertEquals("ACCEPTED", testData.currentOrderStatus(order.orderId()), "переход дошёл до базы")
        );
    }

    @Test
    void shouldAnswerConflictFaultWhenOrderIsNotPaid() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        Manager manager = manager(order.restaurantId());

        HttpResponse<String> response = soap(manager.token, "<AcceptOrderRequest xmlns=\"" + NS + "\"><restaurantId>"
                + order.restaurantId() + "</restaurantId><orderId>" + order.orderId() + "</orderId></AcceptOrderRequest>");

        assertFault(response, "CONFLICT", "Client");
        assertEquals("CREATED", testData.currentOrderStatus(order.orderId()));
    }

    @Test
    void shouldAnswerForbiddenFaultForOutsider() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        String outsider = "Bearer " + jwt.tokenFor(jwt.newUserId(), "RESTAURANT_OWNER");

        HttpResponse<String> response = soap(outsider, "<AcceptOrderRequest xmlns=\"" + NS + "\"><restaurantId>"
                + order.restaurantId() + "</restaurantId><orderId>" + order.orderId() + "</orderId></AcceptOrderRequest>");

        assertFault(response, "FORBIDDEN", "Client");
        assertEquals("PAID", testData.currentOrderStatus(order.orderId()), "чужой не должен менять заказ");
    }

    /** Свой ресторан, но чужой заказ: идентификатор заказа не даёт обойти границу ресторана. */
    @Test
    void shouldNotExposeOrderOfAnotherRestaurant() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext mine = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        TestDataFactory.OrderContext other = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Manager manager = manager(mine.restaurantId());

        HttpResponse<String> response = soap(manager.token, "<GetOrderStatusRequest xmlns=\"" + NS + "\"><restaurantId>"
                + mine.restaurantId() + "</restaurantId><orderId>" + other.orderId() + "</orderId></GetOrderStatusRequest>");

        assertFault(response, "FORBIDDEN", "Client");
    }

    @Test
    void shouldAnswerNotFoundFaultForUnknownOrder() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Manager manager = manager(order.restaurantId());

        HttpResponse<String> response = soap(manager.token, "<GetOrderStatusRequest xmlns=\"" + NS + "\"><restaurantId>"
                + order.restaurantId() + "</restaurantId><orderId>999999999</orderId></GetOrderStatusRequest>");

        assertFault(response, "NOT_FOUND", "Client");
    }

    @Test
    void shouldRequireToken() throws Exception {
        HttpResponse<String> response = soap(null,
                "<ListOpenOrdersRequest xmlns=\"" + NS + "\"><restaurantId>1</restaurantId></ListOpenOrdersRequest>");

        assertEquals(401, response.statusCode());
    }

    // --- вспомогательное -----------------------------------------------------------------------

    private boolean containsOrderId(String body, Long orderId) {
        return body.contains(">" + orderId + "<");
    }

    private void assertFault(HttpResponse<String> response, String partnerCode, String faultCode) {
        assertAll(
                () -> assertEquals(500, response.statusCode(), "SOAP 1.1: Fault приходит с HTTP 500"),
                () -> assertTrue(response.body().contains(faultCode), response.body()),
                () -> assertTrue(response.body().contains("<ns2:code>" + partnerCode + "</ns2:code>")
                        || response.body().contains("<code>" + partnerCode + "</code>"), response.body())
        );
    }

    private HttpResponse<String> soap(String authorization, String payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/ws/orders"))
                .header("Content-Type", "text/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<soapenv:Body>" + payload + "</soapenv:Body></soapenv:Envelope>"));

        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private Manager manager(Long restaurantId) {
        long userId = jwt.newUserId();
        testData.assignRestaurantManager(userId, restaurantId);

        return new Manager("Bearer " + jwt.tokenFor(userId, "RESTAURANT_OWNER"));
    }

    private record Manager(String token) {
    }
}
