package com.dima.fooddelivery.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Расчёт с одним рестораном за один день.
 *
 * @param revenue    сумма доставленных заказов
 * @param commission доля платформы
 * @param payout     к выплате ресторану: выручка минус комиссия
 */
public record SettlementLine(
        LocalDate date,
        long restaurantId,
        int ordersCount,
        BigDecimal revenue,
        BigDecimal commission,
        BigDecimal payout
) {

    static final String CSV_HEADER = "date,restaurant_id,orders,revenue,commission,payout";

    String toCsv() {
        return date + "," + restaurantId + "," + ordersCount + "," + revenue.toPlainString() + ","
                + commission.toPlainString() + "," + payout.toPlainString();
    }
}
