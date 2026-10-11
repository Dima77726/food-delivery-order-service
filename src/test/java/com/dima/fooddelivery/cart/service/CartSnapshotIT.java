package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Корзина хранит снимок блюда — цену и название на момент добавления.
 *
 * <p>Раньше корзина брала название join'ом с меню, и переименование блюда задним числом
 * меняло корзину. Теперь меню живёт в каталоге, и снимок — единственный способ показать
 * корзину без вызова каталога на каждое чтение. А по смыслу он и правильнее: клиент
 * платит за то, что видел, когда выбирал.
 *
 * <p>Меню меняется в тестовом каталоге ({@link TestCatalog}): для корзины это ровно то же,
 * что правка меню в настоящем каталоге.
 */
class CartSnapshotIT extends AbstractIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private TestCatalog catalog;

    @Test
    void shouldKeepPriceAndNameFromMomentOfAdding() {
        Long customerId = testData.insertCustomer();
        Long restaurantId = testData.insertRestaurant();
        Long itemId = testData.insertMenuItem(restaurantId, new BigDecimal("500.00"));
        String originalName = catalog.itemName(itemId);

        cartService.addItemToCart(customerId, restaurantId, new AddCartItemRequest(itemId, 2));

        catalog.changeItem(itemId, "Переименованное блюдо", new BigDecimal("700.00"));

        CartResponse cart = cartService.getActiveCart(customerId, restaurantId);

        assertAll(
                () -> assertEquals(new BigDecimal("500.00"), cart.items().get(0).price()),
                () -> assertEquals(originalName, cart.items().get(0).menuItemName()),
                () -> assertEquals(new BigDecimal("1000.00"), cart.totalAmount())
        );
    }
}
