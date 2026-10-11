package com.dima.fooddelivery.order.integration;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.order.api.PayOrderRequest;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.PaymentMethod;
import com.dima.fooddelivery.order.saga.PaymentSaga;
import com.dima.fooddelivery.order.service.OutboxPublisher;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.TestDataFactory;
import com.dima.fooddelivery.support.TestPayments;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сага через настоящий брокер: команда уходит в топик команд, исход приходит из топика
 * событий и двигает заказ.
 *
 * <p>Сервиса платежей здесь нет — его роль играет тест: читает команду и пишет ответ ровно
 * в той форме, в какой его пишет payment-service. Совпадение формы гарантирует общий контракт
 * из {@code event-contracts}.
 *
 * <p>Без {@code @Transactional} по той же причине, что и {@link OrderOutboxKafkaIT}: публикатор
 * и слушатель видят только зафиксированные данные.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentSagaKafkaIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private PaymentSaga paymentSaga;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfluentKafkaContainer kafkaContainer;

    @Test
    void shouldSendPaymentCommandAndApplyPaymentOutcomeFromKafka() throws Exception {
        Long customerId = testData.insertCustomer();
        TestDataFactory.OrderContext order = testData.insertOrderInStatus(customerId, OrderStatus.CREATED);
        String key = String.valueOf(order.orderId());

        try (KafkaConsumer<String, String> commands = createConsumer(KafkaTopics.PAYMENT_COMMANDS)) {
            paymentSaga.requestPayment(
                    customerId,
                    order.orderId(),
                    new PayOrderRequest(PaymentMethod.CARD, UUID.randomUUID().toString())
            );

            outboxPublisher.publishBatch();

            ConsumerRecord<String, String> command = awaitRecord(commands, key);

            assertTrue(command.value().contains("\"commandType\":\"PAYMENT_REQUESTED\""));
        }

        PaymentEvent succeeded = TestPayments.event(PaymentEvent.PAYMENT_SUCCEEDED, order.orderId(), null);
        kafkaTemplate.send(new ProducerRecord<>(
                KafkaTopics.PAYMENT_EVENTS,
                key,
                objectMapper.writeValueAsString(succeeded)
        )).get();

        awaitOrderStatus(order.orderId(), OrderStatus.PAID);

        assertAll(
                () -> assertEquals(OrderStatus.PAID.getDbValue(), testData.currentOrderStatus(order.orderId())),
                () -> assertEquals(1, testData.countOrderEvents(order.orderId(), "ORDER_PAID"))
        );
    }

    private void awaitOrderStatus(Long orderId, OrderStatus expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            if (expected.getDbValue().equals(testData.currentOrderStatus(orderId))) {
                return;
            }

            Thread.sleep(200);
        }

        throw new AssertionError("Заказ " + orderId + " не перешёл в " + expected + " за " + TIMEOUT);
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "saga-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofSeconds(2));

        return consumer;
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Не дождались сообщения с ключом " + key + " за " + TIMEOUT);
    }
}
