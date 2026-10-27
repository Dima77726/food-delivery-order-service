package com.dima.fooddelivery.delivery.domain;

/**
 * Изменился список доставок, которые курьер может взять: появилась новая или одну уже взяли.
 * Внутреннее событие приложения: на него подписаны курьеры, ждущие новых доставок.
 */
public record DeliveryAvailabilityChanged() {
}
