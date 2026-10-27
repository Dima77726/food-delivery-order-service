package com.dima.fooddelivery.settlement;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.delivery.domain.DeliveryStatus;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Задание Spring Batch на настоящей базе: агрегат по ресторану, комиссия, границы суток в часовом
 * поясе, защита от повторного расчёта и пересчёт. Без транзакции теста: Batch сам управляет
 * транзакциями и не запускается внутри чужой.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementJobIT extends AbstractIntegrationTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Autowired
    private SettlementService service;

    @Autowired
    private SettlementRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAggregateDeliveredOrdersPerRestaurantWithinMoscowDay() {
        LocalDate day = uniqueDay();
        long restaurant = testData.insertRestaurant();
        delivered(restaurant, "1000.00", day.atTime(0, 30).atZone(MOSCOW).toOffsetDateTime());   // 21:30 UTC накануне — но день московский
        delivered(restaurant, "500.00", day.atTime(23, 50).atZone(MOSCOW).toOffsetDateTime());
        delivered(restaurant, "700.00", day.plusDays(1).atTime(0, 5).atZone(MOSCOW).toOffsetDateTime()); // уже завтра

        SettlementService.Result result = service.run(day, false);

        assertEquals(BatchStatus.COMPLETED, result.status());
        SettlementLine line = only(repository.findByDate(day), restaurant);
        assertEquals(2, line.ordersCount());
        assertEquals(new BigDecimal("1500.00"), line.revenue());
        assertEquals(new BigDecimal("225.00"), line.commission());
        assertEquals(new BigDecimal("1275.00"), line.payout());
    }

    @Test
    void shouldRefuseSecondRunOfSameDayButAllowExplicitRerun() {
        LocalDate day = uniqueDay();
        long restaurant = testData.insertRestaurant();
        delivered(restaurant, "100.00", day.atTime(12, 0).atZone(MOSCOW).toOffsetDateTime());
        service.run(day, false);

        assertThrows(BusinessRuleViolationException.class, () -> service.run(day, false));

        delivered(restaurant, "50.00", day.atTime(13, 0).atZone(MOSCOW).toOffsetDateTime());
        service.run(day, true);

        SettlementLine line = only(repository.findByDate(day), restaurant);
        assertEquals(2, line.ordersCount(), "пересчёт перезаписывает строку, а не добавляет вторую");
        assertEquals(new BigDecimal("150.00"), line.revenue());
    }

    /** Найдено на стенде: после пересчёта обычный запуск того же дня — другой экземпляр задания. */
    @Test
    void shouldRefusePlainRunAfterTheDayWasComputedByRerun() {
        LocalDate day = uniqueDay();
        delivered(testData.insertRestaurant(), "100.00", day.atTime(12, 0).atZone(MOSCOW).toOffsetDateTime());
        service.run(day, true);

        assertThrows(BusinessRuleViolationException.class, () -> service.run(day, false));
    }

    @Test
    void shouldServeOwnSettlementsAsJsonAndCsvAndLetOnlyAdminRunJob() throws Exception {
        LocalDate day = uniqueDay();
        long restaurant = testData.insertRestaurant();
        long owner = testData.insertUser("owner", "RESTAURANT_OWNER");
        testData.assignRestaurantManager(owner, restaurant);
        delivered(restaurant, "200.00", day.atTime(15, 0).atZone(MOSCOW).toOffsetDateTime());

        mockMvc.perform(post("/api/v1/admin/settlements/{d}", day).header("Authorization", "Bearer " + jwt.tokenFor(owner, "RESTAURANT_OWNER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/settlements/{d}", day).header("Authorization", "Bearer " + jwt.newUserToken("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        String bearer = "Bearer " + jwt.tokenFor(owner, "RESTAURANT_OWNER");
        mockMvc.perform(get("/api/v1/restaurants/{r}/settlements", restaurant).param("from", day.toString()).param("to", day.toString())
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payout").value(170.00));
        String csv = mockMvc.perform(get("/api/v1/restaurants/{r}/settlements.csv", restaurant)
                        .param("from", day.toString()).param("to", day.toString()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains(day + "," + restaurant + ",1,200.00,30.00,170.00"), csv);
    }

    /** Свой день на каждый тест: в общей базе расчёты разных тестов не пересекаются. */
    private static LocalDate uniqueDay() {
        return LocalDate.of(2000, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 3_000_000));
    }

    private void delivered(long restaurantId, String amount, java.time.OffsetDateTime deliveredAt) {
        long customer = testData.insertCustomer();
        long menuItem = testData.insertMenuItem(restaurantId, new BigDecimal(amount));
        long cart = testData.insertCart(customer, restaurantId, "CHECKED_OUT");
        long order = testData.insertOrder(cart, customer, restaurantId, OrderStatus.DELIVERED, new BigDecimal(amount));
        long delivery = testData.insertDelivery(order, testData.insertCourier(), DeliveryStatus.DELIVERED);
        jdbc.update("UPDATE delivery SET delivered_at = ? WHERE id = ?", deliveredAt, delivery);
        assertTrue(menuItem > 0);
    }

    private static SettlementLine only(List<SettlementLine> lines, long restaurantId) {
        return lines.stream().filter(line -> line.restaurantId() == restaurantId).findFirst()
                .orElseThrow(() -> new AssertionError("нет строки расчёта для ресторана " + restaurantId));
    }
}
