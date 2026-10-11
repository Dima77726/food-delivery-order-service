package com.dima.fooddelivery.cart.service;

import com.dima.fooddelivery.cart.api.AddCartItemRequest;
import com.dima.fooddelivery.cart.api.CartResponse;
import com.dima.fooddelivery.cart.api.CartResponseMapper;
import com.dima.fooddelivery.cart.api.UpdateCartItemQuantityRequest;
import com.dima.fooddelivery.cart.domain.Cart;
import com.dima.fooddelivery.cart.domain.CartStatus;
import com.dima.fooddelivery.cart.persistence.CartRepository;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.catalog.CatalogClient;
import com.dima.fooddelivery.catalog.CatalogItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Модуль Cart.
 *
 * <p>Публичный вход для модуля Order — {@link #requireActiveCart(Long, Long)} и
 * {@link #checkout(Long)}. Цену и доступность блюда, как и статус ресторана, корзина
 * спрашивает у каталога через {@link CatalogClient} — каталог живёт в отдельном сервисе.
 *
 * <p>Вызов каталога стоит внутри транзакции корзины, и за это платится соединением с базой,
 * занятым на время сетевого вызова. Плата терпимая: вызов короткий и ограничен дедлайном.
 * Атомарности он при этом не даёт и дать не может — ресторан живёт в другой базе и вправе
 * закрыться через миллисекунду после ответа. Ответ каталога — проверка на момент
 * добавления, а не блокировка.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CatalogClient catalogClient;

    @Transactional(readOnly = true)
    public CartResponse getActiveCart(Long customerId, Long restaurantId) {
        return CartResponseMapper.toResponse(requireActiveCart(customerId, restaurantId));
    }

    @Transactional
    public CartResponse addItemToCart(Long customerId, Long restaurantId, AddCartItemRequest request) {
        catalogClient.requireActiveRestaurant(restaurantId);

        // Цена фиксируется на момент добавления: если блюдо подорожает, в корзине останется
        // та цена, которую клиент видел. Пересчёт на checkout был бы неприятным сюрпризом.
        CatalogItem menuItem = catalogClient.requireAvailableItem(restaurantId, request.menuItemId());

        Long cartId = cartRepository.findActiveCartId(customerId, restaurantId)
                .orElseGet(() -> {
                    Long created = cartRepository.createActiveCart(customerId, restaurantId);
                    log.info("Создана новая активная корзина: cartId={}, customerId={}", created, customerId);
                    return created;
                });

        cartRepository.addOrIncreaseItem(cartId, menuItem.id(), menuItem.name(), request.quantity(), menuItem.price());
        cartRepository.touch(cartId);

        return getActiveCart(customerId, restaurantId);
    }

    @Transactional
    public CartResponse updateCartItemQuantity(
            Long customerId,
            Long restaurantId,
            Long cartItemId,
            UpdateCartItemQuantityRequest request
    ) {
        catalogClient.requireActiveRestaurant(restaurantId);

        Long cartId = requireActiveCartId(customerId, restaurantId);

        int updated = cartRepository.updateItemQuantity(cartId, cartItemId, request.quantity());

        if (updated == 0) {
            log.warn("Позиция корзины не найдена: cartId={}, cartItemId={}", cartId, cartItemId);

            throw new ResourceNotFoundException(
                    "Позиция корзины с id=" + cartItemId + " не найдена в активной корзине с id=" + cartId
            );
        }

        cartRepository.touch(cartId);

        return getActiveCart(customerId, restaurantId);
    }

    @Transactional
    public CartResponse removeCartItem(Long customerId, Long restaurantId, Long cartItemId) {
        catalogClient.requireActiveRestaurant(restaurantId);

        Long cartId = requireActiveCartId(customerId, restaurantId);

        int deleted = cartRepository.deleteItem(cartId, cartItemId);

        if (deleted == 0) {
            log.warn("Позиция корзины не найдена при удалении: cartId={}, cartItemId={}", cartId, cartItemId);

            throw new ResourceNotFoundException(
                    "Позиция корзины с id=" + cartItemId + " не найдена в активной корзине с id=" + cartId
            );
        }

        cartRepository.touch(cartId);

        return getActiveCart(customerId, restaurantId);
    }

    /**
     * Активная корзина клиента вместе с позициями.
     *
     * @throws ResourceNotFoundException если активной корзины нет
     */
    @Transactional(readOnly = true)
    public Cart requireActiveCart(Long customerId, Long restaurantId) {
        return cartRepository.findActiveCart(customerId, restaurantId)
                .orElseThrow(() -> {
                    log.warn(
                            "Активная корзина не найдена: customerId={}, restaurantId={}",
                            customerId,
                            restaurantId
                    );

                    return new ResourceNotFoundException(
                            "Активная корзина не найдена для customerId=" + customerId
                                    + " и restaurantId=" + restaurantId
                    );
                });
    }

    /**
     * Переводит корзину в CHECKED_OUT. Вызывается модулем Order при создании заказа.
     *
     * <p>Ноль обновлённых строк означает, что корзину уже оформили параллельным запросом —
     * это конфликт, а не «не найдено»: иначе из одной корзины получилось бы два заказа.
     *
     * @throws BusinessRuleViolationException если корзина уже не в статусе ACTIVE
     */
    @Transactional
    public void checkout(Long cartId) {
        int updated = cartRepository.compareAndSetStatus(cartId, CartStatus.ACTIVE, CartStatus.CHECKED_OUT);

        if (updated == 0) {
            throw new BusinessRuleViolationException(
                    "Не удалось оформить корзину с id=" + cartId
                            + ". Возможно, она уже была оформлена или изменила статус"
            );
        }
    }

    private Long requireActiveCartId(Long customerId, Long restaurantId) {
        return cartRepository.findActiveCartId(customerId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Активная корзина не найдена для customerId=" + customerId
                                + " и restaurantId=" + restaurantId
                ));
    }
}
