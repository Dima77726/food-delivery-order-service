package com.dima.fooddelivery.audit.domain;

import java.time.OffsetDateTime;

public record AuditEntry(
        Long id,
        Long actorId,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        AuditOutcome outcome,
        String details,
        OffsetDateTime createdAt,

        /** Метка запроса, породившего запись; NULL для действий вне HTTP-запроса. */
        String correlationId
) {
}
