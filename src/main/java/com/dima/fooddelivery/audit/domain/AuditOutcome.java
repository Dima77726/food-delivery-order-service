package com.dima.fooddelivery.audit.domain;

public enum AuditOutcome {

    SUCCESS, FAILURE;

    public static AuditOutcome fromDbValue(String dbValue) {
        for (AuditOutcome outcome : values()) {
            if (outcome.name().equals(dbValue)) {
                return outcome;
            }
        }

        throw new IllegalArgumentException("Неизвестный исход в журнале аудита: " + dbValue);
    }
}
