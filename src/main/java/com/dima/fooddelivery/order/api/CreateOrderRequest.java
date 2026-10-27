package com.dima.fooddelivery.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Тело запроса на оформление заказа.
 *
 * <p>Тело целиком необязательно, как и адрес в нём: так продолжают работать клиенты, написанные до
 * появления адреса (Postman-коллекция, ИИ-помощник, автотесты). Веб-интерфейс адрес присылает всегда.
 *
 * @param promoCode    промокод; проверяется и гасится при оформлении
 * @param tipAmount    чаевые курьеру, входят в сумму оплаты
 * @param scheduledFor к какому времени доставить; пусто — как можно скорее
 */
public record CreateOrderRequest(
        @Valid
        DeliveryAddressRequest deliveryAddress,

        @Size(max = 40, message = "Промокод не длиннее 40 символов")
        String promoCode,

        @DecimalMin(value = "0.00", message = "Чаевые не могут быть отрицательными")
        BigDecimal tipAmount,

        OffsetDateTime scheduledFor
) {

    public CreateOrderRequest(DeliveryAddressRequest deliveryAddress) {
        this(deliveryAddress, null, null, null);
    }
}
