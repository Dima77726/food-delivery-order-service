package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.audit.domain.AuditEntry;
import com.dima.fooddelivery.audit.persistence.AuditLogRepository;
import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.audit.AuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Отправляет записи журнала аудита в сводный журнал — топик, который читает insights-service.
 *
 * <p>Таблица журнала и есть outbox: запись ложится в транзакции действия, а отметка
 * {@code published_at} ставится, когда Kafka подтвердила приём. Доставка, как и у событий
 * заказа, «хотя бы один раз»: между подтверждением и отметкой процесс может умереть, и запись
 * уйдёт повторно. Потребитель узнаёт повтор по {@code eventId} — тот выводится из номера
 * записи и при повторной отправке не меняется.
 *
 * <p><b>Тело собирается при отправке, а не при записи.</b> У outbox заказов наоборот, и там
 * это важно: событие описывает момент, и собранное позже могло бы описать другой. Здесь
 * источник — сама строка журнала, а она после вставки не меняется. Из одних и тех же колонок
 * всегда получается одно и то же сообщение, и хранить его вторую копию готовым JSON незачем.
 *
 * <p><b>Пачка отправляется целиком, подтверждения ждутся потом.</b> Порядок внутри ресурса
 * держит ключ и идемпотентный продюсер, так что ждать каждую запись по очереди незачем.
 * Отмечаются только подтверждённые; остальные останутся без отметки и уйдут следующим тактом.
 * Перестановка при этом возможна, но безвредна: сводный журнал упорядочен по времени
 * действия, а не по времени прихода.
 *
 * <p>Счётчика попыток и статуса FAILED, как у outbox заказов, здесь нет. Тело записи собирает
 * наш собственный код из наших же колонок — «неотправляемой» записи взяться неоткуда, и
 * неудача отправки означает недоступный брокер. Это видно по метрике очереди.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditPublisher {

    /**
     * Имя источника в сводном журнале. Константа, а не {@code spring.application.name}: из неё
     * выводится {@code eventId}, и переименование приложения в конфиге не должно превращать
     * старые записи в новые.
     */
    static final String SOURCE_SERVICE = "order-service";

    /** Пачка небольшая: пока она в работе, строки журнала заблокированы. */
    private static final int BATCH_SIZE = 100;

    /** Сколько ждать подтверждений на всю пачку. Не дождались — следующий такт попробует снова. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final AuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Вся пачка в одной транзакции: блокировка {@code SKIP LOCKED} живёт до её конца,
     * и отметка ставится в ней же.
     *
     * @return сколько записей подтверждено и отмечено
     */
    @Transactional
    public int publishBatch() {
        List<AuditEntry> pending = auditLogRepository.lockUnpublished(BATCH_SIZE);

        if (pending.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(pending.size());
        for (AuditEntry entry : pending) {
            sends.add(kafkaTemplate.send(toProducerRecord(entry)));
        }

        List<Long> confirmed = awaitConfirmed(pending, sends);

        auditLogRepository.markPublished(confirmed);

        return confirmed.size();
    }

    private List<Long> awaitConfirmed(
            List<AuditEntry> pending,
            List<CompletableFuture<SendResult<String, String>>> sends
    ) {
        Instant deadline = Instant.now().plus(SEND_TIMEOUT);
        List<Long> confirmed = new ArrayList<>(pending.size());

        for (int i = 0; i < pending.size(); i++) {
            AuditEntry entry = pending.get(i);

            try {
                long remainingMillis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
                sends.get(i).get(remainingMillis, TimeUnit.MILLISECONDS);

                confirmed.add(entry.id());
            } catch (InterruptedException interrupted) {
                // Подтверждённое к этому моменту всё равно отмечается: иначе оно ушло бы
                // повторно, а повтор хоть и безвреден, но лишний.
                Thread.currentThread().interrupt();

                return confirmed;
            } catch (Exception exception) {
                log.warn(
                        "Запись журнала аудита не отправлена: auditId={}, correlationId={}, reason={}",
                        entry.id(),
                        entry.correlationId(),
                        exception.getMessage()
                );
            }
        }

        return confirmed;
    }

    /**
     * Ключ — ресурс: записи об одном заказе попадают в одну партицию и идут по порядку.
     * Метка запроса едет и в теле, и заголовком: в теле она часть записи журнала, по которой
     * администратор ищет, а заголовок читают, не разбирая сообщение, — как у событий заказа.
     */
    private ProducerRecord<String, String> toProducerRecord(AuditEntry entry) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopics.AUDIT_EVENTS,
                entry.resourceId() == null ? entry.resourceType() : entry.resourceType() + ":" + entry.resourceId(),
                objectMapper.writeValueAsString(toEvent(entry))
        );

        if (entry.correlationId() != null) {
            record.headers().add(
                    RequestContext.CORRELATION_ID_HEADER,
                    entry.correlationId().getBytes(StandardCharsets.UTF_8)
            );
        }

        return record;
    }

    private static AuditEvent toEvent(AuditEntry entry) {
        return new AuditEvent(
                AuditEvent.eventIdOf(SOURCE_SERVICE, entry.id()),
                AuditEvent.CURRENT_SCHEMA_VERSION,
                SOURCE_SERVICE,
                entry.id(),
                entry.actorId(),
                entry.actorEmail(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome().name(),
                entry.details(),
                entry.correlationId(),
                entry.createdAt()
        );
    }
}
