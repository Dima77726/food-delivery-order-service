package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Адрес доставки при оформлении заказа — через HTTP: разбор необязательного тела и проверка полей
 * работают только на настоящем контроллере.
 */
@AutoConfigureMockMvc
class OrderDeliveryAddressApiIT extends AbstractIntegrationTest {

    private static final String ORDERS = "/api/v1/customers/{customerId}/restaurants/{restaurantId}/orders";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CartService cartService;

    @Test
    void shouldStoreDeliveryAddressAndReturnItWithOrder() throws Exception {
        Checkout checkout = prepareCart();

        String body = mockMvc.perform(post(ORDERS, checkout.customerId(), checkout.restaurantId())
                        .header("Authorization", "Bearer " + checkout.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deliveryAddress": {
                                    "address": "  Москва, Тверская улица, 7  ",
                                    "details": "кв. 12, домофон 12К",
                                    "latitude": 55.7576543219,
                                    "longitude": 37.6134
                                }}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryAddress.address").value("Москва, Тверская улица, 7"))
                .andExpect(jsonPath("$.deliveryAddress.details").value("кв. 12, домофон 12К"))
                .andExpect(jsonPath("$.deliveryAddress.latitude").value(55.757654))
                .andReturn().getResponse().getContentAsString();

        Integer orderId = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/v1/customers/{c}/orders/{o}", checkout.customerId(), orderId)
                        .header("Authorization", "Bearer " + checkout.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryAddress.address").value("Москва, Тверская улица, 7"))
                .andExpect(jsonPath("$.deliveryAddress.longitude").value(37.6134));
    }

    /** Клиенты, написанные до появления адреса, присылают пустой POST — он обязан работать как раньше. */
    @Test
    void shouldCreateOrderWithoutBody() throws Exception {
        Checkout checkout = prepareCart();

        mockMvc.perform(post(ORDERS, checkout.customerId(), checkout.restaurantId())
                        .header("Authorization", "Bearer " + checkout.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryAddress").doesNotExist());
    }

    @Test
    void shouldRejectPointWithOnlyOneCoordinate() throws Exception {
        Checkout checkout = prepareCart();

        mockMvc.perform(post(ORDERS, checkout.customerId(), checkout.restaurantId())
                        .header("Authorization", "Bearer " + checkout.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deliveryAddress": {"address": "Москва", "latitude": 55.75}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectBlankAddress() throws Exception {
        Checkout checkout = prepareCart();

        mockMvc.perform(post(ORDERS, checkout.customerId(), checkout.restaurantId())
                        .header("Authorization", "Bearer " + checkout.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deliveryAddress": {"address": "   ", "latitude": 55.75, "longitude": 37.61}}
                                """))
                .andExpect(status().isBadRequest());
    }

    private Checkout prepareCart() {
        long customerId = testData.insertCustomer();
        long restaurantId = testData.insertRestaurant();
        long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("350.00"));
        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 1));

        return new Checkout(customerId, restaurantId, jwt.tokenFor(customerId, "CUSTOMER"));
    }

    private record Checkout(long customerId, long restaurantId, String token) {
    }
}
