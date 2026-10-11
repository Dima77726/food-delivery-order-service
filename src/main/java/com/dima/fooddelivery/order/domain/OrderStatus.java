package com.dima.fooddelivery.order.domain;

/**
 * Статусы заказа и допустимые переходы.
 *
 * <pre>
 *   CREATED ──оплата──► PAYMENT_PENDING ──списано──► PAID ──ресторан принял──► ACCEPTED ──► COOKING
 *      │                    │      │                   │                                    │
 *      │              отказ │      │ отмена/таймаут    │ отмена                             ▼
 *      └── отмена/таймаут ──┴──────┴──────────────────►CANCELED              READY_FOR_DELIVERY
 *                                                                                           │
 *                                                       DELIVERED ◄── IN_DELIVERY ◄─────────┘
 * </pre>
 *
 * <p><b>PAYMENT_PENDING</b> появился вместе с сагой. Деньги списывает другой сервис, и между
 * «клиент нажал оплатить» и «деньги списаны» лежат два сообщения Kafka. Заказ всё это время
 * не создан и не оплачен — он ждёт ответа, и это состояние обязано быть видно.
 *
 * <p>Отмена доступна клиенту до того, как ресторан взял заказ в работу. Если оплату уже
 * запросили (PAYMENT_PENDING) или провели (PAID), отмена отправляет сервису платежей команду
 * на возврат: была ли оплата на самом деле, знает только он.
 */
public enum OrderStatus {

    CREATED("CREATED"),
    PAYMENT_PENDING("PAYMENT_PENDING"),
    PAID("PAID"),
    ACCEPTED("ACCEPTED"),
    COOKING("COOKING"),
    READY_FOR_DELIVERY("READY_FOR_DELIVERY"),
    IN_DELIVERY("IN_DELIVERY"),
    DELIVERED("DELIVERED"),
    CANCELED("CANCELED");

    private final String dbValue;

    OrderStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public boolean canBePaidByCustomer() {
        return this == CREATED;
    }

    public boolean canBeCanceledByCustomer() {
        return this == CREATED || this == PAYMENT_PENDING || this == PAID;
    }

    /**
     * Отмена из этого статуса требует возврата денег. Для PAYMENT_PENDING — на всякий случай:
     * команда на списание могла уже дойти до сервиса платежей, и вернуть нужно, если списано.
     */
    public boolean requiresRefundOnCancel() {
        return this == PAYMENT_PENDING || this == PAID;
    }

    /** Заказ ещё ждёт оплаты — и по истечении срока отменяется сам. */
    public boolean isAwaitingPayment() {
        return this == CREATED || this == PAYMENT_PENDING;
    }

    public boolean canBeAcceptedByRestaurant() {
        return this == PAID;
    }

    public boolean canStartCookingByRestaurant() {
        return this == ACCEPTED;
    }

    public boolean canBeMarkedReadyForDeliveryByRestaurant() {
        return this == COOKING;
    }

    public boolean canCreateDelivery() {
        return this == READY_FOR_DELIVERY;
    }

    public boolean canBeMovedToInDeliveryByCourier() {
        return this == READY_FOR_DELIVERY;
    }

    public boolean canBeCompletedByCourier() {
        return this == IN_DELIVERY;
    }

    /**
     * Терминальные статусы: из них нет ни одного перехода. Полезно для проверок в админке
     * и для отчётности.
     */
    public boolean isFinal() {
        return this == DELIVERED || this == CANCELED;
    }

    public static OrderStatus fromDbValue(String dbValue) {
        for (OrderStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Неизвестный статус заказа из базы данных: " + dbValue);
    }
}
