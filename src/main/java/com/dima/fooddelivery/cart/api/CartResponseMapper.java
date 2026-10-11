package com.dima.fooddelivery.cart.api;

import com.dima.fooddelivery.cart.domain.Cart;
import com.dima.fooddelivery.cart.domain.CartItem;

public final class CartResponseMapper {

    public static CartResponse toResponse(Cart cart) {
        return new CartResponse(
                cart.id(),
                cart.customerId(),
                cart.restaurantId(),
                cart.status().getDbValue(),
                cart.items().stream().map(CartResponseMapper::toResponse).toList(),
                cart.totalAmount()
        );
    }

    private static CartItemResponse toResponse(CartItem item) {
        return new CartItemResponse(
                item.id(),
                item.menuItemId(),
                item.menuItemName(),
                item.quantity(),
                item.price(),
                item.lineTotal()
        );
    }

    private CartResponseMapper() {
    }
}
