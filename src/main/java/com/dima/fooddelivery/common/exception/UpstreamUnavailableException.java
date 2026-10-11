package com.dima.fooddelivery.common.exception;

/**
 * Сервис, без которого запрос не решить, не ответил.
 *
 * <p>Отдельный тип ради кода ответа 503. Это не ошибка клиента (4xx) и не поломка сервиса
 * (500): сервис исправен, но без соседа выполнить запрос не может, и правильный совет
 * клиенту — повторить позже.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
