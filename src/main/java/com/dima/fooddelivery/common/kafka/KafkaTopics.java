package com.dima.fooddelivery.common.kafka;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.contracts.payment.PaymentTopics;

/**
 * Топики сервиса заказов. Имена — из библиотеки контрактов: их же читают другие сервисы.
 *
 * <p>Пишет сервис в два: события заказа (их читают уведомления и аналитика) и команды сервису
 * платежей. Читает один — исходы платежей. Топик разбора у него тоже один, по читаемому
 * топику: DLT принадлежит потребителю.
 */
public final class KafkaTopics {

    public static final String ORDER_EVENTS = OrderEventTopics.ORDER_EVENTS;

    public static final String PAYMENT_COMMANDS = PaymentTopics.PAYMENT_COMMANDS;

    public static final String PAYMENT_EVENTS = PaymentTopics.PAYMENT_EVENTS;

    /** Исходы платежей, которые сервис заказов не смог обработать. */
    public static final String PAYMENT_EVENTS_DLT = PAYMENT_EVENTS + ".DLT";

    private KafkaTopics() {
    }
}
