package com.dima.fooddelivery.order.saga;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Читает исходы платежей и передаёт их саге.
 *
 * <p>Здесь только транспорт: разбор JSON и метка запроса. Решения принимает {@link PaymentSaga}.
 * Неразбираемое сообщение и сбой обработки уходят наружу исключением — их ловит обработчик
 * ошибок из {@code KafkaConfig}: три повтора, затем DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventsConsumer {

    private final PaymentSaga paymentSaga;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.PAYMENT_EVENTS, groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        String correlationId = correlationIdOf(record);

        // Новый поток без своего MDC: без восстановления метки реакция саги выпала бы
        // из цепочки запроса, который её в конечном счёте породил.
        if (correlationId != null) {
            MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
        }

        try {
            PaymentEvent event = objectMapper.readValue(record.value(), PaymentEvent.class);

            log.debug("Исход платежа получен: orderId={}, type={}", event.orderId(), event.eventType());

            paymentSaga.handle(event, record.topic());
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }

    private String correlationIdOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(RequestContext.CORRELATION_ID_HEADER);

        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }
}
