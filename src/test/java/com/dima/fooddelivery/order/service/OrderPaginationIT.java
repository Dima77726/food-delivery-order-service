package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.order.api.OrderSummaryResponse;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderPaginationIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Test
    void shouldSplitCustomerOrdersIntoPages() {
        Long customerId = testData.insertCustomer();

        for (int i = 0; i < 5; i++) {
            testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        }

        PageResponse<OrderSummaryResponse> first =
                orderService.getOrdersByCustomer(customerId, new PageRequestParams(0, 2));
        PageResponse<OrderSummaryResponse> last =
                orderService.getOrdersByCustomer(customerId, new PageRequestParams(2, 2));

        assertAll(
                () -> assertEquals(2, first.content().size()),
                () -> assertEquals(5, first.totalElements()),
                () -> assertEquals(3, first.totalPages(), "5 заказов по 2 на страницу — это три страницы"),
                () -> assertEquals(1, last.content().size(), "на последней странице остаток")
        );
    }

    /**
     * Главный риск постраничной выборки: без строгого порядка база вправе вернуть одну
     * и ту же строку на двух соседних страницах. Здесь у всех заказов created_at совпадает
     * с точностью до микросекунд, поэтому сортировка держится на id — как раз тот случай,
     * ради которого он добавлен вторым критерием.
     */
    @Test
    void shouldNotRepeatOrdersAcrossPages() {
        Long customerId = testData.insertCustomer();

        int total = 7;
        for (int i = 0; i < total; i++) {
            testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        }

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            orderService.getOrdersByCustomer(customerId, new PageRequestParams(page, 2))
                    .content()
                    .forEach(order -> collected.add(order.id()));
        }

        Set<Long> unique = new HashSet<>(collected);

        assertAll(
                () -> assertEquals(total, collected.size()),
                () -> assertEquals(total, unique.size(), "заказы не должны повторяться между страницами")
        );
    }

    @Test
    void shouldReturnEmptyPageBeyondLastOne() {
        Long customerId = testData.insertCustomer();
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        PageResponse<OrderSummaryResponse> page =
                orderService.getOrdersByCustomer(customerId, new PageRequestParams(10, 20));

        assertAll(
                () -> assertTrue(page.content().isEmpty()),
                () -> assertEquals(1, page.totalElements(), "итог не зависит от запрошенной страницы")
        );
    }

    @Test
    void shouldApplyDefaultsWhenParamsAreMissing() {
        Long customerId = testData.insertCustomer();
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        PageResponse<OrderSummaryResponse> page =
                orderService.getOrdersByCustomer(customerId, new PageRequestParams(null, null));

        assertAll(
                () -> assertEquals(0, page.page()),
                () -> assertEquals(PageRequestParams.DEFAULT_PAGE_SIZE, page.size()),
                () -> assertEquals(1, page.content().size())
        );
    }

    @Test
    void shouldCountOnlyMatchingStatusForRestaurant() {
        Long customerId = testData.insertCustomer();

        var paid = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Long restaurantId = paid.restaurantId();

        // Второй заказ того же ресторана, но в другом статусе: он не должен попасть
        // ни в содержимое, ни в totalElements.
        Long cartId = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");
        testData.insertOrder(cartId, customerId, restaurantId, OrderStatus.CREATED, paid.price());

        PageResponse<OrderSummaryResponse> page = orderService.getOrdersForRestaurant(
                restaurantId,
                OrderStatus.PAID,
                new PageRequestParams(0, 20)
        );

        assertAll(
                () -> assertEquals(1, page.content().size()),
                () -> assertEquals(1, page.totalElements()),
                () -> assertEquals(OrderStatus.PAID.getDbValue(), page.content().get(0).status())
        );
    }
}
