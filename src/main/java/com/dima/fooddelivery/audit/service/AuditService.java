package com.dima.fooddelivery.audit.service;

import com.dima.fooddelivery.audit.domain.AuditEntry;
import com.dima.fooddelivery.audit.domain.AuditOutcome;
import com.dima.fooddelivery.audit.persistence.AuditLogRepository;
import com.dima.fooddelivery.common.security.CurrentUser;
import com.dima.fooddelivery.common.web.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Модуль Audit.
 *
 * <p>Записывает, кто и что сделал. Актора берёт из контекста безопасности сам, чтобы
 * вызывающий код не передавал его руками — переданный вручную идентификатор рано или поздно
 * оказывается не тем.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUser currentUser;

    @Transactional
    public void record(
            String action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome,
            String details
    ) {
        Long actorId = currentUser.id().orElse(null);

        // E-mail сохраняется на момент действия: пользователя могут переименовать или удалить,
        // а запись обязана остаться читаемой. Берётся из токена — пользователи живут в другом
        // сервисе, и спрашивать его на каждую запись журнала значило бы добавить сетевой вызов
        // на пути каждого заказа.
        String actorEmail = actorId == null ? null : currentUser.email().orElse(null);

        // Метка берётся из контекста, а не из аргументов — по той же причине, что и актор:
        // переданная вручную, она рано или поздно окажется не от того запроса.
        String correlationId = RequestContext.correlationId().orElse(null);

        auditLogRepository.append(
                actorId, actorEmail, action, resourceType, resourceId, outcome, details, correlationId
        );
    }

    public void recordSuccess(String action, String resourceType, String resourceId, String details) {
        record(action, resourceType, resourceId, AuditOutcome.SUCCESS, details);
    }

    public void recordFailure(String action, String resourceType, String resourceId, String details) {
        record(action, resourceType, resourceId, AuditOutcome.FAILURE, details);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> getRecent(int limit) {
        return auditLogRepository.findRecent(limit);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> getByActor(Long actorId, int limit) {
        return auditLogRepository.findByActor(actorId, limit);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> getByResource(String resourceType, String resourceId, int limit) {
        return auditLogRepository.findByResource(resourceType, resourceId, limit);
    }
}
