package com.dima.fooddelivery.order.domain;

import java.math.BigDecimal;

/**
 * Куда везти заказ: адрес строкой, уточнение для курьера и точка на карте.
 *
 * <p>Строка адреса — то, что видят курьер и ресторан; координаты нужны карте и маршруту. Хранятся
 * обе: обратное геокодирование неточно, и клиент вправе поправить подпись, не двигая точку.
 * Координаты могут отсутствовать (адрес введён вручную без выбора на карте), но только вместе.
 */
public record DeliveryAddress(
        String address,
        String details,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
