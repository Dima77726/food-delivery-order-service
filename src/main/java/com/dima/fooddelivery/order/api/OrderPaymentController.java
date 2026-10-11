package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.saga.PaymentSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Запуск оплаты заказа.
 *
 * <p>Адрес прежний — {@code POST .../orders/{orderId}/payments}, — но ответ другой: 202 Accepted
 * и заказ в статусе PAYMENT_PENDING вместо готового платежа. Деньги списывает payment-service,
 * асинхронно; исход клиент увидит в статусе заказа (PAID или CANCELED) и в списке платежей
 * {@code GET .../orders/{orderId}/payments}, который отдаёт уже сервис платежей.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Payment", description = "Оплата заказов")
@SecurityRequirement(name = "bearer-jwt")
public class OrderPaymentController {

    private final PaymentSaga paymentSaga;

    @PostMapping("/api/v1/customers/{customerId}/orders/{orderId}/payments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Запросить оплату заказа; исход придёт асинхронно, повтор с тем же ключом безопасен")
    public OrderResponse payForOrder(
            @Positive(message = "customerId должен быть положительным числом")
            @PathVariable Long customerId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId,

            @Valid @RequestBody PayOrderRequest request
    ) {
        log.info("Запрос оплаты заказа: customerId={}, orderId={}, method={}", customerId, orderId, request.method());

        return paymentSaga.requestPayment(customerId, orderId, request);
    }
}
