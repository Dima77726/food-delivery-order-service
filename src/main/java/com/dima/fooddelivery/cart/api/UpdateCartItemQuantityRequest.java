package com.dima.fooddelivery.cart.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateCartItemQuantityRequest(
        @NotNull(message = "quantity must not be null")
        @Positive(message = "quantity must be positive")
        Integer quantity
) {
}
