package com.dima.fooddelivery.dispatch;

import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Кто из курьеров на линии и где: гео-индекс Redis.
 *
 * <pre>
 * couriers:geo           sorted set с геохешем: GEOADD lon lat courierId; GEOSEARCH … BYRADIUS 5 km ASC
 * couriers:alive:{id}    строка с TTL 90 с — «пульс»: обновляется каждой отметкой приложения курьера
 * </pre>
 *
 * <p><b>Почему Redis GEO, а не SQL.</b> «Ближайшие к точке» — запрос, который реляционная база без
 * пространственного индекса (PostGIS) решает перебором всех строк. Redis хранит координаты как геохеш —
 * 52-битное число, где близкие точки имеют близкие значения, — в отсортированном множестве, и поиск в
 * радиусе — это несколько диапазонов по этому числу: O(log N + M).
 *
 * <p><b>Почему отдельный пульс.</b> Члены отсортированного множества не истекают по одному. Поэтому
 * «жив ли курьер» хранит ключ с TTL: приложение курьера упало или телефон сел — ключ исчезает сам через
 * 90 секунд, поиск такого курьера пропускает, а уборщик вычищает его и из гео-индекса.
 */
@Component
@RequiredArgsConstructor
public class CourierPresence {

    static final String GEO_KEY = "couriers:geo";
    static final String ALIVE_PREFIX = "couriers:alive:";
    static final Duration ALIVE_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;

    public void online(long courierId, double latitude, double longitude) {
        redis.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), String.valueOf(courierId));
        redis.opsForValue().set(ALIVE_PREFIX + courierId, "1", ALIVE_TTL);
    }

    public void offline(long courierId) {
        redis.opsForGeo().remove(GEO_KEY, String.valueOf(courierId));
        redis.delete(ALIVE_PREFIX + courierId);
    }

    public boolean isOnline(long courierId) {
        return Boolean.TRUE.equals(redis.hasKey(ALIVE_PREFIX + courierId));
    }

    /** Курьеры на линии в радиусе, ближайшие первыми; {@code exclude} — уже отказавшиеся. */
    public List<Nearby> nearest(double latitude, double longitude, double radiusKm, int limit, Collection<Long> exclude) {
        var results = redis.opsForGeo().radius(GEO_KEY,
                new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(limit + exclude.size() + 10));
        List<Nearby> nearby = new ArrayList<>();
        if (results == null) {
            return nearby;
        }
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            long courierId = Long.parseLong(result.getContent().getName());
            if (exclude.contains(courierId) || !isOnline(courierId)) {
                continue;
            }
            nearby.add(new Nearby(courierId, (int) Math.round(result.getDistance().in(Metrics.KILOMETERS).getValue() * 1000)));
            if (nearby.size() == limit) {
                break;
            }
        }
        return nearby;
    }

    /**
     * Сколько курьеров на линии — для коэффициента спроса. Не {@code KEYS couriers:alive:*}: KEYS перебирает
     * все ключи базы и на это время блокирует Redis целиком. Члены гео-индекса и так перечень кандидатов.
     */
    public long countOnline() {
        Set<String> members = redis.opsForZSet().range(GEO_KEY, 0, -1);
        return members == null ? 0 : members.stream().filter(member -> Boolean.TRUE.equals(redis.hasKey(ALIVE_PREFIX + member))).count();
    }

    /** Уборка гео-индекса от курьеров без пульса: члены множества сами не истекают. */
    @Scheduled(fixedDelayString = "PT60S")
    void pruneStale() {
        Set<String> members = redis.opsForZSet().range(GEO_KEY, 0, -1);
        if (members == null) {
            return;
        }
        members.stream()
                .filter(member -> !Boolean.TRUE.equals(redis.hasKey(ALIVE_PREFIX + member)))
                .forEach(member -> redis.opsForGeo().remove(GEO_KEY, member));
    }

    /** @param distanceMeters расстояние по прямой до точки поиска */
    public record Nearby(long courierId, int distanceMeters) {
    }
}
