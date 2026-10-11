package com.dima.fooddelivery.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Запускает отправку журнала аудита по расписанию.
 *
 * <p>Отдельно от {@link AuditPublisher} по тем же двум причинам, что и {@code OutboxPublishJob}:
 * вызов через прокси, без которого {@code SKIP LOCKED} ничего не заблокировал бы, и выключатель,
 * который гасит только таймер — тесты вызывают публикацию сами.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.audit.publish-enabled", havingValue = "true", matchIfMissing = true)
public class AuditPublishJob {

    private final AuditPublisher auditPublisher;

    @Scheduled(fixedDelayString = "${app.audit.publish-interval:PT5S}")
    public void publish() {
        int published = auditPublisher.publishBatch();

        if (published > 0) {
            log.debug("Отправлено в сводный журнал аудита: {}", published);
        }
    }
}
