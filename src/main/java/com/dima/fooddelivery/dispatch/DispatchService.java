package com.dima.fooddelivery.dispatch;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.domain.DeliveryAvailabilityChanged;
import com.dima.fooddelivery.delivery.service.DeliveryService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Автоназначение курьера: предложение ближайшему свободному курьеру на линии.
 *
 * <pre>
 * такт (раз в 5 с, один экземпляр — ShedLock)
 *   1. истёкшие предложения → EXPIRED; предложения по уже взятым доставкам → CANCELED
 *   2. для каждой доставки без курьера и без действующего предложения:
 *        GEOSEARCH от адреса доставки, 5 км, ближайшие первыми
 *        − отказавшиеся и пропустившие эту доставку − занятые другой доставкой − с открытым предложением
 *        → INSERT delivery_offer (OFFERED, истекает через 30 с)
 * курьер: «Беру» → OFFERED→ACCEPTED (если не истекло) → назначение;  «Не могу» → DECLINED → следующему
 * </pre>
 *
 * <ul>
 *     <li><b>Предложение, а не назначение.</b> Курьер может быть за рулём, без заряда, в другом заказе
 *     неофициально — без его согласия заказ «завис» бы. Таймаут возвращает заказ в оборот;</li>
 *     <li><b>одно действующее предложение на доставку</b> держит частичный уникальный индекс: два такта
 *     (или два экземпляра без ShedLock) не предложат одну доставку двоим;</li>
 *     <li><b>ручной путь остаётся.</b> Курьер по-прежнему может взять доставку из списка свободных;
 *     открытое предложение по ней тогда снимается;</li>
 *     <li><b>точка отсчёта — адрес доставки.</b> Правильнее — ресторан, но координат ресторанов в
 *     каталоге нет; когда появятся, поменяется одна строка запроса. Доставки без координат идут только
 *     ручным путём.</li>
 * </ul>
 */
@Slf4j
@Service
public class DispatchService {

    private final JdbcTemplate jdbc;
    private final CourierPresence presence;
    private final DeliveryService deliveryService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactionTemplate;
    private final Duration offerTtl;
    private final double radiusKm;

    public DispatchService(JdbcTemplate jdbc, CourierPresence presence, DeliveryService deliveryService, ApplicationEventPublisher events,
                           TransactionTemplate transactionTemplate,
                           @Value("${app.dispatch.offer-ttl:PT30S}") Duration offerTtl,
                           @Value("${app.dispatch.radius-km:5}") double radiusKm) {
        this.jdbc = jdbc;
        this.presence = presence;
        this.deliveryService = deliveryService;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
        this.offerTtl = offerTtl;
        this.radiusKm = radiusKm;
    }

    @Scheduled(fixedDelayString = "${app.dispatch.tick:PT5S}", initialDelayString = "${app.dispatch.initial-delay:PT10S}")
    @SchedulerLock(name = "courier-dispatch", lockAtMostFor = "PT30S")
    public void tick() {
        int expired = jdbc.update("UPDATE delivery_offer SET status = 'EXPIRED', decided_at = CURRENT_TIMESTAMP WHERE status = 'OFFERED' AND expires_at <= CURRENT_TIMESTAMP");
        jdbc.update("""
                UPDATE delivery_offer o SET status = 'CANCELED', decided_at = CURRENT_TIMESTAMP
                FROM delivery d WHERE d.id = o.delivery_id AND o.status = 'OFFERED' AND d.courier_id IS NOT NULL
                """);

        List<Waiting> waiting = jdbc.query("""
                SELECT d.id, o.delivery_latitude, o.delivery_longitude
                FROM delivery d JOIN customer_order o ON o.id = d.order_id
                WHERE d.status = 'CREATED' AND d.courier_id IS NULL AND o.delivery_latitude IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM delivery_offer f WHERE f.delivery_id = d.id AND f.status = 'OFFERED')
                ORDER BY d.created_at
                LIMIT 50
                """, (rs, n) -> new Waiting(rs.getLong("id"), rs.getBigDecimal("delivery_latitude"), rs.getBigDecimal("delivery_longitude")));

        int offered = 0;
        for (Waiting delivery : waiting) {
            offered += offer(delivery) ? 1 : 0;
        }
        if (offered > 0 || expired > 0) {
            // Будит ждущих курьеров (long polling свободных доставок) — у них появилось или исчезло предложение.
            transactionTemplate.executeWithoutResult(status -> events.publishEvent(new DeliveryAvailabilityChanged()));
            log.info("Автоназначение: предложено {}, истекло {}", offered, expired);
        }
    }

