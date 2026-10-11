package com.dima.fooddelivery.common.web;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Восстанавливает сквозную метку на входящем gRPC-вызове — так же, как {@code CorrelationIdFilter}
 * делает это для HTTP.
 *
 * <p><b>Почему MDC ставится в каждом обратном вызове, а не один раз.</b> gRPC обрабатывает вызов
 * событиями — пришло сообщение, клиент закончил отправку, вызов отменён, — и каждое событие
 * может выполниться в своём потоке из пула. MDC живёт в ThreadLocal: поставленный однажды,
 * он остался бы в чужом потоке и всплыл бы в логах следующего, никак не связанного вызова.
 * Поэтому метка ставится перед каждым событием и снимается сразу после.
 *
 * <p>Значение из метаданных очищается тем же правилом, что и HTTP-заголовок: оно приходит
 * от другого процесса и попадает в лог.
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdServerInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> CORRELATION_ID =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        String correlationId = sanitize(headers.get(CORRELATION_ID));

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            @Override
            public void onMessage(ReqT message) {
                withCorrelationId(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                // Для унарного вызова именно здесь выполняется метод сервиса.
                withCorrelationId(super::onHalfClose);
            }

            @Override
            public void onCancel() {
                withCorrelationId(super::onCancel);
            }

            @Override
            public void onComplete() {
                withCorrelationId(super::onComplete);
            }

            @Override
            public void onReady() {
                withCorrelationId(super::onReady);
            }

            private void withCorrelationId(Runnable action) {
                MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
                try {
                    action.run();
                } finally {
                    MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
                }
            }
        };
    }

    /** Без метки от клиента — своя: вызов без метки не должен оставлять в логе пустое место. */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String sanitized = raw.replaceAll("[^A-Za-z0-9\\-_]", "");

        if (sanitized.isEmpty()) {
            return UUID.randomUUID().toString();
        }

        return sanitized.length() > RequestContext.MAX_LENGTH
                ? sanitized.substring(0, RequestContext.MAX_LENGTH)
                : sanitized;
    }
}
