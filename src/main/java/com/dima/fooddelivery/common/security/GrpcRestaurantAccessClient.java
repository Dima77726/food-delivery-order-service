package com.dima.fooddelivery.common.security;

import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.identity.v1.AssignRestaurantManagerRequest;
import com.dima.fooddelivery.grpc.identity.v1.CheckRestaurantAccessRequest;
import com.dima.fooddelivery.grpc.identity.v1.RestaurantAccessServiceGrpc;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * {@link RestaurantAccessClient} поверх gRPC. Сервер — identity-service, тот же, у кого
 * спрашивает каталог.
 *
 * <p><b>Дедлайн на каждый вызов обязателен.</b> По умолчанию у gRPC-вызова его нет вовсе:
 * зависший сервер держал бы поток запроса бесконечно, а за ним и пользователя. Дедлайн
 * короткий, потому что вызов стоит на пути каждого запроса ресторана к своим заказам и
 * отвечает быстро всегда, кроме случаев, когда не ответит уже никогда.
 *
 * <p>Ошибка транспорта превращается в {@link UpstreamUnavailableException}, то есть в 503.
 * Повторов здесь нет намеренно: вызов синхронный, пользователь ждёт, а повтор при
 * недоступном сервере только удвоил бы ожидание перед тем же отказом.
 */
@Slf4j
@Component
public class GrpcRestaurantAccessClient implements RestaurantAccessClient {

    private final RestaurantAccessServiceGrpc.RestaurantAccessServiceBlockingStub stub;
    private final Duration deadline;

    @Autowired
    public GrpcRestaurantAccessClient(
            GrpcChannelFactory channels,
            @Value("${app.grpc.identity.deadline:PT2S}") Duration deadline
    ) {
        // Имя канала — ключ в spring.grpc.client.channels: адрес живёт в конфигурации.
        this(channels.createChannel("identity"), deadline);
    }

    /** Для тестов: канал к серверу в памяти процесса вместо настоящего адреса. */
    GrpcRestaurantAccessClient(Channel channel, Duration deadline) {
        this.stub = RestaurantAccessServiceGrpc.newBlockingStub(channel);
        this.deadline = deadline;
    }

    @Override
    public boolean managesRestaurant(long userId, long restaurantId) {
        try {
            return withDeadline().checkRestaurantAccess(
                    CheckRestaurantAccessRequest.newBuilder()
                            .setUserId(userId)
                            .setRestaurantId(restaurantId)
                            .build()
            ).getManages();
        } catch (StatusRuntimeException exception) {
            throw unavailable("проверить права на ресторан", exception);
        }
    }

    @Override
    public void assignManager(long userId, long restaurantId) {
        try {
            withDeadline().assignRestaurantManager(
                    AssignRestaurantManagerRequest.newBuilder()
                            .setUserId(userId)
                            .setRestaurantId(restaurantId)
                            .build()
            );
        } catch (StatusRuntimeException exception) {
            throw unavailable("назначить управляющего рестораном", exception);
        }
    }

    /** Дедлайн отсчитывается от момента вызова, поэтому стаб с ним создаётся на каждый вызов. */
    private RestaurantAccessServiceGrpc.RestaurantAccessServiceBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private UpstreamUnavailableException unavailable(String action, StatusRuntimeException exception) {
        log.error("Не удалось {}: сервис пользователей ответил {}", action, exception.getStatus());

        return new UpstreamUnavailableException(
                "Сервис пользователей недоступен, не удалось " + action + ". Повторите позже",
                exception
        );
    }
}
