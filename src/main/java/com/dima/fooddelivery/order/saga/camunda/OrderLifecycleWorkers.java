package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.order.saga.PaymentSaga;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Задания процесса {@code order-lifecycle}: каждое — один шаг в своей транзакции.
 *
 * <p>Задание завершается после коммита. Если завершение не дошло до брокера, он выдаст задание
 * снова — поэтому шаги идемпотентны: переход статуса — compare-and-set, и повтор ничего не меняет.
 * Исключение оставляет задание с уменьшенным счётчиком попыток; когда они кончатся, в Operate
 * появится инцидент с текстом ошибки.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.saga.engine", havingValue = "camunda")
public class OrderLifecycleWorkers {

    private final PaymentSaga paymentSaga;
    private final MeterRegistry meterRegistry;

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

    /**
     * Ресторан не принял оплаченный заказ за {@code app.saga.acceptance-sla}. Заказ не трогаем:
     * решение за рестораном и поддержкой, таймер непрерывающий, и процесс продолжает ждать.
     * Метрика {@code orders.acceptance.overdue} — то, на что можно повесить алерт в Grafana.
     */
    @JobWorker(type = "order-acceptance-overdue")
    public void acceptanceOverdue(@Variable("orderId") Long orderId) {
        meterRegistry.counter("orders.acceptance.overdue").increment();
        log.warn("Ресторан не принял оплаченный заказ вовремя: orderId={}", orderId);
    }
}
