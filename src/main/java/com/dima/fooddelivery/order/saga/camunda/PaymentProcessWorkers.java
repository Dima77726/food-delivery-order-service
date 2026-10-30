package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.order.saga.PaymentSaga;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Задания процесса {@code payment-saga}: каждое — один шаг саги в своей транзакции.
 *
 * <p>Задание завершается после коммита. Если завершение не дошло до брокера, он выдаст задание
 * снова — поэтому шаги идемпотентны: переход статуса — compare-and-set, и повтор ничего не меняет.
 * Исключение оставляет задание с уменьшенным счётчиком попыток; когда они кончатся, в Operate
 * появится инцидент с текстом ошибки.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.saga.engine", havingValue = "camunda")
public class PaymentProcessWorkers {

    private final PaymentSaga paymentSaga;

    @Value("${app.saga.payment-timeout:PT15M}")
    private Duration paymentTimeout;

    @JobWorker(type = "order-mark-paid")
    public void markPaid(@Variable("orderId") Long orderId, @Variable("paymentId") Long paymentId) {
        paymentSaga.markPaid(orderId, paymentId);
    }

    @JobWorker(type = "order-cancel-payment-failed")
    public void cancelOnPaymentFailure(@Variable("orderId") Long orderId,
                                       @Variable(value = "failureReason", optional = true) String failureReason) {
        paymentSaga.cancelOnPaymentFailure(orderId, failureReason);
    }

    @JobWorker(type = "order-expire-payment")
    public void expire(@Variable("orderId") Long orderId) {
        paymentSaga.expireIfAwaiting(orderId, paymentTimeout);
    }
}
