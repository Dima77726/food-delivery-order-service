package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import com.dima.fooddelivery.contracts.order.OrderIntegrationEvent;
import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.order.persistence.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Кладёт событие заказа в outbox.
 *
 * <p>{@code @EventListener}, а не {@code @TransactionalEventListener(AFTER_COMMIT)} — и это
 * принципиально. Слушатель обязан отработать <b>внутри</b> транзакции, которая меняет статус
 * заказа: только тогда запись в outbox и сам заказ фиксируются вместе. Сработай он после
 * коммита — вернулась бы ровно та проблема двойной записи, ради которой outbox и заводили.
 *
 * <p>Здесь же происходит перевод внутреннего события во внешний контракт. Разделение не
 * бюрократия: {@link OrderStatusChangedEvent} можно менять как угодно, потому что он живёт
 * внутри процесса, а {@link OrderIntegrationEvent} читают чужие потребители, и его форма
 * меняется по правилам совместимости.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxListener {

    static final String AGGREGATE_TYPE = "ORDER";

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        UUID eventId = UUID.randomUUID();

        OrderIntegrationEvent integrationEvent = new OrderIntegrationEvent(
                eventId,
                OrderIntegrationEvent.CURRENT_SCHEMA_VERSION,
                event.eventType().getDbValue(),
                event.orderId(),
                event.customerId(),
                event.restaurantId(),
                event.previousStatus().getDbValue(),
                event.newStatus().getDbValue(),
                event.totalAmount(),
                OffsetDateTime.now()
        );

        // Сериализация здесь, а не в публикаторе: в outbox должно лечь ровно то, что уйдёт
        // в Kafka. Если собирать тело позже, оно будет зависеть от версии кода публикатора,
        // а не от того, что произошло в момент события.
        String payload = objectMapper.writeValueAsString(integrationEvent);

        // Слушатель работает в потоке запроса, поэтому метка ещё доступна в MDC.
        // Дальше её нужно нести явно: планировщик публикации и потребитель живут
        // в других потоках, куда ThreadLocal не дотягивается.
        outboxRepository.append(
                eventId,
                KafkaTopics.ORDER_EVENTS,
                AGGREGATE_TYPE,
                event.orderId(),
                event.eventType().getDbValue(),
                payload,
                RequestContext.correlationId().orElse(null)
        );

        log.debug("Событие заказа помещено в outbox: orderId={}, eventId={}", event.orderId(), eventId);
    }
}
