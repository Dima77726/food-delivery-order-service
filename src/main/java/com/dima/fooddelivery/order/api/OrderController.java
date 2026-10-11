package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.common.api.CursorPageResponse;
import com.dima.fooddelivery.common.api.CursorRequestParams;
import com.dima.fooddelivery.common.api.PageRequestParams;
import com.dima.fooddelivery.common.api.PageResponse;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Контроллер тонкий: принимает запрос, проверяет права, зовёт сервис, отдаёт DTO.
 *
 * <p>Логируется только вход. Результат пишет сервис — он знает, что на самом деле произошло,
 * а дублирующая пара «получен запрос» / «возвращаем ответ» на каждый метод удваивала бы
 * объём логов, ничего не добавляя.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Order", description = "Заказы клиента и работа ресторана с ними")
@SecurityRequirement(name = "bearer-jwt")
public class OrderController {

    private final OrderService orderService;

    // --- Сценарии клиента --------------------------------------------------------------------

    @PostMapping("/api/v1/customers/{customerId}/restaurants/{restaurantId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Создать заказ из активной корзины")
    public OrderResponse createOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId
    ) {
        log.info("Создание заказа: customerId={}, restaurantId={}", customerId, restaurantId);

        return orderService.createOrderFromActiveCart(customerId, restaurantId);
    }

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Заказ клиента по идентификатору")
    public OrderResponse getOrderById(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        return orderService.getOrderByIdForCustomer(customerId, orderId);
    }

    @GetMapping("/api/v1/customers/{customerId}/orders")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Список заказов клиента, постранично")
    public PageResponse<OrderSummaryResponse> getOrdersByCustomer(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Valid PageRequestParams page
    ) {
        return orderService.getOrdersByCustomer(customerId, page);
    }

    /**
     * Отдельный путь, а не ещё один режим у {@code /orders}: у курсорного ответа другой набор
     * полей — нет ни номера страницы, ни общего количества. Слить их в один тип значило бы
     * отдавать половину полей всегда пустыми и заставлять каждого клиента гадать,
     * какая половина сейчас заполнена.
     */
    @GetMapping("/api/v1/customers/{customerId}/orders/feed")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Лента заказов клиента: курсорная выборка для подгрузки вниз")
    public CursorPageResponse<OrderSummaryResponse> getOrdersFeedByCustomer(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Valid CursorRequestParams cursor
    ) {
        return orderService.getOrdersByCustomerAfter(customerId, cursor);
    }

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}/events")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "История событий заказа")
    public List<OrderEventResponse> getOrderEvents(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        return orderService.getOrderEventsForCustomer(customerId, orderId);
    }

    @PostMapping("/api/v1/customers/{customerId}/orders/{orderId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Отменить заказ (доступно до принятия рестораном)")
    public OrderResponse cancelOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info("Отмена заказа: customerId={}, orderId={}", customerId, orderId);

        return orderService.cancelOrder(customerId, orderId);
    }

    // --- Сценарии ресторана ------------------------------------------------------------------

    @GetMapping("/api/v1/restaurants/{restaurantId}/orders")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Заказы ресторана постранично, при необходимости отфильтрованные по статусу")
    public PageResponse<OrderSummaryResponse> getRestaurantOrders(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @RequestParam(required = false) OrderStatus status,

            @Valid PageRequestParams page
    ) {
        return orderService.getOrdersForRestaurant(restaurantId, status, page);
    }

    @GetMapping("/api/v1/restaurants/{restaurantId}/orders/feed")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Лента заказов ресторана: курсорная выборка, при необходимости по статусу")
    public CursorPageResponse<OrderSummaryResponse> getRestaurantOrdersFeed(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @RequestParam(required = false) OrderStatus status,

            @Valid CursorRequestParams cursor
    ) {
        return orderService.getOrdersForRestaurantAfter(restaurantId, status, cursor);
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/accept")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Принять оплаченный заказ в работу")
    public OrderResponse acceptOrder(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info("Принятие заказа рестораном: restaurantId={}, orderId={}", restaurantId, orderId);

        return orderService.acceptOrder(restaurantId, orderId);
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/start-cooking")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Начать готовить заказ")
    public OrderResponse startCookingOrder(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info("Начало готовки: restaurantId={}, orderId={}", restaurantId, orderId);

        return orderService.startCookingOrder(restaurantId, orderId);
    }

    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/ready-for-delivery")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Отметить заказ готовым к доставке")
    public OrderResponse markOrderReadyForDelivery(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info("Готовность к доставке: restaurantId={}, orderId={}", restaurantId, orderId);

        return orderService.markOrderReadyForDelivery(restaurantId, orderId);
    }
}
