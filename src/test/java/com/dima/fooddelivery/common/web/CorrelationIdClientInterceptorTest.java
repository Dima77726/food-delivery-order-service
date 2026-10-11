package com.dima.fooddelivery.common.web;

import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.RestaurantSummary;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Метка запроса уезжает в метаданные исходящего gRPC-вызова — и только если она есть.
 */
class CorrelationIdClientInterceptorTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void shutdown() {
        MDC.clear();
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void shouldPutCorrelationIdFromMdcIntoMetadata() throws IOException {
        AtomicReference<String> received = startServerCapturingCorrelationId();

        MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, "trace-42");
        call();

        assertEquals("trace-42", received.get());
    }

    /** Фоновая задача без метки не выдумывает её: пустое место честнее чужой метки. */
    @Test
    void shouldNotSendCorrelationIdWhenThereIsNone() throws IOException {
        AtomicReference<String> received = startServerCapturingCorrelationId();

        call();

        assertNull(received.get());
    }

    private void call() {
        CatalogServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, new CorrelationIdClientInterceptor()))
                .getActiveRestaurant(GetActiveRestaurantRequest.newBuilder().setRestaurantId(1).build());
    }

    private AtomicReference<String> startServerCapturingCorrelationId() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        String name = InProcessServerBuilder.generateName();

        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next
            ) {
                received.set(headers.get(CorrelationIdClientInterceptor.CORRELATION_ID));
                return next.startCall(call, headers);
            }
        };

        CatalogServiceGrpc.CatalogServiceImplBase catalog = new CatalogServiceGrpc.CatalogServiceImplBase() {
            @Override
            public void getActiveRestaurant(GetActiveRestaurantRequest request, StreamObserver<RestaurantSummary> observer) {
                observer.onNext(RestaurantSummary.newBuilder().setId(request.getRestaurantId()).build());
                observer.onCompleted();
            }
        };

        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(catalog, capture))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        return received;
    }
}
