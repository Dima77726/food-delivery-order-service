package com.dima.fooddelivery.cart.api;

import com.dima.fooddelivery.cart.service.CartService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/customers/{customerId}/restaurants/{restaurantId}/cart")
@Tag(name = "Cart", description = "Корзина клиента в конкретном ресторане")
@SecurityRequirement(name = "bearer-jwt")
// customerId стоит в пути, поэтому одной роли CUSTOMER мало: без сверки с токеном
// любой залогиненный клиент открыл бы чужую корзину, подставив чужой идентификатор.
@PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getActiveCart(@PathVariable Long customerId, @PathVariable Long restaurantId) {

        log.info(
                "Получен запрос на получение активной корзины: customerId={}, restaurantId={}",
                customerId,
                restaurantId
        );

        CartResponse response = cartService.getActiveCart(customerId, restaurantId);

        log.info(
                "Возвращаем активную корзину: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    @PostMapping
    public CartResponse addItemToCart(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        log.info(
                "Получен запрос на добавление позиции в корзину: customerId={}, restaurantId={}, menuItemId={}, quantity={}",
                customerId,
                restaurantId,
                request.menuItemId(),
                request.quantity()
        );

        CartResponse response = cartService.addItemToCart(customerId, restaurantId, request);

        log.info(
                "Позиция успешно добавлена в корзину: cartId={}, itemsCount={}, totalAmount={}",
                response.id(),
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    @PatchMapping("/items/{cartItemId}")
    public CartResponse updateCartItemQuantity(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "cartItemId должен быть положительным числом")
            @PathVariable Long cartItemId,

            @Valid @RequestBody UpdateCartItemQuantityRequest request
    ) {
        log.info(
                "Получен запрос на изменение количества позиции корзины: customerId={}, restaurantId={}, cartItemId={}, quantity={}",
                customerId,
                restaurantId,
                cartItemId,
                request.quantity()
        );

        CartResponse response = cartService.updateCartItemQuantity(
                customerId,
                restaurantId,
                cartItemId,
                request
        );

        log.info(
                "Количество позиции корзины успешно изменено: cartId={}, cartItemId={}, itemsCount={}, totalAmount={}",
                response.id(),
                cartItemId,
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

    @DeleteMapping("/items/{cartItemId}")
    public CartResponse removeCartItem(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "cartItemId должен быть положительным числом")
            @PathVariable Long cartItemId
    ) {
        log.info(
                "Получен запрос на удаление позиции из корзины: customerId={}, restaurantId={}, cartItemId={}",
                customerId,
                restaurantId,
                cartItemId
        );

       CartResponse response = cartService.removeCartItem(customerId, restaurantId, cartItemId);

        log.info(
                "Позиция успешно удалена из корзины: cartId={}, cartItemId={}, itemsCount={}, totalAmount={}",
                response.id(),
                cartItemId,
                response.items().size(),
                response.totalAmount()
        );

        return response;
    }

}
