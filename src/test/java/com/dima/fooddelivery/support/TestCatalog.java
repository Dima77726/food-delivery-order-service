package com.dima.fooddelivery.support;

import com.dima.fooddelivery.catalog.CatalogClient;
import com.dima.fooddelivery.catalog.CatalogItem;
import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Каталог в памяти — для интеграционных тестов сервиса заказов.
 *
 * <p>Ресторанов и меню в базе заказов нет: они живут в catalog-service. Тестам корзины,
 * заказа и оплаты нужен ответ на два вопроса — открыт ли ресторан и сколько стоит блюдо, —
 * но не нужен живой каталог: поднимать второй сервис ради каждого теста SQL заказов значило
 * бы проверять всё сразу и медленно. {@link TestDataFactory} заводит рестораны и блюда здесь,
 * а корзина спрашивает отсюда же — через тот же интерфейс {@link CatalogClient}, что и в бою.
 *
 * <p>Правила те же, что у каталога, вплоть до исключений: ресторан проверяется на активность,
 * блюдо ищется в пределах ресторана, снятое с продажи — «нельзя». Совпадение с настоящим
 * каталогом проверяют тесты самого каталога и тест gRPC-клиента.
 *
 * <p>Контекст Spring общий для тестов, а с ним и этот объект: изоляцию дают идентификаторы,
 * которые никогда не повторяются.
 */
public class TestCatalog implements CatalogClient {

    private static final AtomicLong IDS = new AtomicLong(1_000_000L);

    private final Map<Long, Boolean> restaurants = new ConcurrentHashMap<>();
    private final Map<Long, Item> items = new ConcurrentHashMap<>();

    public Long addRestaurant(boolean active) {
        long id = IDS.incrementAndGet();
        restaurants.put(id, active);
        return id;
    }

    public Long addItem(Long restaurantId, String name, BigDecimal price, boolean available) {
        long id = IDS.incrementAndGet();
        items.put(id, new Item(id, restaurantId, name, price, available));
        return id;
    }

    /** Правка меню «в каталоге» — для проверки, что корзина держит свой снимок. */
    public void changeItem(Long itemId, String name, BigDecimal price) {
        items.computeIfPresent(itemId, (id, item) -> new Item(id, item.restaurantId(), name, price, item.available()));
    }

    public String itemName(Long itemId) {
        return items.get(itemId).name();
    }

    @Override
    public void requireActiveRestaurant(Long restaurantId) {
        Boolean active = restaurants.get(restaurantId);

        if (active == null) {
            throw new ResourceNotFoundException("Ресторан с id=" + restaurantId + " не найден");
        }

        if (!active) {
            throw new BusinessRuleViolationException("Ресторан с id=" + restaurantId + " сейчас недоступен для заказов");
        }
    }

    @Override
    public CatalogItem requireAvailableItem(Long restaurantId, Long menuItemId) {
        Item item = items.get(menuItemId);

        if (item == null || !item.restaurantId().equals(restaurantId)) {
            throw new ResourceNotFoundException(
                    "Позиция меню с id=" + menuItemId + " не найдена в ресторане с id=" + restaurantId
            );
        }

        if (!item.available()) {
            throw new BusinessRuleViolationException("Позиция меню с id=" + menuItemId + " сейчас недоступна");
        }

        return new CatalogItem(item.id(), item.name(), item.price());
    }

    private record Item(Long id, Long restaurantId, String name, BigDecimal price, boolean available) {
    }
}
