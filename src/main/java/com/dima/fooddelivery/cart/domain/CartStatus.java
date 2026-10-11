package com.dima.fooddelivery.cart.domain;

/**
 * Статусы корзины. Раньше эти значения жили в коде строковыми литералами
 * ({@code status = 'ACTIVE'}) — опечатка в таком литерале не ловится ни компилятором,
 * ни тестом, только пустым результатом запроса в проде.
 */
public enum CartStatus {

    ACTIVE("ACTIVE"),
    CHECKED_OUT("CHECKED_OUT"),
    CANCELED("CANCELED"),
    ABANDONED("ABANDONED");

    private final String dbValue;

    CartStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static CartStatus fromDbValue(String dbValue) {
        for (CartStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Неизвестный статус корзины из базы данных: " + dbValue);
    }
}
