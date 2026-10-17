package com.dima.fooddelivery.order.api;

import jakarta.validation.Valid;

/**
 * Тело запроса на оформление заказа.
 *
 * <p>Тело целиком необязательно, как и адрес в нём: так продолжают работать клиенты, написанные до
 * появления адреса (Postman-коллекция, ИИ-помощник, автотесты). Веб-интерфейс адрес присылает всегда.
 */
public record CreateOrderRequest(
        @Valid
        DeliveryAddressRequest deliveryAddress
) {
}
