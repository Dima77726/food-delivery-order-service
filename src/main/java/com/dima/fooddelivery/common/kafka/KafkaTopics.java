package com.dima.fooddelivery.common.kafka;

import com.dima.fooddelivery.contracts.audit.AuditTopics;
import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.contracts.payment.PaymentTopics;

/**
 * Топики сервиса заказов. Имена — из библиотеки контрактов: их же читают другие сервисы.
 *
 * <p>Пишет сервис в три: события заказа (их читают уведомления и аналитика), команды сервису
 * платежей и журнал аудита (его собирает insights-service). Читает один — исходы платежей. Топик разбора у него тоже один, по читаемому
 * топику: DLT принадлежит потребителю.
 */
public final class KafkaTopics {

    public static final String ORDER_EVENTS = OrderEventTopics.ORDER_EVENTS;

    public static final String PAYMENT_COMMANDS = PaymentTopics.PAYMENT_COMMANDS;

    public static final String PAYMENT_EVENTS = PaymentTopics.PAYMENT_EVENTS;

    public static final String AUDIT_EVENTS = AuditTopics.AUDIT_EVENTS;

    /** Исходы платежей, которые сервис заказов не смог обработать. */
    public static final String PAYMENT_EVENTS_DLT = PAYMENT_EVENTS + ".DLT";

    private KafkaTopics() {
    }
}
