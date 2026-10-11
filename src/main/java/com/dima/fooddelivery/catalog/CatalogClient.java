package com.dima.fooddelivery.catalog;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;

/**
 * Каталог глазами сервиса заказов: ровно два вопроса, которые задаёт корзина.
 *
 * <p>Интерфейс, а не gRPC-стаб в сервисе корзины. Корзине незачем знать, что каталог
 * отвечает по сети: для неё это тот же «спроси у модуля меню», что и раньше, с теми же
 * исключениями. Отсюда и выбор исключений — доменные, а не статусы gRPC: код корзины
 * и обработчик ошибок не поменялись ни строкой.
 */
public interface CatalogClient {

    /**
     * @throws ResourceNotFoundException      если ресторана нет
     * @throws BusinessRuleViolationException если ресторан не принимает заказы
     * @throws UpstreamUnavailableException   если каталог не ответил
     */
    void requireActiveRestaurant(Long restaurantId);

    /**
     * @throws ResourceNotFoundException      если блюда нет в меню этого ресторана
     * @throws BusinessRuleViolationException если блюдо сейчас не продаётся
     * @throws UpstreamUnavailableException   если каталог не ответил
     */
    CatalogItem requireAvailableItem(Long restaurantId, Long menuItemId);
}
