package com.dima.fooddelivery.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Бизнес-метрики платформы.
 *
 * <p>Micrometer сам считает технические показатели: время ответа, коды HTTP, пул соединений,
 * работу сборщика мусора. Всё это отвечает на вопрос «жив ли сервис» и молчит о том, «работает
 * ли бизнес». Приложение может отдавать бодрые 200 на каждый запрос, пока оплаты отклоняются
 * все подряд, — по техническим метрикам это не видно.
 *
 * <p>Счётчики оплат переехали вместе с деньгами в payment-service; имена метрик при этом
 * не изменились, и дашборды продолжают их находить.
 *
 * <p>Счётчики собраны в один компонент, а не рассыпаны по сервисам: имена метрик — такой же
 * контракт, как и API. Разъехавшись по десятку классов, они неизбежно превращаются
 * в orders_created, order.created и ordersCreatedTotal одновременно.
 *
 * <p>Про теги: их значения обязаны быть из ограниченного набора. Каждое новое значение тега —
 * это новый временной ряд в Prometheus. Тег со статусом заказа даёт восемь рядов, а тег
 * с идентификатором ресторана — столько, сколько ресторанов, и это верный способ положить
 * хранилище метрик.
 */
@Slf4j
@Component
public class BusinessMetrics {

    private static final String ORDERS_CREATED = "food_delivery.orders.created";
    private static final String ORDERS_CANCELED = "food_delivery.orders.canceled";
    private static final String ORDERS_DELIVERED = "food_delivery.orders.delivered";
    private static final String ORDER_VALUE = "food_delivery.orders.value";
    private static final String CHECKOUT_DURATION = "food_delivery.checkout.duration";

    private final MeterRegistry registry;

    private final Counter ordersCreated;
    private final Counter ordersCanceled;
    private final Counter ordersDelivered;
    private final Timer checkoutDuration;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.ordersCreated = Counter.builder(ORDERS_CREATED)
                .description("Заказов создано из корзины")
                .register(registry);

        this.ordersCanceled = Counter.builder(ORDERS_CANCELED)
                .description("Заказов отменено клиентом")
                .register(registry);

        this.ordersDelivered = Counter.builder(ORDERS_DELIVERED)
                .description("Заказов доставлено")
                .register(registry);

        this.checkoutDuration = Timer.builder(CHECKOUT_DURATION)
                .description("Время оформления заказа из корзины")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void orderCreated(BigDecimal totalAmount) {
        ordersCreated.increment();

        // Сумма пишется отдельной метрикой-распределением: по ней видно и выручку,
        // и структуру чека. Счётчик заказов на этот вопрос не отвечает.
        registry.summary(ORDER_VALUE).record(totalAmount.doubleValue());
    }

    public void orderCanceled() {
        ordersCanceled.increment();
    }

    public void orderDelivered() {
        ordersDelivered.increment();
    }

    public Timer.Sample startCheckout() {
        return Timer.start(registry);
    }

    public void finishCheckout(Timer.Sample sample) {
        sample.stop(checkoutDuration);
    }
}
