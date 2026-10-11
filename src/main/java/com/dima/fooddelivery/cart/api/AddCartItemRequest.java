package com.dima.fooddelivery.cart.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull(message = "menuItemId must not be null")
        @Positive(message = "menuItemId must be positive")
        Long menuItemId,
        @NotNull(message = "quantity must not be null")
        @Positive(message = "quantity must be positive")
        Integer quantity
) {
}