    private boolean offer(Waiting delivery) {
        Set<Long> exclude = new HashSet<>(jdbc.queryForList(
                "SELECT courier_id FROM delivery_offer WHERE delivery_id = ? AND status IN ('DECLINED', 'EXPIRED')", Long.class, delivery.id()));
        exclude.addAll(jdbc.queryForList("SELECT courier_id FROM delivery_offer WHERE status = 'OFFERED'", Long.class));
        exclude.addAll(jdbc.queryForList("SELECT courier_id FROM delivery WHERE status IN ('ASSIGNED', 'PICKED_UP') AND courier_id IS NOT NULL", Long.class));

        Optional<CourierPresence.Nearby> nearest = presence.nearest(delivery.latitude().doubleValue(), delivery.longitude().doubleValue(),
                radiusKm, 1, exclude).stream().findFirst();
        if (nearest.isEmpty()) {
            return false;
        }
        try {
            jdbc.update("INSERT INTO delivery_offer (delivery_id, courier_id, distance_m, expires_at) VALUES (?, ?, ?, ?)",
                    delivery.id(), nearest.get().courierId(), nearest.get().distanceMeters(), OffsetDateTime.now().plus(offerTtl));
            log.info("Доставка {} предложена курьеру {} ({} м)", delivery.id(), nearest.get().courierId(), nearest.get().distanceMeters());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<Offer> offersFor(long courierId) {
        return jdbc.query("""
                SELECT f.id, f.delivery_id, d.order_id, f.distance_m, f.expires_at, o.delivery_address, t.tip_amount
                FROM delivery_offer f
                JOIN delivery d ON d.id = f.delivery_id
                JOIN customer_order o ON o.id = d.order_id
                LEFT JOIN order_terms t ON t.order_id = o.id
                WHERE f.courier_id = ? AND f.status = 'OFFERED' AND f.expires_at > CURRENT_TIMESTAMP
                """, (rs, n) -> new Offer(rs.getLong("id"), rs.getLong("delivery_id"), rs.getLong("order_id"), rs.getObject("distance_m", Integer.class),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("delivery_address"), rs.getBigDecimal("tip_amount")), courierId);
    }

    /** Принять: только своё, только действующее; дальше обычное назначение со всеми его проверками. */
    @Transactional
    public DeliveryResponse accept(long courierId, long offerId) {
        List<Long> delivery = jdbc.queryForList("""
                UPDATE delivery_offer SET status = 'ACCEPTED', decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND courier_id = ? AND status = 'OFFERED' AND expires_at > CURRENT_TIMESTAMP
                RETURNING delivery_id
                """, Long.class, offerId, courierId);
        if (delivery.isEmpty()) {
            throw new BusinessRuleViolationException("Предложение уже недействительно");
        }
        return deliveryService.assignCourierToDelivery(courierId, delivery.getFirst());
    }

    @Transactional
    public void decline(long courierId, long offerId) {
        if (jdbc.update("""
                UPDATE delivery_offer SET status = 'DECLINED', decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND courier_id = ? AND status = 'OFFERED'
                """, offerId, courierId) == 0) {
            throw new BusinessRuleViolationException("Предложение уже недействительно");
        }
    }

    public Earnings earnings(long courierId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS deliveries, COALESCE(SUM(t.tip_amount), 0) AS tips
                FROM delivery d LEFT JOIN order_terms t ON t.order_id = d.order_id
                WHERE d.courier_id = ? AND d.status = 'DELIVERED'
                """, (rs, n) -> new Earnings(rs.getLong("deliveries"), rs.getBigDecimal("tips")), courierId);
    }

    private record Waiting(long id, BigDecimal latitude, BigDecimal longitude) {
    }

    public record Offer(long offerId, long deliveryId, long orderId, Integer distanceMeters, OffsetDateTime expiresAt,
                        String deliveryAddress, BigDecimal tipAmount) {
    }

    public record Earnings(long deliveries, BigDecimal tips) {
    }
}
