package com.dima.fooddelivery.pricing;

import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Заказы ко времени: отложенная задача «за час до срока напомнить ресторану».
 *
 * <p><b>Как сделаны отложенные задачи.</b> Срок лежит в базе ({@code order_terms.scheduled_for}),
 * а задача раз в минуту выбирает наступившие по частичному индексу. Альтернативы и почему не они:
 * <ul>
 *     <li>RabbitMQ с TTL сообщения — TTL проверяется только у головы очереди, «через три дня» застрянет
 *     перед «через час»; плагин отложенных сообщений хранит их в памяти узла;</li>
 *     <li>Redis ZSET со временем в score — хорош для миллионов задач, но это ещё одно хранилище состояния,
 *     которое надо согласовывать с заказом;</li>
 *     <li>Quartz — свои таблицы и кластерный режим; здесь избыточен.</li>
 * </ul>
 * Опрос базы раз в минуту точен до минуты — для «пора готовить» этого достаточно, а состояние задачи
 * живёт в той же транзакционной базе, что и заказ.
 *
 * <p><b>Два экземпляра — одно напоминание.</b> ShedLock даёт выполнять такт одному экземпляру, а
 * {@code markReminded} (UPDATE … WHERE prep_reminded_at IS NULL) — второй рубеж: даже если такт
 * всё-таки выполнится дважды, напоминание запишет только один.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ScheduledOrders {

    private final OrderTermsRepository terms;
    private final OrderStatusService orderStatusService;
    private final PricingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT20S")
    @SchedulerLock(name = "scheduled-order-reminders", lockAtMostFor = "PT2M")
    public void remindRestaurants() {
        for (OrderTermsRepository.Due due : terms.findDueForPrep(properties.acceptWindow(), 100)) {
            transactionTemplate.executeWithoutResult(status -> {
                if (terms.markReminded(due.orderId())) {
                    String at = due.scheduledFor().atZoneSameInstant(ZoneId.of("Europe/Moscow")).toLocalTime().withSecond(0).withNano(0).toString();
                    orderStatusService.recordEvent(due.orderId(), OrderEventType.ORDER_ACCEPTED, "Пора готовить: заказ ко времени " + at);
                    log.info("Напоминание ресторану: orderId={}, к {}", due.orderId(), at);
                }
            });
        }
    }

    @GetMapping("/api/v1/restaurants/{restaurantId}/orders/scheduled")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    public List<Upcoming> upcoming(@PathVariable Long restaurantId) {
        return jdbc.query("""
                SELECT o.id, o.status, t.scheduled_for, t.prep_reminded_at IS NOT NULL AS reminded
                FROM order_terms t JOIN customer_order o ON o.id = t.order_id
                WHERE o.restaurant_id = ? AND t.scheduled_for IS NOT NULL AND o.status IN ('PAID', 'ACCEPTED', 'COOKING')
                ORDER BY t.scheduled_for
                """, (rs, n) -> new Upcoming(rs.getLong("id"), rs.getString("status"),
                rs.getObject("scheduled_for", OffsetDateTime.class), rs.getBoolean("reminded")), restaurantId);
    }

    /** @param prepReminded напоминание «пора готовить» уже отправлено */
    public record Upcoming(long orderId, String status, OffsetDateTime scheduledFor, boolean prepReminded) {
    }
}
