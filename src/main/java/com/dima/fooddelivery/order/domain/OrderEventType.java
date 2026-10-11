package com.dima.fooddelivery.order.domain;

/**
 * Типы событий в истории заказа.
 *
 * <p>Значения дублируются в check-констрейнте таблицы {@code customer_order_event}. Добавление
 * нового типа требует и правки этого enum, и миграции — база не должна принимать значение,
 * которое приложение не умеет прочитать обратно.
 */
public enum OrderEventType {

    ORDER_CREATED("ORDER_CREATED"),

    ORDER_PAYMENT_REQUESTED("ORDER_PAYMENT_REQUESTED"),

    ORDER_PAID("ORDER_PAID"),

    ORDER_PAYMENT_FAILED("ORDER_PAYMENT_FAILED"),

    ORDER_REFUNDED("ORDER_REFUNDED"),

    ORDER_CANCELED("ORDER_CANCELED"),

    ORDER_ACCEPTED("ORDER_ACCEPTED"),

    ORDER_COOKING_STARTED("ORDER_COOKING_STARTED"),

    ORDER_READY_FOR_DELIVERY("ORDER_READY_FOR_DELIVERY"),

    ORDER_PICKED_UP_BY_COURIER("ORDER_PICKED_UP_BY_COURIER"),

    ORDER_DELIVERED("ORDER_DELIVERED");

    private final String dbValue;

    OrderEventType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static OrderEventType fromDbValue(String dbValue) {
        for (OrderEventType orderEventType : values()) {
            if (orderEventType.dbValue.equals(dbValue)) {
                return orderEventType;
            }
        }

        throw new IllegalArgumentException("Неизвестный тип события заказа из базы данных: " + dbValue);
    }
}
