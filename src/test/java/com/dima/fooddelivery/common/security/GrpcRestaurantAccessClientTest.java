package com.dima.fooddelivery.common.security;

import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.identity.v1.AssignRestaurantManagerRequest;
import com.dima.fooddelivery.grpc.identity.v1.AssignRestaurantManagerResponse;
import com.dima.fooddelivery.grpc.identity.v1.CheckRestaurantAccessRequest;
import com.dima.fooddelivery.grpc.identity.v1.CheckRestaurantAccessResponse;
import com.dima.fooddelivery.grpc.identity.v1.RestaurantAccessServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC-клиент к владельцу прав против сервера в памяти процесса.
 *
 * <p>Сервер в памяти — тот же gRPC, только без сети: настоящая сериализация, настоящие статусы,
 * настоящие дедлайны. Проверяется то, что у клиента может сломаться: перевод сбоя в 503
 * и то, что дедлайн действительно ограничивает ожидание. Быстрый тест, без Docker.
 */
class GrpcRestaurantAccessClientTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void shutdown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    @Test
    void shouldAskServerWhetherUserManagesRestaurant() throws IOException {
        GrpcRestaurantAccessClient client = clientFor(new FakeAccessService(Behaviour.ANSWER), Duration.ofSeconds(2));

        assertTrue(client.managesRestaurant(7L, 42L));
    }

    /** Упавший владелец прав — 503 для пользователя, а не 500 и не «нет прав». */
    @Test
    void shouldTurnServerFailureIntoUpstreamUnavailable() throws IOException {
        GrpcRestaurantAccessClient client = clientFor(new FakeAccessService(Behaviour.UNAVAILABLE), Duration.ofSeconds(2));

        assertThrows(UpstreamUnavailableException.class, () -> client.managesRestaurant(7L, 42L));
        assertThrows(UpstreamUnavailableException.class, () -> client.assignManager(7L, 42L));
    }

    /**
     * Зависший сервер не держит вызов дольше дедлайна. Без дедлайна этот тест висел бы вечно —
     * ровно так, как висел бы запрос пользователя.
     */
    @Test
    void shouldGiveUpAfterDeadline() throws IOException {
        GrpcRestaurantAccessClient client = clientFor(new FakeAccessService(Behaviour.HANG), Duration.ofMillis(200));

        long started = System.nanoTime();
        assertThrows(UpstreamUnavailableException.class, () -> client.managesRestaurant(7L, 42L));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 2_000, "ожидание обязано оборваться по дедлайну, а длилось " + elapsedMillis + " мс");
    }

    @Test
    void shouldSendAssignmentToServer() throws IOException {
        FakeAccessService service = new FakeAccessService(Behaviour.ANSWER);
        GrpcRestaurantAccessClient client = clientFor(service, Duration.ofSeconds(2));

        client.assignManager(7L, 42L);

        assertEquals("7:42", service.lastAssignment);
    }

    private GrpcRestaurantAccessClient clientFor(FakeAccessService service, Duration deadline) throws IOException {
        String name = InProcessServerBuilder.generateName();

        Server server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        resources.add(server::shutdownNow);
        resources.add(channel::shutdownNow);

        return new GrpcRestaurantAccessClient(channel, deadline);
    }

    private enum Behaviour { ANSWER, UNAVAILABLE, HANG }

    private static class FakeAccessService extends RestaurantAccessServiceGrpc.RestaurantAccessServiceImplBase {

        private final Behaviour behaviour;
        private volatile String lastAssignment;

        FakeAccessService(Behaviour behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public void checkRestaurantAccess(
                CheckRestaurantAccessRequest request,
                StreamObserver<CheckRestaurantAccessResponse> responseObserver
        ) {
            switch (behaviour) {
                case ANSWER -> {
                    responseObserver.onNext(CheckRestaurantAccessResponse.newBuilder().setManages(true).build());
                    responseObserver.onCompleted();
                }
                case UNAVAILABLE -> responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                case HANG -> {
                    // Не отвечаем вовсе — так выглядит зависший сервер.
                }
            }
        }

        @Override
        public void assignRestaurantManager(
                AssignRestaurantManagerRequest request,
                StreamObserver<AssignRestaurantManagerResponse> responseObserver
        ) {
            if (behaviour == Behaviour.UNAVAILABLE) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }

            lastAssignment = request.getUserId() + ":" + request.getRestaurantId();
            responseObserver.onNext(AssignRestaurantManagerResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
