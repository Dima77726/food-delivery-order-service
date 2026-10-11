package com.dima.fooddelivery.catalog;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.GetAvailableMenuItemRequest;
import com.dima.fooddelivery.grpc.catalog.v1.MenuItemSnapshot;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link CatalogClient} поверх gRPC.
 *
 * <p><b>Статусы обратно в исключения.</b> Каталог переводит свои доменные исключения в статусы
 * gRPC, здесь они переводятся обратно: NOT_FOUND — «нет такого», FAILED_PRECONDITION — «есть,
 * но нельзя». Клиент получает те же 404 и 409, что получал, пока меню жило в монолите.
 * Всё остальное — каталог недоступен, не уложился в дедлайн, упал — это 503: повторить
 * позже имеет смысл, а ошибки клиента тут нет.
 *
 * <p><b>Дедлайн обязателен.</b> По умолчанию у gRPC-вызова его нет, и зависший каталог держал
 * бы поток запроса, а с ним соединение с базой — вызов стоит внутри транзакции корзины.
 *
 * <p><b>Повторов нет.</b> Вызов синхронный, пользователь ждёт. Повтор к недоступному каталогу
 * только удвоил бы ожидание перед тем же отказом, а к перегруженному — добавил бы нагрузки.
 */
@Slf4j
@Component
public class GrpcCatalogClient implements CatalogClient {

    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;
    private final Duration deadline;

    @Autowired
    public GrpcCatalogClient(
            GrpcChannelFactory channels,
            @Value("${app.grpc.catalog.deadline:PT2S}") Duration deadline
    ) {
        // Имя канала — ключ в spring.grpc.client.channels: адрес живёт в конфигурации.
        this(channels.createChannel("catalog"), deadline);
    }

    /** Для тестов: канал к серверу в памяти процесса вместо настоящего адреса. */
    GrpcCatalogClient(Channel channel, Duration deadline) {
        this.stub = CatalogServiceGrpc.newBlockingStub(channel);
        this.deadline = deadline;
    }

    @Override
    public void requireActiveRestaurant(Long restaurantId) {
        call("проверить ресторан", () -> withDeadline().getActiveRestaurant(
                GetActiveRestaurantRequest.newBuilder()
                        .setRestaurantId(restaurantId)
                        .build()
        ));
    }

    @Override
    public CatalogItem requireAvailableItem(Long restaurantId, Long menuItemId) {
        MenuItemSnapshot snapshot = call("получить блюдо", () -> withDeadline().getAvailableMenuItem(
                GetAvailableMenuItemRequest.newBuilder()
                        .setRestaurantId(restaurantId)
                        .setMenuItemId(menuItemId)
                        .build()
        ));

        return new CatalogItem(snapshot.getId(), snapshot.getName(), new BigDecimal(snapshot.getPrice()));
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private <T> T call(String action, Supplier<T> grpcCall) {
        try {
            return grpcCall.get();
        } catch (StatusRuntimeException exception) {
            String description = exception.getStatus().getDescription();

            switch (exception.getStatus().getCode()) {
                case NOT_FOUND -> throw new ResourceNotFoundException(description);
                case FAILED_PRECONDITION -> throw new BusinessRuleViolationException(description);
                default -> {
                    log.error("Не удалось {}: каталог ответил {}", action, exception.getStatus());

                    throw new UpstreamUnavailableException(
                            "Каталог недоступен, не удалось " + action + ". Повторите позже",
                            exception
                    );
                }
            }
        }
    }
}
