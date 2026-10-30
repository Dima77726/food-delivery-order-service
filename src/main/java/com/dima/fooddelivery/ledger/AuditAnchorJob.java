package com.dima.fooddelivery.ledger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Заверяет журнал аудита по расписанию. Один экземпляр за раз (ShedLock): два экземпляра
 * посчитали бы одну пачку, и второй получил бы отказ контракта «overlap».
 *
 * <p>Недоступный узел — не авария: записи никуда не денутся и уйдут следующей пачкой.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ledger.enabled", havingValue = "true")
public class AuditAnchorJob {

    /** За один запуск — не больше стольких пачек: первая волна после включения догоняет историю. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final AuditAnchorService service;

    @Scheduled(fixedDelayString = "${app.ledger.anchor-interval:PT5M}", initialDelayString = "${app.ledger.initial-delay:PT30S}")
    @SchedulerLock(name = "audit-anchor", lockAtMostFor = "PT5M")
    public void anchor() {
        try {
            service.anchorPending(MAX_BATCHES_PER_RUN);
        } catch (LedgerException | RestClientException exception) {
            log.warn("Журнал аудита не заверен в этот раз, блокчейн недоступен: {}", exception.getMessage());
        }
    }
}
