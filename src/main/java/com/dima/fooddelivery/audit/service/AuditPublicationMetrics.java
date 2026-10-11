package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.audit.persistence.AuditLogRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Сколько записей журнала ещё не уехало в сводный журнал.
 *
 * <p>Отправка ломается так же тихо, как outbox заказов (см. {@code OutboxMetrics}): журнал
 * пишется, действия идут, а сводный журнал в insights-service просто перестаёт пополняться.
 * Тревожит не ненулевое значение, а рост: очередь, которая не убывает, значит отправка встала.
 *
 * <p>Имя метрики общее с identity-service, различает их тег {@code application}.
 */
@Slf4j
@Component
public class AuditPublicationMetrics {

    private final AuditLogRepository auditLogRepository;

    public AuditPublicationMetrics(MeterRegistry registry, AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;

        Gauge.builder("food_delivery.audit.backlog", this, AuditPublicationMetrics::countQuietly)
                .description("Записей журнала аудита ожидает отправки в сводный журнал")
                .register(registry);
    }

    /** NaN вместо нуля при недоступной базе — по той же причине, что в {@code OutboxMetrics}. */
    private double countQuietly() {
        try {
            return auditLogRepository.countUnpublished();
        } catch (RuntimeException exception) {
            log.warn("Не удалось посчитать неотправленный журнал аудита: {}", exception.getMessage());

            return Double.NaN;
        }
    }
}
