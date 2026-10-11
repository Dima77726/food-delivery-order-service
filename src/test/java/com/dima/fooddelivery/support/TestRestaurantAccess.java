package com.dima.fooddelivery.support;

import com.dima.fooddelivery.common.security.RestaurantAccessClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Права на рестораны в памяти — вместо gRPC-вызова в identity-service.
 *
 * <p>Тест, которому нужен управляющий ресторана, назначает его здесь
 * ({@link TestDataFactory#assignRestaurantManager}), а контроллеры заказов ресторана спрашивают
 * отсюда же через {@link RestaurantAccessClient}. Сам gRPC-клиент проверяется отдельно,
 * против сервера в памяти процесса.
 */
public class TestRestaurantAccess implements RestaurantAccessClient {

    private final Set<String> grants = ConcurrentHashMap.newKeySet();

    @Override
    public boolean managesRestaurant(long userId, long restaurantId) {
        return grants.contains(userId + ":" + restaurantId);
    }

    @Override
    public void assignManager(long userId, long restaurantId) {
        grants.add(userId + ":" + restaurantId);
    }
}
