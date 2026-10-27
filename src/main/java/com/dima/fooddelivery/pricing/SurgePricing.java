package com.dima.fooddelivery.pricing;

import com.dima.fooddelivery.dispatch.CourierPresence;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Динамическая цена доставки (surge pricing): коэффициент к базовой цене от соотношения спроса и
 * предложения.
 *
 * <pre>
 * спрос        = доставок, ждущих курьера
 * предложение  = курьеров на линии (пульс в Redis)
 * сырой        = 1 + 0,5 × (спрос / max(предложение, 1) − 1), в пределах [1; maxSurge]
 * коэффициент  = 0,5 × прошлый + 0,5 × сырой, округлён до 0,1     ← сглаживание (EMA)
 * </pre>
 *
 * <ul>
 *     <li><b>Сглаживание.</b> Без него цена прыгала бы на каждом новом заказе и каждом курьере, вышедшем
 *     на линию: клиент видит в корзине одну цену, обновляет страницу — другую. Экспоненциальное среднее
 *     гасит скачки, округление до 0,1 убирает дрожание в копейках;</li>
 *     <li><b>один считает — все читают.</b> Пересчёт запускается на всех экземплярах, но выполняет один
 *     (ShedLock), а результат кладёт в Redis. Иначе экземпляры с разной историей сглаживания называли бы
 *     клиенту разные цены;</li>
 *     <li><b>цена фиксируется при оформлении</b> (в {@code order_terms}): коэффициент может вырасти, пока
 *     клиент оплачивает, — заплатит он ту цену, которую видел.</li>
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(PricingProperties.class)
public class SurgePricing {

    static final String KEY = "pricing:surge";
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final CourierPresence presence;
    private final PricingProperties properties;

    public SurgePricing(StringRedisTemplate redis, JdbcTemplate jdbc, CourierPresence presence, PricingProperties properties) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.presence = presence;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.pricing.surge-interval:PT30S}")
    @SchedulerLock(name = "surge-pricing", lockAtMostFor = "PT1M")
    public void recompute() {
        long waiting = jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE status = 'CREATED' AND courier_id IS NULL", Long.class);
        long online = presence.countOnline();
        BigDecimal next = next(current().multiplier(), waiting, online, properties.maxSurge());
        redis.opsForValue().set(KEY, next.toPlainString() + ":" + waiting + ":" + online);
        log.debug("Коэффициент спроса: {} (ждут курьера {}, на линии {})", next, waiting, online);
    }

    /** Чистая функция пересчёта — её проверяет unit-тест. */
    static BigDecimal next(BigDecimal previous, long waiting, long online, BigDecimal max) {
        BigDecimal ratio = BigDecimal.valueOf(waiting).divide(BigDecimal.valueOf(Math.max(online, 1)), 4, RoundingMode.HALF_UP);
        BigDecimal raw = BigDecimal.ONE.add(HALF.multiply(ratio.subtract(BigDecimal.ONE))).max(BigDecimal.ONE).min(max);
        BigDecimal smoothed = HALF.multiply(previous).add(HALF.multiply(raw));
        return smoothed.setScale(1, RoundingMode.HALF_UP).max(BigDecimal.ONE.setScale(1)).min(max.setScale(1, RoundingMode.DOWN));
    }

    public Quote current() {
        String value = redis.opsForValue().get(KEY);
        if (value == null) {
            return new Quote(properties.baseDeliveryFee(), new BigDecimal("1.0"), properties.baseDeliveryFee(), 0, 0);
        }
        String[] parts = value.split(":");
        BigDecimal multiplier = new BigDecimal(parts[0]);
        BigDecimal fee = properties.baseDeliveryFee().multiply(multiplier).setScale(0, RoundingMode.HALF_UP).setScale(2);
        return new Quote(properties.baseDeliveryFee(), multiplier, fee, Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    }

    /**
     * @param fee цена доставки сейчас: база × коэффициент, до рубля
     */
    public record Quote(BigDecimal baseFee, BigDecimal multiplier, BigDecimal fee, long waitingDeliveries, long onlineCouriers) {
    }
}
