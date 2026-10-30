package com.dima.fooddelivery.ledger;

/** Сбой обращения к блокчейну: узел недоступен, транзакция отклонена, ответ не тот. */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }
}
