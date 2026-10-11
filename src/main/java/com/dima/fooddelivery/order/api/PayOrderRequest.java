package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.domain.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Запрос на оплату заказа.
 *
 * <p>Ключ идемпотентности присылает клиент — обычно это UUID, сгенерированный на экране оплаты
 * один раз и переиспользуемый при повторных попытках. Сервер не может сгенерировать его сам:
 * ему неоткуда узнать, что новый HTTP-запрос — это повтор предыдущего, а не вторая покупка.
 */
public record PayOrderRequest(
        @NotNull(message = "способ оплаты обязателен")
        PaymentMethod method,

        @NotBlank(message = "ключ идемпотентности обязателен")
        @Size(max = 100, message = "ключ идемпотентности не длиннее 100 символов")
        String idempotencyKey
) {
}
