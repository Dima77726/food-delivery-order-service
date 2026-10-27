package com.dima.fooddelivery.order.saga;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.contracts.payment.PaymentCommand;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.PaymentMethod;
import com.dima.fooddelivery.order.persistence.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Кладёт команды сервису платежей в outbox.
 *
 * <p>Через тот же outbox, что и события заказа, и в той же транзакции, что и смена статуса.
 * Иначе возможны оба плохих исхода: заказ ушёл в PAYMENT_PENDING, а команда потерялась —
 * и он ждёт ответа, которого не будет; или команда ушла, а смена статуса откатилась — и деньги
 * списаны за заказ, который о них не знает.
 *
 * <p>Ключ сообщения — идентификатор заказа, как и у событий заказа: «спиши» и «верни»
 * одного заказа приходят к сервису платежей по порядку.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCommandWriter {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void requestPayment(OrderAccess order, PaymentMethod method, String idempotencyKey) {
        append(order, PaymentCommand.PAYMENT_REQUESTED, method.name(), idempotencyKey);
    }

    /**
     * Просит вернуть деньги, если они были списаны. Отправляется и тогда, когда списание ещё
     * не подтверждено: знать наверняка, дошла ли команда «спиши», сервис заказов не может,
     * а лишний запрос на возврат безвреден — сервис платежей вернёт только то, что списал.
     */
    public void requestRefund(OrderAccess order) {
        append(order, PaymentCommand.REFUND_REQUESTED, null, null);
    }

    /**
     * Частичный возврат: та же команда REFUND_REQUESTED, но с суммой меньше заказа и ключом идемпотентности
     * заявки — по ключу сервис платежей отличает частичный возврат от полного и не вернёт одну заявку дважды.
     * Контракт события при этом не меняется: сумма в команде была и раньше.
     */
    public void requestPartialRefund(OrderAccess order, BigDecimal amount, UUID refundRequestKey) {
        append(order, PaymentCommand.REFUND_REQUESTED, null, "partial-refund:" + refundRequestKey, amount);
    }

    private void append(OrderAccess order, String commandType, String method, String idempotencyKey) {
        append(order, commandType, method, idempotencyKey, order.totalAmount());
    }

    private void append(OrderAccess order, String commandType, String method, String idempotencyKey, BigDecimal amount) {
        UUID commandId = UUID.randomUUID();

        PaymentCommand command = new PaymentCommand(
                commandId,
                PaymentCommand.CURRENT_SCHEMA_VERSION,
                commandType,
                order.orderId(),
                order.customerId(),
                amount,
                method,
                idempotencyKey,
                OffsetDateTime.now()
        );

        outboxRepository.append(
                commandId,
                KafkaTopics.PAYMENT_COMMANDS,
                AGGREGATE_TYPE,
                order.orderId(),
                commandType,
                objectMapper.writeValueAsString(command),
                RequestContext.correlationId().orElse(null)
        );

        log.info("Команда сервису платежей помещена в outbox: orderId={}, type={}, commandId={}",
                order.orderId(), commandType, commandId);
    }
}
