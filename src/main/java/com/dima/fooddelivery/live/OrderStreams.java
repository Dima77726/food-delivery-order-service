package com.dima.fooddelivery.live;

import com.dima.fooddelivery.order.api.OrderEventResponse;
import com.dima.fooddelivery.order.api.OrderResponseMapper;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEvent;
import com.dima.fooddelivery.order.persistence.OrderEventRepository;
import com.dima.fooddelivery.order.service.OrderStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Потоки SSE клиента: история и статус одного заказа в реальном времени.
 *
 * <p>Схема «сигнал + чтение». Каждый поток помнит идентификатор последнего отправленного события.
 * По сигналу о заказе он дочитывает из базы всё, что новее, и отправляет. Отсюда три свойства:
 * <ul>
 *     <li>переподключение с {@code Last-Event-ID} продолжает ровно с места обрыва — это тот же
 *     путь, что и обычный сигнал;</li>
 *     <li>лишний или повторный сигнал безвреден — дочитывать нечего;</li>
 *     <li>потерянный сигнал не теряет события — их заберёт следующий.</li>
 * </ul>
 *
 * <p>Известное ограничение: события одного заказа упорядочены по идентификатору, а он выдаётся
 * в момент вставки, не коммита. Смены статуса одного заказа сериализует блокировка строки
 * заказа, поэтому их порядок честный; событие без смены статуса теоретически может закоммититься
 * позже события с большим номером и тогда придёт клиенту только после переподключения.
 * Полное решение — свой счётчик событий на заказ; здесь оно не окупается.
 */
@Slf4j
@Component
public class OrderStreams implements LiveChannel {

    static final String EVENT = "order-event";
    static final String STATUS = "status";
    static final String END = "end";

    private final Map<Long, Set<OrderStream>> byOrder = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("live-orders-", 0).daemon().factory());

    private final OrderEventRepository orderEventRepository;
    private final OrderStatusService orderStatusService;
    private final StreamLimiter limiter;
    private final LiveProperties properties;

    public OrderStreams(
            OrderEventRepository orderEventRepository,
            OrderStatusService orderStatusService,
            StreamLimiter limiter,
            LiveProperties properties
    ) {
        this.orderEventRepository = orderEventRepository;
        this.orderStatusService = orderStatusService;
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * Открывает поток. Права на заказ проверяет вызывающий.
     *
     * @param lastEventId последнее событие, которое клиент уже видел, или {@code null} — тогда
     *                    сначала уйдёт вся история
     * @return {@code null}, если потолок потоков достигнут
     */
    public SseEmitter open(Long orderId, Long lastEventId) {
        if (!limiter.tryAcquire()) {
            return null;
        }

        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        OrderStream stream = new OrderStream(emitter, orderId, lastEventId == null ? 0L : lastEventId);

        byOrder.computeIfAbsent(orderId, id -> ConcurrentHashMap.newKeySet()).add(stream);

        Runnable cleanup = () -> forget(stream);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            stream.complete();
            cleanup.run();
        });
        emitter.onError(error -> cleanup.run());

        executor.execute(() -> catchUp(stream));

        return emitter;
    }

    @Override
    public void onSignal(LiveSignal signal) {
        if (signal.isDeliveries()) {
            return;
        }

        Set<OrderStream> streams = byOrder.get(signal.orderId());
        if (streams != null) {
            streams.forEach(stream -> executor.execute(() -> catchUp(stream)));
        }
    }

    @Override
    public void resync() {
        byOrder.values().forEach(streams -> streams.forEach(stream -> executor.execute(() -> catchUp(stream))));
    }

    @Override
    public void closeAll() {
        byOrder.values().forEach(streams -> streams.forEach(SseStream::complete));
        byOrder.clear();
    }

    @Scheduled(fixedDelayString = "${app.live.heartbeat:PT15S}")
    void heartbeat() {
        byOrder.values().forEach(streams -> streams.forEach(stream -> {
            if (!stream.heartbeat()) {
                forget(stream);
            }
        }));
    }

    int openStreams(Long orderId) {
        Set<OrderStream> streams = byOrder.get(orderId);
        return streams == null ? 0 : streams.size();
    }

    private void catchUp(OrderStream stream) {
        stream.lock().lock();
        try {
            if (stream.isClosed()) {
                return;
            }

            List<OrderEvent> events = orderEventRepository.findByOrderIdAfter(stream.orderId, stream.lastEventId);
            OrderAccess access = orderStatusService.requireAccess(stream.orderId);

            for (OrderEventResponse event : OrderResponseMapper.toEventResponses(events)) {
                if (!stream.send(String.valueOf(event.id()), EVENT, event)) {
                    forget(stream);
                    return;
                }
                stream.lastEventId = event.id();
            }

            // Статус — после событий, и только когда есть что сообщить: первый кадр потока
            // или новые события. Повторный сигнал без новостей клиенту ничего не шлёт.
            if (stream.firstFrame || !events.isEmpty()) {
                stream.firstFrame = false;
                if (!stream.send(null, STATUS, new LiveOrderStatus(access.orderId(), access.status().getDbValue()))) {
                    forget(stream);
                    return;
                }
            }

            // Из финального статуса переходов нет: держать поток дальше незачем.
            if (access.status().isFinal()) {
                stream.send(null, END, new LiveOrderStatus(access.orderId(), access.status().getDbValue()));
                stream.complete();
                forget(stream);
            }
        } catch (RuntimeException exception) {
            log.warn("Живые обновления: не удалось дочитать заказ orderId={}: {}",
                    stream.orderId, exception.getMessage());
        } finally {
            stream.lock().unlock();
        }
    }

    private void forget(OrderStream stream) {
        Set<OrderStream> streams = byOrder.get(stream.orderId);
        if (streams != null && streams.remove(stream)) {
            limiter.release();
            if (streams.isEmpty()) {
                byOrder.remove(stream.orderId, streams);
            }
        }
    }

    /** Статус заказа в кадре {@code status} и {@code end}. */
    public record LiveOrderStatus(Long orderId, String status) {
    }

    private static final class OrderStream extends SseStream {

        private final Long orderId;
        private long lastEventId;
        private boolean firstFrame = true;

        private OrderStream(SseEmitter emitter, Long orderId, long lastEventId) {
            super(emitter);
            this.orderId = orderId;
            this.lastEventId = lastEventId;
        }
    }
}
