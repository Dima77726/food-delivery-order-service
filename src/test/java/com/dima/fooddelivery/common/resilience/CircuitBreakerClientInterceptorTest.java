package com.dima.fooddelivery.common.resilience;

import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.RestaurantSummary;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Предохранитель на gRPC-канале против сервера в памяти процесса.
 *
 * <p>Проверяется не сама библиотека, а три решения, которые легко сделать наоборот:
 * размыкание отдаёт отказ без обращения к соседу, деловые статусы здорового соседа
 * предохранитель не размыкают, а после паузы он пропускает пробный вызов и замыкается.
 */
class CircuitBreakerClientInterceptorTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void shutdown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    /** Четыре неудачи из четырёх — порог пройден, пятый вызов до каталога не доходит. */
    @Test
    void shouldRejectCallsWithoutReachingNeighbourOnceOpen() throws IOException {
        FakeCatalog catalog = new FakeCatalog(Status.UNAVAILABLE);
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = stubFor(catalog, Duration.ofSeconds(30));

        for (int i = 0; i < 4; i++) {
            assertThrows(StatusRuntimeException.class, () -> call(stub));
        }
        int reachedBeforeOpen = catalog.calls.get();

        StatusRuntimeException rejected = assertThrows(StatusRuntimeException.class, () -> call(stub));

        assertAll(
                () -> assertEquals(4, reachedBeforeOpen),
                () -> assertEquals(4, catalog.calls.get(), "разомкнутый предохранитель не должен ходить к соседу"),
                // Тот же статус, что вернул бы лежащий сосед: клиенты переводят его в 503 как обычно.
                () -> assertEquals(Status.Code.UNAVAILABLE, rejected.getStatus().getCode()),
                () -> assertTrue(rejected.getStatus().getDescription().contains("Предохранитель"))
        );
    }

    /**
     * Запросы к несуществующему блюду — работа здорового каталога. Считай их неудачами,
     * и поток таких запросов отключил бы исправный каталог.
     */
    @Test
    void shouldNotOpenOnBusinessStatuses() throws IOException {
        FakeCatalog catalog = new FakeCatalog(Status.NOT_FOUND);
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = stubFor(catalog, Duration.ofSeconds(30));

        for (int i = 0; i < 20; i++) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> call(stub));
            assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
        }

        assertEquals(20, catalog.calls.get(), "каждый вызов обязан дойти до каталога");
    }

    @Test
    void shouldCloseAgainOnceNeighbourRecovers() throws Exception {
        FakeCatalog catalog = new FakeCatalog(Status.UNAVAILABLE);
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = stubFor(catalog, Duration.ofMillis(150));

        for (int i = 0; i < 4; i++) {
            assertThrows(StatusRuntimeException.class, () -> call(stub));
        }
        assertThrows(StatusRuntimeException.class, () -> call(stub));
        assertEquals(4, catalog.calls.get(), "пока предохранитель разомкнут, каталог не трогают");

        catalog.failure = null;
        Thread.sleep(300);

        // Пробные вызовы после паузы доходят до ожившего каталога и замыкают предохранитель.
        for (int i = 0; i < 3; i++) {
            assertEquals("Пиццерия", call(stub).getName());
        }
        assertEquals("Пиццерия", call(stub).getName());
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub stubFor(FakeCatalog catalog, Duration openDuration)
            throws IOException {
        CircuitBreakerClientInterceptor interceptor = new CircuitBreakerClientInterceptor(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .waitDurationInOpenState(openDuration)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build()
        );

        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).directExecutor().addService(catalog).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().intercept(interceptor).build();

        resources.add(server::shutdownNow);
        resources.add(channel::shutdownNow);

        return CatalogServiceGrpc.newBlockingStub(channel);
    }

    private static RestaurantSummary call(CatalogServiceGrpc.CatalogServiceBlockingStub stub) {
        return stub.getActiveRestaurant(GetActiveRestaurantRequest.newBuilder().setRestaurantId(1L).build());
    }

    /** Каталог, который отвечает либо заданным отказом, либо успехом; считает обращения к себе. */
    private static class FakeCatalog extends CatalogServiceGrpc.CatalogServiceImplBase {

        final AtomicInteger calls = new AtomicInteger();
        volatile Status failure;

        FakeCatalog(Status failure) {
            this.failure = failure;
        }

        @Override
        public void getActiveRestaurant(GetActiveRestaurantRequest request, StreamObserver<RestaurantSummary> observer) {
            calls.incrementAndGet();

            if (failure != null) {
                observer.onError(failure.asRuntimeException());
                return;
            }

            observer.onNext(RestaurantSummary.newBuilder().setId(request.getRestaurantId()).setName("Пиццерия").build());
            observer.onCompleted();
        }
    }
}
