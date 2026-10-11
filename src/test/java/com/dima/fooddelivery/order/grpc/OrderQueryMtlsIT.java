package com.dima.fooddelivery.order.grpc;

import com.dima.fooddelivery.grpc.order.v1.GetOrderRequest;
import com.dima.fooddelivery.grpc.order.v1.OrderQueryServiceGrpc;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * gRPC-сервер заказов под взаимным TLS — так, как он работает в compose.
 *
 * <p>Монолит ({@code CN=core}) и сервис трека допущены: ответ на несуществующий заказ —
 * NOT_FOUND, то есть запрос дошёл до кода сервиса. Посторонний с подлинным сертификатом того же CA получает
 * PERMISSION_DENIED. Сертификаты — тестовые, из {@code src/test/resources/grpc-tls}.
 */
@ActiveProfiles("grpc-tls")
@TestPropertySource(properties = {
        "GRPC_TLS_CERTIFICATE=classpath:grpc-tls/order-service.crt",
        "GRPC_TLS_PRIVATE_KEY=classpath:grpc-tls/order-service.key",
        "GRPC_TLS_CA=classpath:grpc-tls/ca.crt"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderQueryMtlsIT extends AbstractIntegrationTest {

    @LocalGrpcPort
    private int grpcPort;

    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void closeChannels() {
        channels.forEach(ManagedChannel::shutdownNow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "tracking-service"})
    void shouldServeAllowedServices(String service) {
        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> ordersAs(service).getOrder(unknownOrder())
        );

        assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode(), "запрос обязан дойти до сервиса");
    }

    @Test
    void shouldDenyServiceThatIsNotAllowed() {
        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> ordersAs("intruder").getOrder(unknownOrder())
        );

        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
    }

    private OrderQueryServiceGrpc.OrderQueryServiceBlockingStub ordersAs(String service) throws IOException {
        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", grpcPort)
                .sslContext(GrpcSslContexts.forClient()
                        .keyManager(resource(service + ".crt"), resource(service + ".key"))
                        .trustManager(resource("ca.crt"))
                        .build())
                .build();
        channels.add(channel);

        return OrderQueryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private InputStream resource(String name) throws IOException {
        return new ClassPathResource("grpc-tls/" + name).getInputStream();
    }

    private static GetOrderRequest unknownOrder() {
        return GetOrderRequest.newBuilder().setOrderId(Long.MAX_VALUE).build();
    }
}
