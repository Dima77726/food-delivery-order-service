package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Курсорная лента заказов через HTTP.
 *
 * <p>Сервисные тесты сюда не достают по двум причинам. Во-первых, {@code /orders/feed} —
 * это буквальный сегмент рядом с шаблоном {@code /orders/{orderId}}: если приоритет
 * литерального пути когда-нибудь перестанет работать, «feed» уедет в разбор идентификатора
 * заказа, и увидеть это можно только на настоящей маршрутизации. Во-вторых, битый курсор
 * обязан превратиться в 400, а не в 500, — а превращает его обработчик исключений,
 * который при прямом вызове сервиса не участвует.
 */
@AutoConfigureMockMvc
class OrderFeedApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRouteFeedPathToCursorEndpointAndNotToOrderById() throws Exception {
        Customer customer = registerCustomer();
        insertOrders(customer.id(), 3);

        mockMvc.perform(get("/api/v1/customers/{id}/orders/feed", customer.id())
                        .param("size", "2")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").isString())
                // Постраничных полей в курсорном ответе быть не должно.
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    void shouldWalkFeedToTheEnd() throws Exception {
        Customer customer = registerCustomer();
        insertOrders(customer.id(), 5);

        List<Integer> collected = new ArrayList<>();

        String cursor = null;
        do {
            String body = mockMvc.perform(get("/api/v1/customers/{id}/orders/feed", customer.id())
                            .param("size", "2")
                            .param("cursor", cursor == null ? "" : cursor)
                            .header("Authorization", "Bearer " + customer.token()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            collected.addAll(JsonPath.read(body, "$.content[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertAll(
                () -> assertEquals(5, collected.size()),
                () -> assertEquals(5, new HashSet<>(collected).size(), "заказы не должны повторяться")
        );
    }

    @Test
    void shouldAnswerBadRequestOnForgedCursor() throws Exception {
        Customer customer = registerCustomer();

        mockMvc.perform(get("/api/v1/customers/{id}/orders/feed", customer.id())
                        .param("cursor", "явно-не-курсор")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Потолок размера обязан быть общим с постраничной выборкой: иначе клиент обошёл бы
     * лимит, просто перейдя на соседний эндпоинт.
     */
    @Test
    void shouldRejectOversizedFeedRequest() throws Exception {
        Customer customer = registerCustomer();

        mockMvc.perform(get("/api/v1/customers/{id}/orders/feed", customer.id())
                        .param("size", "101")
                        .header("Authorization", "Bearer " + customer.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldNotLetCustomerReadForeignFeed() throws Exception {
        Customer customer = registerCustomer();
        Customer stranger = registerCustomer();

        insertOrders(customer.id(), 1);

        mockMvc.perform(get("/api/v1/customers/{id}/orders/feed", customer.id())
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isForbidden());
    }

    private void insertOrders(long customerId, int count) {
        for (int i = 0; i < count; i++) {
            testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        }
    }

    /** Клиент — строка с идентификатором и токен в форме identity-service для него. */
    private Customer registerCustomer() {
        long id = testData.insertCustomer();
        return new Customer(id, jwt.tokenFor(id, "CUSTOMER"));
    }

    private record Customer(long id, String token) {
    }
}
