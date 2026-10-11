package com.dima.fooddelivery.order.service;

import com.dima.fooddelivery.order.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Наблюдаемость очереди публикации.
 *
 * <p>Outbox ломается тихо. Приложение отвечает на запросы, заказы меняют статусы, ошибок в логе
 * нет — а события не уезжают: упал брокер, или планировщик не запустился, или сообщение
 * не сериализуется. Снаружи всё выглядит здоровым, и узнаётся об этом от потребителя,
 * который перестал получать события.
 *
 * <p>Отсюда два числа, и оба нужны:
 * <ul>
 *   <li>{@code status=PENDING} — сколько ещё не отправлено. Само по себе ненулевое значение
 *       нормально, тревожит его <b>рост</b>: очередь, которая не убывает, значит публикация
 *       встала.</li>
 *   <li>{@code status=FAILED} — сколько исчерпало попытки. Любое ненулевое значение требует
 *       человека: эти события не уйдут сами.</li>
 * </ul>
 *
 * <p>Счётчики здесь не годятся — очередь может как расти, так и убывать, а счётчик Prometheus
 * умеет только расти. Поэтому измеритель: он спрашивает текущее состояние в момент опроса.
 *
 * <p>Цена — два запроса к базе на каждый скрейп. Оба закрыты частичными индексами и читают
 * только то, что не опубликовано, поэтому от размера таблицы не зависят.
 */
@Slf4j
@Component
public class OutboxMetrics {

    private static final String OUTBOX_BACKLOG = "food_delivery.outbox.backlog";

    private final OutboxRepository outboxRepository;

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;

        registerBacklogGauge(registry, "PENDING", "Событий заказа ожидает публикации в Kafka");
        registerBacklogGauge(registry, "FAILED", "Событий заказа исчерпало попытки публикации");
    }

    private void registerBacklogGauge(MeterRegistry registry, String status, String description) {
        Gauge.builder(OUTBOX_BACKLOG, this, metrics -> metrics.countQuietly(status))
                .description(description)
                .tag("status", status)
                .register(registry);
    }

    /**
     * Недоступная база не должна ронять отдачу метрик: как раз в такой момент остальные
     * показатели нужнее всего. NaN Prometheus просто пропустит, и ряд прервётся —
     * это честнее нуля, который выглядит как «очередь пуста».
     */
    private double countQuietly(String status) {
        try {
            return outboxRepository.countByStatus(status);
        } catch (RuntimeException exception) {
            log.warn("Не удалось посчитать очередь outbox: status={}, reason={}", status, exception.getMessage());

            return Double.NaN;
        }
    }
}
