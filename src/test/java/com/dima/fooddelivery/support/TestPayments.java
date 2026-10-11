package com.dima.fooddelivery.support;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.order.saga.PaymentSaga;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ответы сервиса платежей — без сервиса платежей и без брокера.
 *
 * <p>Сага получает ровно то, что пришло бы из топика: {@link PaymentEvent} с новым
 * идентификатором. Так тесты сервиса заказов проверяют свою половину саги, не поднимая
 * вторую. Путь через настоящий Kafka проверяет {@code PaymentSagaKafkaIT}.
 */
public class TestPayments {

    private static final AtomicLong PAYMENT_IDS = new AtomicLong(5_000_000L);

    private final PaymentSaga paymentSaga;

    public TestPayments(PaymentSaga paymentSaga) {
        this.paymentSaga = paymentSaga;
    }

    public PaymentEvent succeeded(Long orderId) {
        return deliver(event(PaymentEvent.PAYMENT_SUCCEEDED, orderId, null));
    }

    public PaymentEvent failed(Long orderId, String reason) {
        return deliver(event(PaymentEvent.PAYMENT_FAILED, orderId, reason));
    }

    public PaymentEvent refunded(Long orderId) {
        return deliver(event(PaymentEvent.PAYMENT_REFUNDED, orderId, null));
    }

    /** Повторная доставка того же сообщения — как после сбоя между обработкой и коммитом офсета. */
    public void redeliver(PaymentEvent event) {
        deliver(event);
    }

    public static PaymentEvent event(String type, Long orderId, String failureReason) {
        return new PaymentEvent(
                UUID.randomUUID(),
                PaymentEvent.CURRENT_SCHEMA_VERSION,
                type,
                orderId,
                PAYMENT_IDS.incrementAndGet(),
                1L,
                new BigDecimal("450.00"),
                "CARD",
                failureReason,
                OffsetDateTime.now()
        );
    }

    private PaymentEvent deliver(PaymentEvent event) {
        paymentSaga.handle(event, KafkaTopics.PAYMENT_EVENTS);

        return event;
    }
}
