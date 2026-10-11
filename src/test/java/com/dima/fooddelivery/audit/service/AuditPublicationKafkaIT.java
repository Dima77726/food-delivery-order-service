package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.audit.domain.AuditOutcome;
import com.dima.fooddelivery.audit.persistence.AuditLogRepository;
import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.audit.AuditEvent;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Журнал аудита как outbox: запись → топик сводного журнала → отметка {@code published_at}.
 *
 * <p>Без транзакции теста по той же причине, что и {@code OrderOutboxKafkaIT}: публикатор
 * видит только зафиксированные записи. В базе при этом лежат неотправленные записи других
 * тестов, поэтому публикация гоняется до опустошения очереди, а нужное сообщение ищется по ключу.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditPublicationKafkaIT extends AbstractIntegrationTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditPublisher auditPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfluentKafkaContainer kafkaContainer;

    @Test
    void shouldPublishEntryKeyedByResourceAndMarkIt() {
        String resourceId = "audit-" + testData.uniqueSuffix();
        String correlationId = "trace-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> consumer = createConsumer()) {
            Long id = auditLogRepository.append(
                    null, null, "ORDER_CANCELED", "ORDER", resourceId,
                    AuditOutcome.SUCCESS, "Отменён по таймауту оплаты", correlationId
            );

            drainQueue();

            ConsumerRecord<String, String> record = awaitRecord(consumer, "ORDER:" + resourceId);
            AuditEvent event = objectMapper.readValue(record.value(), AuditEvent.class);
            Header header = record.headers().lastHeader(RequestContext.CORRELATION_ID_HEADER);

            assertAll(
                    () -> assertEquals(AuditEvent.eventIdOf("order-service", id), event.eventId(),
                            "eventId обязан выводиться из номера записи — иначе повтор не узнать"),
                    () -> assertEquals("order-service", event.sourceService()),
                    () -> assertEquals(id, event.sourceId()),
                    () -> assertNull(event.actorId(), "системное действие остаётся без автора"),
                    () -> assertEquals("ORDER_CANCELED", event.action()),
                    () -> assertEquals("SUCCESS", event.outcome()),
                    () -> assertEquals(correlationId, event.correlationId()),
                    () -> assertNotNull(event.occurredAt()),
                    () -> assertNotNull(header, "метка запроса едет и заголовком"),
                    () -> assertEquals(correlationId, new String(header.value(), StandardCharsets.UTF_8)),
                    () -> assertNotNull(publishedAt(id), "подтверждённая запись обязана получить отметку")
            );
        }
    }

    /** Отмеченная запись в следующую пачку не попадает — иначе журнал уезжал бы по кругу. */
    @Test
    void shouldNotPickUpPublishedEntryAgain() {
        Long id = auditLogRepository.append(
                null, null, "ORDER_PAID", "ORDER", "audit-" + testData.uniqueSuffix(),
                AuditOutcome.SUCCESS, null, null
        );

        drainQueue();

        Object firstMark = publishedAt(id);
        drainQueue();

        assertAll(
                () -> assertNotNull(firstMark),
                () -> assertEquals(firstMark, publishedAt(id), "повторная отметка не должна сдвигать время")
        );
    }

    /** Пачки ограничены, а в базе лежат записи других тестов: гоняем до пустой очереди. */
    private void drainQueue() {
        for (int round = 0; round < 50; round++) {
            if (auditPublisher.publishBatch() == 0) {
                return;
            }
        }
    }

    private Object publishedAt(Long id) {
        return jdbcTemplate.queryForObject("SELECT published_at FROM audit_log WHERE id = ?", Object.class, id);
    }

    private KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(KafkaTopics.AUDIT_EVENTS));
        consumer.poll(Duration.ofSeconds(2));

        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Не дождались записи журнала с ключом " + key + " за " + DELIVERY_TIMEOUT);
    }
}
