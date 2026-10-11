package com.dima.fooddelivery.order.integration;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.persistence.OutboxRepository;
import com.dima.fooddelivery.order.service.OrderStatusService;
import com.dima.fooddelivery.order.service.OutboxPublisher;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Путь события на стороне продюсера: смена статуса заказа → outbox → топик Kafka.
 *
 * <p>Дальше топика сервис заказов не смотрит, и это граница ответственности, а не упрощение.
 * Уведомления живут в notification-service, и их проверки — идемпотентность, DLT,
 * перенос метки в базу — переехали туда вместе с потребителем. Здесь проверяется, что
 * сервис заказов выполняет свою половину контракта: событие публикуется, с ключом заказа
 * и с меткой запроса в заголовке.
 *
 * <p><b>Почему без {@code @Transactional}.</b> Все остальные интеграционные тесты откатывают
 * транзакцию после себя, и это удобно. Здесь так нельзя: смысл outbox в том, что событие
 * становится видимым публикатору только после коммита. В откатываемой транзакции запись
 * никогда не была бы зафиксирована, публикатор её не увидел бы, и тест проверял бы пустоту.
 *
 * <p>Плата — данные остаются в базе после теста. Для проверки, которая доказывает
 * согласованность записи и публикации, это честная цена.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderOutboxKafkaIT extends AbstractIntegrationTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Адрес брокера берётся у контейнера, а не из {@code spring.kafka.bootstrap-servers}.
     *
     * <p>{@code @ServiceConnection} не переписывает свойства окружения: он отдаёт
     * автоконфигурации бин {@code KafkaConnectionDetails}, а в {@code application.yml}
     * остаётся значение по умолчанию — {@code localhost:9094}. Бины Spring подключаются
     * куда надо, а вот собранный вручную потребитель по этому свойству ушёл бы в пустоту
     * и молча ничего не дождался.
     */
    @Autowired
    private ConfluentKafkaContainer kafkaContainer;

    @Test
    void shouldWriteEventToOutboxInSameTransactionAsStatusChange() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAYMENT_PENDING);

        markPaid(order.orderId());

        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_event_outbox WHERE aggregate_id = ? AND status = 'PENDING'",
                Integer.class,
                order.orderId()
        );

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM order_event_outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                String.class,
                order.orderId()
        );

        assertAll(
                () -> assertEquals(1, outboxRows, "смена статуса обязана оставить запись в outbox"),
                () -> assertTrue(payload.contains("\"newStatus\":\"PAID\"")),
                () -> assertTrue(payload.contains("\"schemaVersion\":1"), "версия схемы должна попадать в контракт")
        );
    }

    /**
     * Публикация вызывается вручную, а сообщение читается из топика отдельным потребителем:
     * так проверяется то, что увидит любой подписчик, а не внутреннее состояние продюсера.
     *
     * <p>Ключ — идентификатор заказа. Только он держит события одного заказа в одной
     * партиции, то есть в порядке; без ключа «доставлен» мог бы обогнать «оплачен».
     */
    @Test
    void shouldPublishEventToTopicKeyedByOrderId() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAYMENT_PENDING);
        String key = String.valueOf(order.orderId());

        try (KafkaConsumer<String, String> consumer = createConsumer(KafkaTopics.ORDER_EVENTS)) {
            markPaid(order.orderId());

            int published = outboxPublisher.publishBatch();
            assertTrue(published > 0, "публикатор должен был отправить событие");

            ConsumerRecord<String, String> record = awaitRecord(consumer, key);

            assertAll(
                    () -> assertTrue(record.value().contains("\"newStatus\":\"PAID\"")),
                    () -> assertTrue(record.value().contains("\"customerId\":" + customerId))
            );
        }
    }

    @Test
    void shouldMarkOutboxRecordPublishedAfterSuccessfulSend() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAYMENT_PENDING);

        markPaid(order.orderId());
        outboxPublisher.publishBatch();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM order_event_outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                String.class,
                order.orderId()
        );

        assertAll(
                () -> assertEquals("PUBLISHED", status),
                () -> assertFalse(
                        outboxRepository.lockPending(10).stream()
                                .anyMatch(record -> record.aggregateId().equals(order.orderId())),
                        "опубликованное событие не должно попадать в следующую выборку"
                )
        );
    }

    /**
     * Метка запроса обязана пережить асинхронную границу.
     *
     * <p>Проверяется половина цепочки, за которую отвечает сервис заказов: MDC потока запроса,
     * колонка в outbox и заголовок сообщения Kafka. Вторую половину — заголовок → колонка
     * в уведомлении — проверяет notification-service.
     */
    @Test
    void shouldCarryCorrelationIdFromRequestThreadToKafkaHeader() {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.PAYMENT_PENDING);

        String correlationId = "trace-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> consumer = createConsumer(KafkaTopics.ORDER_EVENTS)) {
            // Смена статуса выполняется так, будто её вызвал HTTP-запрос: именно из MDC
            // слушатель outbox и берёт метку.
            MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
            try {
                markPaid(order.orderId());
            } finally {
                MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
            }

            String inOutbox = jdbcTemplate.queryForObject(
                    "SELECT correlation_id FROM order_event_outbox WHERE aggregate_id = ? ORDER BY id LIMIT 1",
                    String.class,
                    order.orderId()
            );

            outboxPublisher.publishBatch();

            ConsumerRecord<String, String> record = awaitRecord(consumer, String.valueOf(order.orderId()));
            Header header = record.headers().lastHeader(RequestContext.CORRELATION_ID_HEADER);

            assertAll(
                    () -> assertEquals(correlationId, inOutbox, "метка обязана лечь в outbox вместе с событием"),
                    () -> assertNotNull(header, "метка обязана уехать заголовком сообщения"),
                    () -> assertEquals(correlationId, new String(header.value(), StandardCharsets.UTF_8))
            );
        }
    }

    /** Перевод, который в бою делает сага, получив исход платежа. */
    private void markPaid(Long orderId) {
        orderStatusService.transition(
                orderId,
                OrderStatus.PAYMENT_PENDING,
                OrderStatus.PAID,
                OrderEventType.ORDER_PAID,
                "Оплата прошла"
        );
    }

    /**
     * Своя группа, чтобы чтение теста не сдвигало ничьих офсетов, и {@code earliest} —
     * сообщение может оказаться в топике раньше, чем потребитель получит партиции.
     * Первый poll назначает партиции: до него подписка ещё не вступила в силу.
     */
    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofSeconds(2));

        return consumer;
    }

    /**
     * В топике лежат события и других тестов, поэтому нужное ищется по ключу. Ключ —
     * идентификатор заказа, а заказ у каждого теста свой.
     */
    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Не дождались сообщения с ключом " + key + " за " + DELIVERY_TIMEOUT);
    }
}
