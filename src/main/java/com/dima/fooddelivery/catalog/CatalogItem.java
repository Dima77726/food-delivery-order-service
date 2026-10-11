package com.dima.fooddelivery.catalog;

import java.math.BigDecimal;

/**
 * Блюдо в том виде, в каком его кладут в корзину: идентификатор, название и цена
 * на момент добавления.
 */
public record CatalogItem(
        Long id,
        String name,
        BigDecimal price
) {
}
