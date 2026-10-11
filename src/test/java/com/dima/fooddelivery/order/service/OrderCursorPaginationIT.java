package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.common.api.CursorPageResponse;
import com.dima.fooddelivery.common.api.CursorRequestParams;
import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.exception.InvalidCursorException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Курсорная выборка заказов на настоящем PostgreSQL.
 *
 * <p>Все заказы теста создаются внутри одной транзакции, а значит {@code CURRENT_TIMESTAMP}
 * у них совпадает до последнего разряда. Это не помеха, а нужный случай: порядок целиком
 * держится на id как втором критерии сортировки, и именно так проверяется, что курсор
 * не теряет и не дублирует строки с одинаковой датой.
 */
class OrderCursorPaginationIT extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Test
    void shouldWalkWholeListWithoutGapsOrDuplicates() {
        Long customerId = testData.insertCustomer();

        int total = 7;
        for (int i = 0; i < total; i++) {
            testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        }

        List<Long> collected = collectFeed(customerId, 2);

        assertAll(
                () -> assertEquals(total, collected.size()),
                () -> assertEquals(total, new HashSet<>(collected).size(), "заказы не должны повторяться"),
                () -> assertEquals(sortedDescending(collected), collected, "порядок обязан сохраняться между порциями")
        );
    }

    @Test
    void shouldStopAtTheEndOfList() {
        Long customerId = testData.insertCustomer();

        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        CursorPageResponse<OrderSummaryResponse> whole =
                orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(null, 10));

        assertAll(
                () -> assertEquals(2, whole.content().size()),
                () -> assertFalse(whole.hasNext()),
                () -> assertNull(whole.nextCursor(), "курсор выдаётся только когда есть продолжение")
        );
    }

    /**
     * Последняя порция ровно заполняет запрошенный размер. Разведочная строка для того
     * и читается: без неё пришлось бы отдать курсор на всякий случай и заставить клиента
     * сходить за заведомо пустым ответом.
     */
    @Test
    void shouldNotPromiseContinuationWhenListEndsExactlyOnPageBoundary() {
        Long customerId = testData.insertCustomer();

        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        CursorPageResponse<OrderSummaryResponse> page =
                orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(null, 2));

        assertAll(
                () -> assertEquals(2, page.content().size()),
                () -> assertFalse(page.hasNext()),
                () -> assertNull(page.nextCursor())
        );
    }

    /**
     * Ради этого всё и затевалось. Между двумя запросами появляются новые заказы; они встают
     * в начало списка и при листании по OFFSET сдвинули бы окно, показав клиенту уже виденные
     * строки второй раз. Курсор привязан к содержимому, поэтому продолжение остаётся
     * продолжением.
     */
    @Test
    void shouldNotShiftWindowWhenNewOrdersArriveWhileReading() {
        Long customerId = testData.insertCustomer();

        for (int i = 0; i < 4; i++) {
            testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        }

        CursorPageResponse<OrderSummaryResponse> first =
                orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(null, 2));

        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        CursorPageResponse<OrderSummaryResponse> second =
                orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(first.nextCursor(), 2));

        Set<Long> seen = new HashSet<>(idsOf(first));

        assertAll(
                () -> assertEquals(2, second.content().size()),
                () -> assertTrue(
                        idsOf(second).stream().noneMatch(seen::contains),
                        "вставки выше по списку не должны возвращать уже показанные заказы"
                )
        );
    }

    /**
     * Тот же сценарий на постраничной выборке — для сравнения. Вторая страница после двух
     * вставок отдаёт заказы, которые клиент уже видел на первой: это не дефект реализации,
     * а свойство OFFSET, и оно зафиксировано здесь, чтобы выбор между двумя эндпоинтами
     * делался осознанно.
     */
    @Test
    void shouldDemonstrateThatOffsetPagingRepeatsRowsAfterInserts() {
        Long customerId = testData.insertCustomer();

        for (int i = 0; i < 4; i++) {
            testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        }

        List<Long> firstPage = idsOf(orderService.getOrdersByCustomer(customerId, new PageRequestParams(0, 2))
                .content());

        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        List<Long> secondPage = idsOf(orderService.getOrdersByCustomer(customerId, new PageRequestParams(1, 2))
                .content());

        assertTrue(
                secondPage.stream().anyMatch(firstPage::contains),
                "сдвиг окна при OFFSET — ожидаемое поведение, курсор нужен именно из-за него"
        );
    }

    @Test
    void shouldApplyDefaultSizeWhenNotRequested() {
        Long customerId = testData.insertCustomer();
        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);

        CursorPageResponse<OrderSummaryResponse> page =
                orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(null, null));

        assertAll(
                () -> assertEquals(PageRequestParams.DEFAULT_PAGE_SIZE, page.size()),
                () -> assertEquals(1, page.content().size())
        );
    }

    @Test
    void shouldRejectForgedCursor() {
        Long customerId = testData.insertCustomer();

        assertThrows(
                InvalidCursorException.class,
                () -> orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams("подделка", 2))
        );
    }

    @Test
    void shouldNotLeakOrdersOfOtherCustomers() {
        Long customerId = testData.insertCustomer();
        Long stranger = testData.insertCustomer();

        testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        testData.insertOrderInStatus(stranger, OrderStatus.CREATED);

        CursorPageResponse<OrderSummaryResponse> page =
                orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(null, 10));

        assertAll(
                () -> assertEquals(1, page.content().size()),
                () -> assertEquals(customerId, page.content().get(0).customerId())
        );
    }

    @Test
    void shouldFilterRestaurantFeedByStatus() {
        Long customerId = testData.insertCustomer();

        var paid = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Long restaurantId = paid.restaurantId();

        Long cartId = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");
        testData.insertOrder(cartId, customerId, restaurantId, OrderStatus.CREATED, paid.price());

        CursorPageResponse<OrderSummaryResponse> page = orderService.getOrdersForRestaurantAfter(
                restaurantId,
                OrderStatus.PAID,
                new CursorRequestParams(null, 10)
        );

        assertAll(
                () -> assertEquals(1, page.content().size()),
                () -> assertEquals(OrderStatus.PAID.getDbValue(), page.content().get(0).status()),
                () -> assertFalse(page.hasNext())
        );
    }

    /**
     * Фильтр обязан переживать переход по курсору: иначе вторая порция приедет без него
     * и клиент получит заказы в чужих статусах.
     */
    @Test
    void shouldKeepStatusFilterAcrossCursorSteps() {
        Long customerId = testData.insertCustomer();

        var paid = testData.insertOrderInStatus(customerId, OrderStatus.PAID);
        Long restaurantId = paid.restaurantId();

        for (int i = 0; i < 2; i++) {
            Long paidCart = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");
            testData.insertOrder(paidCart, customerId, restaurantId, OrderStatus.PAID, paid.price());

            Long createdCart = testData.insertCart(customerId, restaurantId, "CHECKED_OUT");
            testData.insertOrder(createdCart, customerId, restaurantId, OrderStatus.CREATED, paid.price());
        }

        CursorPageResponse<OrderSummaryResponse> first = orderService.getOrdersForRestaurantAfter(
                restaurantId,
                OrderStatus.PAID,
                new CursorRequestParams(null, 2)
        );

        CursorPageResponse<OrderSummaryResponse> second = orderService.getOrdersForRestaurantAfter(
                restaurantId,
                OrderStatus.PAID,
                new CursorRequestParams(first.nextCursor(), 2)
        );

        assertAll(
                () -> assertNotNull(first.nextCursor(), "трёх оплаченных заказов хватает на продолжение"),
                () -> assertEquals(1, second.content().size(), "оплаченных заказов всего три"),
                () -> assertTrue(
                        second.content().stream()
                                .allMatch(order -> OrderStatus.PAID.getDbValue().equals(order.status())),
                        "статус должен фильтроваться и на второй порции"
                )
        );
    }

    private List<Long> collectFeed(Long customerId, int size) {
        List<Long> collected = new ArrayList<>();

        String cursor = null;
        do {
            CursorPageResponse<OrderSummaryResponse> page =
                    orderService.getOrdersByCustomerAfter(customerId, new CursorRequestParams(cursor, size));

            collected.addAll(idsOf(page.content()));
            cursor = page.nextCursor();
        } while (cursor != null);

        return collected;
    }

    private static List<Long> idsOf(CursorPageResponse<OrderSummaryResponse> page) {
        return idsOf(page.content());
    }

    private static List<Long> idsOf(List<OrderSummaryResponse> orders) {
        return orders.stream().map(OrderSummaryResponse::id).toList();
    }

    private static List<Long> sortedDescending(List<Long> ids) {
        return ids.stream().sorted((left, right) -> Long.compare(right, left)).toList();
    }
}
