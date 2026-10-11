package com.dima.fooddelivery.audit.api;

import com.dima.fooddelivery.audit.domain.AuditEntry;

import java.util.List;

public final class AuditResponseMapper {

    public static AuditEntryResponse toResponse(AuditEntry entry) {
        return new AuditEntryResponse(
                entry.id(),
                entry.actorId(),
                entry.actorEmail(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome(),
                entry.details(),
                entry.createdAt(),
                entry.correlationId()
        );
    }

    public static List<AuditEntryResponse> toResponses(List<AuditEntry> entries) {
        return entries.stream().map(AuditResponseMapper::toResponse).toList();
    }

    private AuditResponseMapper() {
    }
}
