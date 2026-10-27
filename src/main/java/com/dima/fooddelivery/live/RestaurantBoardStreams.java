package com.dima.fooddelivery.live;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Потоки SSE доски заказов ресторана.
 *
 * <p>Здесь, в отличие от потока клиента, нет ни истории, ни {@code Last-Event-ID}: в кадре
 * только «заказ N теперь в статусе S», а доска по нему перечитывает свою колонку. После
 * переподключения доска просто перечитывает всё — восстанавливать пропущенные кадры дороже,
 * чем один запрос списка. Поэтому базу этот канал не читает вовсе: всё нужное есть в сигнале.
 *
 * <p>Это пример fan-out: один сигнал о заказе уходит всем открытым вкладкам всех сотрудников
 * ресторана.
 */
@Component
public class RestaurantBoardStreams implements LiveChannel {

    static final String ORDER = "order";
    static final String READY = "ready";

    private final Map<Long, Set<SseStream>> byRestaurant = new ConcurrentHashMap<>();

    private final StreamLimiter limiter;
    private final LiveProperties properties;

    public RestaurantBoardStreams(StreamLimiter limiter, LiveProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    /** @return {@code null}, если потолок потоков достигнут */
    public SseEmitter open(Long restaurantId) {
        if (!limiter.tryAcquire()) {
            return null;
        }

        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        SseStream stream = new SseStream(emitter);

        byRestaurant.computeIfAbsent(restaurantId, id -> ConcurrentHashMap.newKeySet()).add(stream);

        Runnable cleanup = () -> forget(restaurantId, stream);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            stream.complete();
            cleanup.run();
        });
        emitter.onError(error -> cleanup.run());

        // Первый кадр сразу: клиент узнаёт, что подписка состоялась, и прокси получают первые байты
        // ответа — некоторые без них держат заголовки у себя.
        stream.send(null, READY, Map.of("restaurantId", restaurantId));

        return emitter;
    }

    @Override
    public void onSignal(LiveSignal signal) {
        if (!signal.isStatusChange()) {
            return;
        }

        Set<SseStream> streams = byRestaurant.get(signal.restaurantId());
        if (streams == null) {
            return;
        }

        BoardChange change = new BoardChange(signal.orderId(), signal.status().getDbValue());
        streams.forEach(stream -> {
            if (!stream.send(null, ORDER, change)) {
                forget(signal.restaurantId(), stream);
            }
        });
    }

    @Override
    public void resync() {
        // Сигналы потерялись — пусть доски перечитают себя целиком.
        byRestaurant.forEach((restaurantId, streams) ->
                streams.forEach(stream -> stream.send(null, READY, Map.of("restaurantId", restaurantId))));
    }

    @Override
    public void closeAll() {
        byRestaurant.values().forEach(streams -> streams.forEach(SseStream::complete));
        byRestaurant.clear();
    }

    @Scheduled(fixedDelayString = "${app.live.heartbeat:PT15S}")
    void heartbeat() {
        byRestaurant.forEach((restaurantId, streams) -> streams.forEach(stream -> {
            if (!stream.heartbeat()) {
                forget(restaurantId, stream);
            }
        }));
    }

    private void forget(Long restaurantId, SseStream stream) {
        Set<SseStream> streams = byRestaurant.get(restaurantId);
        if (streams != null && streams.remove(stream)) {
            limiter.release();
            if (streams.isEmpty()) {
                byRestaurant.remove(restaurantId, streams);
            }
        }
    }

    /** Кадр доски: заказ и его новый статус. */
    public record BoardChange(Long orderId, String status) {
    }
}
