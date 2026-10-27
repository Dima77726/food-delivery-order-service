package com.dima.fooddelivery.live;

import com.dima.fooddelivery.delivery.domain.DeliveryAvailabilityChanged;
import com.dima.fooddelivery.order.domain.OrderEventAppended;
import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Отправляет сигналы живых обновлений через {@code pg_notify} — в той же транзакции,
 * что и само изменение.
 *
 * <p>Почему PostgreSQL, а не Redis Pub/Sub или Kafka. {@code NOTIFY} транзакционен: сигнал
 * доставляется слушателям только после коммита и пропадает вместе с откатом. Значит, не бывает
 * ни сигнала об изменении, которого нет в базе, ни отдельного шага «после коммита», который
 * может не выполниться. Kafka с её outbox дала бы ту же гарантию, но с задержкой публикации
 * (до двух секунд) — для живого статуса это заметно. Redis пришлось бы звать после коммита
 * отдельно, и падение между коммитом и вызовом теряло бы сигнал.
 *
 * <p>Цена: сигнал доходит только до экземпляров, подключённых к этой же базе, и не хранится —
 * кто не слушал в момент коммита, его не получит. Обе вещи здесь не мешают: слушают экземпляры
 * этого же сервиса, а пропуск догоняется чтением из базы (см. {@link LiveSignal}).
 *
 * <p>Слушатели синхронные, а не {@code @TransactionalEventListener}: {@code pg_notify} обязан
 * выполниться внутри транзакции, иначе вся гарантия пропадает.
 */
@Component
@RequiredArgsConstructor
public class LiveSignals {

    static final String CHANNEL = "live_updates";

    private final JdbcTemplate jdbcTemplate;

    @EventListener
    public void onOrderEventAppended(OrderEventAppended event) {
        notify(LiveSignal.orderEventPayload(event.orderId()));
    }

    @EventListener
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        notify(LiveSignal.statusChangePayload(event.orderId(), event.restaurantId(), event.newStatus()));
    }

    @EventListener
    public void onDeliveryAvailabilityChanged(DeliveryAvailabilityChanged event) {
        notify(LiveSignal.DELIVERIES);
    }

    private void notify(String payload) {
        // Одинаковые сигналы в одной транзакции PostgreSQL сам схлопывает в один.
        jdbcTemplate.queryForObject("SELECT pg_notify(?, ?)", Object.class, CHANNEL, payload);
    }
}
