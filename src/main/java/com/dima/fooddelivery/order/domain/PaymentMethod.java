package com.dima.fooddelivery.order.domain;

/**
 * Способ оплаты, выбранный клиентом.
 *
 * <p>Своя копия, а не тип из сервиса платежей: в команду он уходит строкой ({@code name()}),
 * и общего кода у двух сервисов нет. Значения совпадают с {@code chk_customer_order_payment_method}.
 */
public enum PaymentMethod {

    CARD,

    CASH_ON_DELIVERY
}
