package com.dima.fooddelivery.delivery.domain;

public enum DeliveryStatus {

    CREATED("CREATED"),

    ASSIGNED("ASSIGNED"),

    PICKED_UP("PICKED_UP"),

    DELIVERED("DELIVERED"),

    CANCELED("CANCELED");

    private final String dbValue;

    DeliveryStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public boolean canBeAssignedToCourier() {
        return this == CREATED;
    }

    public boolean canBePickedUpByCourier() {
        return this == ASSIGNED;
    }

    public boolean canBeDeliveredByCourier() {
        return this == PICKED_UP;
    }

    public static DeliveryStatus fromDbValue(String dbValue) {
        for (DeliveryStatus status : DeliveryStatus.values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown delivery status from database: " + dbValue);
    }
}
