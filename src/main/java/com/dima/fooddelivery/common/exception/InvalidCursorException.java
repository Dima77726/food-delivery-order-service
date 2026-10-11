package com.dima.fooddelivery.common.exception;

/**
 * Клиент прислал курсор, который приложение не выдавало.
 *
 * <p>Отдельный тип, а не {@link BusinessRuleViolationException}: это ошибка ввода (400),
 * а не нарушение бизнес-правила (409). Курсор клиент не сочиняет сам — он берёт его из поля
 * {@code nextCursor} предыдущего ответа, поэтому мусор в параметре означает ошибку в коде
 * клиента, и отвечать на неё нужно так же, как на любой некорректный query-параметр.
 */
public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message) {
        super(message);
    }
}
