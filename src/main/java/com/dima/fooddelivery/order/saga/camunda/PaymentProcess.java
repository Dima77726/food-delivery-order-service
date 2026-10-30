package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Сага оплаты как процесс Camunda 8 ({@code bpmn/payment-saga.bpmn}).
 *
 * <pre>
 *   старт (запрос оплаты) ─► ждём первого из:
 *       payment-succeeded ─► задание order-mark-paid             ─► «Оплачен»
 *       payment-failed    ─► задание order-cancel-payment-failed ─► «Отменён: оплата не прошла»
 *       таймер            ─► задание order-expire-payment        ─► «Отменён по таймауту»
 *       order-canceled    ─────────────────────────────────────── ─► «Отменён клиентом»
 * </pre>
 *
 * <p>Процесс ведёт только ожидание исхода. Команда «спиши» по-прежнему пишется в outbox в той же
 * транзакции, что и переход в PAYMENT_PENDING: отправь её задание процесса, клиент мог бы отменить
 * заказ раньше, чем ушла команда, и тогда возврат обогнал бы списание.
 *
 * <p>Ключ корреляции всех сообщений — идентификатор заказа, а идентификатор сообщения об исходе —
 * eventId из Kafka: повтор того же события брокер Camunda отбросит сам.
 */
@Slf4j
@Component
@Deployment(resources = "classpath:bpmn/payment-saga.bpmn")
@ConditionalOnProperty(name = "app.saga.engine", havingValue = "camunda")
public class PaymentProcess {

    public static final String PROCESS_ID = "payment-saga";

    static final String MESSAGE_SUCCEEDED = "payment-succeeded";
    static final String MESSAGE_FAILED = "payment-failed";
    static final String MESSAGE_ORDER_CANCELED = "order-canceled";

    /**
     * Сколько брокер держит исход, если процесс ещё не дошёл до ожидания. Процесс стартует до
     * отправки команды, так что в обычном потоке подписка уже есть; запас — на ретраи и рестарты.
     */
    private static final Duration OUTCOME_TTL = Duration.ofHours(1);

    private final CamundaClient camunda;
    private final Duration paymentTimeout;

    public PaymentProcess(CamundaClient camunda, @Value("${app.saga.payment-timeout:PT15M}") Duration paymentTimeout) {
        this.camunda = camunda;
        this.paymentTimeout = paymentTimeout;
    }

    public void start(Long orderId) {
        long instanceKey = camunda.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(
                        "orderId", orderId,
                        "paymentTimeout", paymentTimeout.toString()
                ))
                .send()
                .join()
                .getProcessInstanceKey();

        log.info("Процесс оплаты запущен: orderId={}, processInstanceKey={}", orderId, instanceKey);
    }

    /** Исходы, которые ждёт процесс. Возврат денег процесс не ведёт — его пишет в историю сама сага. */
    public boolean handles(PaymentEvent event) {
        return PaymentEvent.PAYMENT_SUCCEEDED.equals(event.eventType())
                || PaymentEvent.PAYMENT_FAILED.equals(event.eventType());
    }

    public void publishOutcome(PaymentEvent event) {
        boolean succeeded = PaymentEvent.PAYMENT_SUCCEEDED.equals(event.eventType());

        Map<String, Object> variables = new HashMap<>();
        variables.put("paymentId", event.paymentId());
        if (!succeeded) {
            variables.put("failureReason", event.failureReason());
        }

        camunda.newPublishMessageCommand()
                .messageName(succeeded ? MESSAGE_SUCCEEDED : MESSAGE_FAILED)
                .correlationKey(String.valueOf(event.orderId()))
                .messageId(event.eventId().toString())
                .timeToLive(OUTCOME_TTL)
                .variables(variables)
                .send()
                .join();

        log.info("Исход платежа передан процессу: orderId={}, type={}", event.orderId(), event.eventType());
    }

    /**
     * Клиент отменил заказ, пока тот ждал исхода. Без срока жизни: если процесс уже не ждёт
     * (сам отменил заказ по таймауту или отказу), сообщение просто никому не нужно.
     */
    public void publishOrderCanceled(Long orderId) {
        camunda.newPublishMessageCommand()
                .messageName(MESSAGE_ORDER_CANCELED)
                .correlationKey(String.valueOf(orderId))
                .timeToLive(Duration.ZERO)
                .send()
                .join();
    }
}
