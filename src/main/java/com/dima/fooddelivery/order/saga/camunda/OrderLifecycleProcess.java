package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.order.domain.OrderStatus;
import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Жизненный цикл заказа как процесс Camunda 8 ({@code bpmn/order-lifecycle.bpmn}), от запроса
 * оплаты до доставки.
 *
 * <pre>
 *   старт (запрос оплаты) ─► ждём первого из:
 *       payment-succeeded ─► order-mark-paid ─► [Выполнение заказа] ─► «Доставлен»
 *       payment-failed    ─► order-cancel-payment-failed            ─► «Отменён: отказ оплаты»
 *       таймер оплаты     ─► order-expire-payment                   ─► «Отменён по таймауту»
 *       order-canceled    ─────────────────────────────────────────────► «Отменён клиентом»
 *
 *   [Выполнение заказа]: order-accepted ─► order-cooking ─► order-ready ─► order-in-delivery ─► order-delivered
 *       на шаге «Ресторан принимает» — непрерывающий таймер ─► order-acceptance-overdue (эскалация)
 *       order-canceled на всём подпроцессе ─► «Отменён после оплаты»
 * </pre>
 *
 * <p>Сагу оплаты процесс ведёт сам: решает, что делать с исходом, и отменяет заказ по таймеру.
 * Дальше он наблюдает: статусы меняют ресторан и курьер через API, а процесс узнаёт о каждом
 * сообщением ({@link OrderStatusMessageListener}) и показывает в Operate, где заказ сейчас.
 *
 * <p>Команда «спиши» по-прежнему пишется в outbox в той же транзакции, что и переход в PAYMENT_PENDING:
 * отправь её задание процесса, клиент мог бы отменить заказ раньше, чем ушла команда, и тогда возврат
 * обогнал бы списание.
 *
 * <p>Ключ корреляции всех сообщений — идентификатор заказа. Идентификатор сообщения задаётся явно
 * (eventId исхода, «заказ:статус»): повтор того же сообщения брокер Camunda отбросит сам.
 */
@Slf4j
@Component
@Deployment(resources = "classpath:bpmn/order-lifecycle.bpmn")
@ConditionalOnProperty(name = "app.saga.engine", havingValue = "camunda")
public class OrderLifecycleProcess {

    public static final String PROCESS_ID = "order-lifecycle";

    static final String MESSAGE_SUCCEEDED = "payment-succeeded";
    static final String MESSAGE_FAILED = "payment-failed";
    static final String MESSAGE_ORDER_CANCELED = "order-canceled";

    /** Статусы, о которых процесс ждёт сообщения. Статусы до оплаты процесс ведёт сам. */
    static final Map<OrderStatus, String> STATUS_MESSAGES = Map.of(
            OrderStatus.ACCEPTED, "order-accepted",
            OrderStatus.COOKING, "order-cooking",
            OrderStatus.READY_FOR_DELIVERY, "order-ready",
            OrderStatus.IN_DELIVERY, "order-in-delivery",
            OrderStatus.DELIVERED, "order-delivered",
            OrderStatus.CANCELED, MESSAGE_ORDER_CANCELED
    );

    /**
     * Сколько брокер держит сообщение, если процесс ещё не дошёл до его ожидания: ресторан может
     * принять заказ раньше, чем завершится задание «Заказ оплачен», — тогда сообщение дождётся шага.
     */
    private static final Duration MESSAGE_TTL = Duration.ofHours(1);

    private final CamundaClient camunda;
    private final Duration paymentTimeout;
    private final Duration acceptanceSla;

    public OrderLifecycleProcess(
            CamundaClient camunda,
            @Value("${app.saga.payment-timeout:PT15M}") Duration paymentTimeout,
            @Value("${app.saga.acceptance-sla:PT10M}") Duration acceptanceSla
    ) {
        this.camunda = camunda;
        this.paymentTimeout = paymentTimeout;
        this.acceptanceSla = acceptanceSla;
    }

    public void start(Long orderId) {
        long instanceKey = camunda.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(
                        "orderId", orderId,
                        "paymentTimeout", paymentTimeout.toString(),
                        "acceptanceSla", acceptanceSla.toString()
                ))
                .send()
                .join()
                .getProcessInstanceKey();

        log.info("Процесс заказа запущен: orderId={}, processInstanceKey={}", orderId, instanceKey);
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
                .timeToLive(MESSAGE_TTL)
                .variables(variables)
                .send()
                .join();

        log.info("Исход платежа передан процессу: orderId={}, type={}", event.orderId(), event.eventType());
    }

    /**
     * Сообщает процессу новый статус заказа. Если процесс его уже не ждёт (сам отменил заказ
     * по таймауту или отказу), сообщение истечёт без адресата.
     *
     * @return имя сообщения или пусто, если этот статус процессу не сообщается
     */
    public Optional<String> publishStatus(Long orderId, OrderStatus status) {
        String message = STATUS_MESSAGES.get(status);
        if (message == null) {
            return Optional.empty();
        }

        camunda.newPublishMessageCommand()
                .messageName(message)
                .correlationKey(String.valueOf(orderId))
                .messageId(orderId + ":" + status.getDbValue())
                .timeToLive(MESSAGE_TTL)
                .send()
                .join();

        return Optional.of(message);
    }
}
