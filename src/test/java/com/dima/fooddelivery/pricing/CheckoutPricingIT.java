package com.dima.fooddelivery.pricing;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.order.api.OrderResponse;
import com.dima.fooddelivery.order.domain.DeliveryAddress;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.promo.PromoRepository.PromoCode;
import com.dima.fooddelivery.promo.PromoService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Оформление с промокодом, ценой доставки, чаевыми и временем. Без транзакции теста: проверка
 * гонки за последний экземпляр промокода требует настоящих параллельных коммитов.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutPricingIT extends AbstractIntegrationTest {

    private static final DeliveryAddress ADDRESS = new DeliveryAddress("Тверская, 1", null, new BigDecimal("55.757"), new BigDecimal("37.615"));

    @Autowired
    private OrderService orderService;

    @Autowired
    private PromoService promoService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldComposeTotalFromItemsDiscountSurgeFeeAndTip() {
        String code = promo(PromoCode.Type.PERCENT, "10", 100);
        redis.opsForValue().set(SurgePricing.KEY, "1.5:6:4");
        Cart cart = cart("1000.00");

        OrderResponse order = orderService.createOrderFromActiveCart(cart.customerId(), cart.restaurantId(), ADDRESS,
                new CheckoutPricing.Options(code.toLowerCase(), new BigDecimal("100"), null));

        // 1000 − 10% + 149 × 1.5 (= 224 до рубля) + 100
        assertEquals(new BigDecimal("1224.00"), order.totalAmount());
        assertEquals(new BigDecimal("100.00"), order.terms().discount());
        assertEquals(new BigDecimal("224.00"), order.terms().deliveryFee());
        assertEquals(new BigDecimal("1.50"), order.terms().surgeMultiplier());
        assertEquals(code, order.terms().promoCode());
        redis.delete(SurgePricing.KEY);
    }

    @Test
    void shouldKeepOldTotalForOrderWithoutAddressOrOptions() {
        Cart cart = cart("450.00");

        OrderResponse order = orderService.createOrderFromActiveCart(cart.customerId(), cart.restaurantId());

        assertEquals(new BigDecimal("450.00"), order.totalAmount());
    }

    @Test
    void shouldRejectUnknownUsedAndForeignCodes() {
        String code = promo(PromoCode.Type.FIXED, "50", 100);
        Cart first = cart("300.00");
        orderService.createOrderFromActiveCart(first.customerId(), first.restaurantId(), ADDRESS, new CheckoutPricing.Options(code, null, null));

        long restaurant = testData.insertRestaurant();
        long menuItem = testData.insertMenuItem(restaurant, new BigDecimal("300.00"));
        testData.insertCartItem(testData.insertCart(first.customerId(), restaurant, "ACTIVE"), menuItem, 1, new BigDecimal("300.00"));

        assertThrows(BusinessRuleViolationException.class, () -> orderService.createOrderFromActiveCart(
                first.customerId(), restaurant, ADDRESS, new CheckoutPricing.Options(code, null, null)), "второй раз тем же клиентом");
        Cart other = cart("300.00");
        assertThrows(BusinessRuleViolationException.class, () -> orderService.createOrderFromActiveCart(
                other.customerId(), other.restaurantId(), ADDRESS, new CheckoutPricing.Options("NO-SUCH-CODE-" + other.customerId(), null, null)));
    }

    /** Последний экземпляр промокода: из восьми одновременных оформлений проходит ровно одно. */
    @Test
    void shouldSellLastPromoUseExactlyOnceUnderRace() throws Exception {
        String code = promo(PromoCode.Type.FIXED, "100", 1);
        List<Cart> carts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            carts.add(cart("500.00"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        for (Cart cart : carts) {
            results.add(pool.submit((Callable<Boolean>) () -> {
                try {
                    orderService.createOrderFromActiveCart(cart.customerId(), cart.restaurantId(), ADDRESS,
                            new CheckoutPricing.Options(code, null, null));
                    return true;
                } catch (BusinessRuleViolationException exception) {
                    return false;
                }
            }));
        }
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            succeeded += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertEquals(1, succeeded);
        assertEquals(1, promoService.list().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow().usedCount());
    }

    @Test
    void shouldValidateTipAndScheduledTime() {
        Cart cart = cart("500.00");

        assertThrows(BusinessRuleViolationException.class, () -> orderService.createOrderFromActiveCart(cart.customerId(), cart.restaurantId(),
                ADDRESS, new CheckoutPricing.Options(null, new BigDecimal("99999"), null)));
        assertThrows(BusinessRuleViolationException.class, () -> orderService.createOrderFromActiveCart(cart.customerId(), cart.restaurantId(),
                ADDRESS, new CheckoutPricing.Options(null, null, OffsetDateTime.now().plusMinutes(10))));

        OffsetDateTime at = OffsetDateTime.now().plusHours(3).withNano(0);
        OrderResponse order = orderService.createOrderFromActiveCart(cart.customerId(), cart.restaurantId(), ADDRESS,
                new CheckoutPricing.Options(null, null, at));
        assertEquals(at.toInstant(), order.terms().scheduledFor().toInstant());
    }

    @Test
    void shouldCheckPromoForCartAndQuoteDeliveryOverHttp() throws Exception {
        String code = promo(PromoCode.Type.PERCENT, "20", 10);
        Cart cart = cart("1000.00");
        String bearer = "Bearer " + jwt.tokenFor(cart.customerId(), "CUSTOMER");

        mockMvc.perform(post("/api/v1/customers/{c}/restaurants/{r}/promo-check", cart.customerId(), cart.restaurantId())
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discount").value(200.00));
        mockMvc.perform(get("/api/v1/delivery-fee").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseFee").value(149));
        mockMvc.perform(post("/api/v1/admin/promo-codes").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HACK\",\"type\":\"FIXED\",\"value\":1000,\"maxUses\":1}"))
                .andExpect(status().isForbidden());
    }

    private String promo(PromoCode.Type type, String value, int maxUses) {
        String code = "T" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        promoService.create(new PromoCode(code, type, new BigDecimal(value), null, BigDecimal.ZERO, null, maxUses, 0, null, null, true));
        return code;
    }

    private Cart cart(String price) {
        long customer = testData.insertCustomer();
        long restaurant = testData.insertRestaurant();
        long menuItem = testData.insertMenuItem(restaurant, new BigDecimal(price));
        testData.insertCartItem(testData.insertCart(customer, restaurant, "ACTIVE"), menuItem, 1, new BigDecimal(price));
        return new Cart(customer, restaurant);
    }

    private record Cart(long customerId, long restaurantId) {
    }
}
