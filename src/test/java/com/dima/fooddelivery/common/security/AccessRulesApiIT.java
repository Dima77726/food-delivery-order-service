package com.dima.fooddelivery.common.security;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правила доступа сервиса заказов через HTTP — с токенами в форме identity-service.
 *
 * <p>Раньше эти проверки жили в {@code AuthApiIT} и получали токен регистрацией. Регистрация
 * уехала в сервис пользователей, а правила доступа к заказам остались здесь, и проверять их
 * нужно здесь же: {@code @PreAuthorize} висит на контроллерах сервиса.
 */
@AutoConfigureMockMvc
class AccessRulesApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRejectUnauthenticatedAccessToProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowAnonymousAccessToPublicEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowReadingOwnOrders() throws Exception {
        long customerId = testData.insertCustomer();

        mockMvc.perform(get("/api/v1/customers/{id}/orders", customerId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(customerId, "CUSTOMER")))
                .andExpect(status().isOk());
    }

    /**
     * Роль CUSTOMER у пользователя есть, но идентификатор в пути чужой. Без сверки с токеном
     * это была бы дыра, через которую видны чужие заказы.
     */
    @Test
    void shouldForbidReadingForeignOrders() throws Exception {
        long customerId = testData.insertCustomer();
        long foreignCustomerId = testData.insertCustomer();

        mockMvc.perform(get("/api/v1/customers/{id}/orders", foreignCustomerId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(customerId, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldForbidCustomerFromReachingAdminApi() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit")
                        .header("Authorization", "Bearer " + jwt.newUserToken("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    /**
     * Заказы ресторана видит только его управляющий. Ответ на вопрос «управляет ли» сервис
     * получает у сервиса пользователей — здесь вместо него тестовые права в памяти.
     */
    @Test
    void shouldLetOnlyManagerReadRestaurantOrders() throws Exception {
        long ownerId = testData.insertUser("owner", "RESTAURANT_OWNER");
        long strangerId = testData.insertUser("stranger", "RESTAURANT_OWNER");
        long restaurantId = testData.insertRestaurant();

        testData.assignRestaurantManager(ownerId, restaurantId);

        mockMvc.perform(get("/api/v1/restaurants/{id}/orders", restaurantId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(ownerId, "RESTAURANT_OWNER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/restaurants/{id}/orders", restaurantId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(strangerId, "RESTAURANT_OWNER")))
                .andExpect(status().isForbidden());
    }

    /** Токен, подписанный правильным ключом, но выпущенный другой системой, не принимается. */
    @Test
    void shouldRejectTokenFromForeignIssuer() throws Exception {
        long customerId = testData.insertCustomer();

        mockMvc.perform(get("/api/v1/customers/{id}/orders", customerId)
                        .header("Authorization", "Bearer " + jwt.tokenWithIssuer(customerId, "someone-else", "CUSTOMER")))
                .andExpect(status().isUnauthorized());
    }
}
