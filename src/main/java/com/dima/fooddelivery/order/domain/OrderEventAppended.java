package com.dima.fooddelivery.order.domain;

/**
 * В историю заказа дописано событие — со сменой статуса или без неё (например, неудачная
 * попытка оплаты). Внутреннее событие приложения: на него подписаны живые обновления клиента.
 */
public record OrderEventAppended(Long orderId) {
}
