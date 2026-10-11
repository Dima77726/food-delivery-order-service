package com.dima.fooddelivery.common.metrics;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет наблюдаемость целиком: метрики считаются, отдаются на служебном порту
 * и не отдаются на публичном.
 *
 * <p>Последнее — не придирка. Разделение портов существует ровно ради того, чтобы
 * {@code /actuator/prometheus} нельзя было открыть снаружи, и это утверждение обязано
 * проверяться, а не держаться на честном слове конфигурации.
 *
 * <p>{@code @Transactional} здесь нет: сервер работает в своём потоке, откатить его
 * транзакции из теста нельзя.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 0 — случайный свободный порт: фиксированный 8081 конфликтовал бы
                // с локально запущенным приложением и делал сборку зависимой от машины.
                "management.server.port=0",
                "logging.level.liquibase=WARN"
        }
)
@Import(TestcontainersConfiguration.class)
class MetricsEndpointIT {

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CartService cartService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private com.dima.fooddelivery.support.TestDataFactory testData;

    @Test
    void shouldExposePrometheusMetricsOnManagementPortOnly() {
        String metrics = RestClient.create()
                .get()
                .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .retrieve()
                .body(String.class);

        assertAll(
                () -> assertTrue(metrics.contains("jvm_memory_used_bytes"), "должны быть метрики JVM"),
                () -> assertTrue(metrics.contains("hikaricp_connections"), "должны быть метрики пула соединений"),
                () -> assertTrue(
                        metrics.contains("application=\"order-service\""),
                        "общий тег приложения должен проставляться на все метрики"
                )
        );
    }

    @Test
    void shouldNotExposeActuatorOnApplicationPort() {
        HttpClientErrorException error = assertThrows(
                HttpClientErrorException.class,
                () -> RestClient.create()
                        .get()
                        .uri("http://localhost:" + applicationPort + "/actuator/prometheus")
                        .retrieve()
                        .body(String.class)
        );

        // 401, а не 404: основная цепочка Security с anyRequest().authenticated()
        // перехватывает запрос раньше, чем диспетчер обнаружит отсутствие маршрута.
        //
        // Для нашей цели это даже лучше 404. Ответ «не аутентифицирован» одинаков
        // для существующих и несуществующих путей, поэтому перебором нельзя выяснить,
        // какие служебные эндпоинты у приложения есть. Важно другое и оно проверено:
        // метрик на прикладном порту не отдают.
        assertEquals(401, error.getStatusCode().value());
    }

    @Test
    void shouldReportHealthWithDatabaseCheck() {
        String health = RestClient.create()
                .get()
                .uri("http://localhost:" + managementPort + "/actuator/health")
                .retrieve()
                .body(String.class);

        assertTrue(health.contains("\"status\":\"UP\""), "с поднятой базой состояние должно быть UP");
    }

    /**
     * Технические метрики Micrometer даёт сам. Бизнес-счётчики — нет: то, что создание заказа
     * увеличивает именно {@code orders.created}, проверить можно только так.
     */
    @Test
    void shouldCountCreatedOrdersAsBusinessMetric() {
        double before = counterValue("food_delivery.orders.created");

        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long menuItemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.00"));

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(menuItemId, 2));
        orderService.createOrderFromActiveCart(customerId, restaurantId);

        assertAll(
                () -> assertEquals(before + 1, counterValue("food_delivery.orders.created")),
                () -> assertTrue(
                        meterRegistry.find("food_delivery.checkout.duration").timer().count() > 0,
                        "оформление заказа должно попадать в таймер"
                )
        );
    }

    private double counterValue(String name) {
        var counter = meterRegistry.find(name).counter();

        return counter == null ? 0d : counter.count();
    }
}
