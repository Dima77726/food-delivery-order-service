package com.dima.fooddelivery.catalog;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.GetAvailableMenuItemRequest;
import com.dima.fooddelivery.grpc.catalog.v1.MenuItemSnapshot;
import com.dima.fooddelivery.grpc.catalog.v1.RestaurantSummary;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC-клиент к каталогу против сервера в памяти процесса.
 *
 * <p>Главное, что здесь проверяется, — перевод статусов обратно в доменные исключения.
 * От него зависит, что увидит клиент корзины: 404 на чужое блюдо, 409 на закрытый ресторан,
 * 503 на упавший каталог. Ошибись перевод — и недоступный каталог выглядел бы для
 * пользователя как «такого блюда нет».
 */
class GrpcCatalogClientTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void shutdown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    @Test
    void shouldReturnItemWithExactPrice() throws IOException {
        CatalogClient client = clientFor(new FakeCatalog(null), Duration.ofSeconds(2));

        CatalogItem item = client.requireAvailableItem(1L, 10L);

        assertAll(
                () -> assertEquals(10L, item.id()),
                () -> assertEquals("Маргарита", item.name()),
                () -> assertEquals(0, new BigDecimal("450.10").compareTo(item.price()))
        );
    }

    @Test
    void shouldTurnNotFoundIntoResourceNotFound() throws IOException {
        CatalogClient client = clientFor(new FakeCatalog(Status.NOT_FOUND.withDescription("нет такого")), Duration.ofSeconds(2));

        ResourceNotFoundException error = assertThrows(
                ResourceNotFoundException.class,
                () -> client.requireAvailableItem(1L, 10L)
        );

        assertEquals("нет такого", error.getMessage(), "текст каталога доходит до клиента без изменений");
    }

    @Test
    void shouldTurnFailedPreconditionIntoBusinessRuleViolation() throws IOException {
        CatalogClient client = clientFor(new FakeCatalog(Status.FAILED_PRECONDITION.withDescription("закрыт")), Duration.ofSeconds(2));

        assertThrows(BusinessRuleViolationException.class, () -> client.requireActiveRestaurant(1L));
    }

    /** Упавший каталог — 503, а не «такого блюда нет». */
    @Test
    void shouldTurnAnyOtherFailureIntoUpstreamUnavailable() throws IOException {
        CatalogClient client = clientFor(new FakeCatalog(Status.UNAVAILABLE), Duration.ofSeconds(2));

        assertThrows(UpstreamUnavailableException.class, () -> client.requireActiveRestaurant(1L));
        assertThrows(UpstreamUnavailableException.class, () -> client.requireAvailableItem(1L, 10L));
    }

    /** Без дедлайна зависший каталог держал бы поток запроса и соединение с базой бесконечно. */
    @Test
    void shouldGiveUpAfterDeadline() throws IOException {
        CatalogClient client = clientFor(new FakeCatalog(null, true), Duration.ofMillis(200));

        long started = System.nanoTime();
        assertThrows(UpstreamUnavailableException.class, () -> client.requireActiveRestaurant(1L));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 2_000, "ожидание обязано оборваться по дедлайну, а длилось " + elapsedMillis + " мс");
    }

    private CatalogClient clientFor(FakeCatalog catalog, Duration deadline) throws IOException {
        String name = InProcessServerBuilder.generateName();

        Server server = InProcessServerBuilder.forName(name).directExecutor().addService(catalog).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        resources.add(server::shutdownNow);
        resources.add(channel::shutdownNow);

        return new GrpcCatalogClient(channel, deadline);
    }

    /** Каталог, который либо отвечает, либо отказывает заданным статусом, либо молчит. */
    private static class FakeCatalog extends CatalogServiceGrpc.CatalogServiceImplBase {

        private final Status failure;
        private final boolean hang;

        FakeCatalog(Status failure) {
            this(failure, false);
        }

        FakeCatalog(Status failure, boolean hang) {
            this.failure = failure;
            this.hang = hang;
        }

        @Override
        public void getActiveRestaurant(GetActiveRestaurantRequest request, StreamObserver<RestaurantSummary> observer) {
            respond(observer, RestaurantSummary.newBuilder().setId(request.getRestaurantId()).setName("Пиццерия").build());
        }

        @Override
        public void getAvailableMenuItem(GetAvailableMenuItemRequest request, StreamObserver<MenuItemSnapshot> observer) {
            respond(observer, MenuItemSnapshot.newBuilder()
                    .setId(request.getMenuItemId())
                    .setName("Маргарита")
                    .setPrice("450.10")
                    .build());
        }

        private <T> void respond(StreamObserver<T> observer, T response) {
            if (hang) {
                return;
            }

            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }

            observer.onNext(response);
            observer.onCompleted();
        }
    }
}
