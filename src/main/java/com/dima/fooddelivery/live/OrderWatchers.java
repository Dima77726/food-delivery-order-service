package com.dima.fooddelivery.live;

import com.dima.fooddelivery.grpc.order.v1.OrderEventView;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEvent;
import com.dima.fooddelivery.order.persistence.OrderEventRepository;
import com.dima.fooddelivery.order.service.OrderStatusService;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * gRPC server streaming {@code WatchOrder}: тот же канал живых обновлений, что у SSE клиента
 * ({@link OrderStreams}), только поток — gRPC, а читатель — другой сервис, а не браузер.
 *
 * <p><b>Обратное давление (backpressure).</b> {@code onNext} в gRPC не блокируется: если клиент
 * читает медленно, сообщения копятся в буфере сервера, и медленный читатель может съесть память.
 * Поэтому отправка идёт, только пока {@link ServerCallStreamObserver#isReady()}; остальное ждёт в
 * очереди наблюдателя, а {@code onReadyHandler} дописывает её, когда транспорт освободился. Поток
 * событий одного заказа короткий, но правило то же, что для любого стриминга.
 *
 * <p>Схема «сигнал + чтение», как у SSE: на сигнал из PostgreSQL дочитываются события новее
 * последнего отправленного, а не пересылается содержимое сигнала.
 */
@Slf4j
@Component
public class OrderWatchers implements LiveChannel {

    private final Map<Long, Set<Watcher>> byOrder = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("live-grpc-", 0).daemon().factory());

    private final OrderEventRepository orderEventRepository;
    private final OrderStatusService orderStatusService;

    public OrderWatchers(OrderEventRepository orderEventRepository, OrderStatusService orderStatusService) {
        this.orderEventRepository = orderEventRepository;
        this.orderStatusService = orderStatusService;
    }

    public void watch(long orderId, long afterEventId, ServerCallStreamObserver<OrderEventView> observer) {
        Watcher watcher = new Watcher(orderId, afterEventId, observer);
        byOrder.computeIfAbsent(orderId, id -> ConcurrentHashMap.newKeySet()).add(watcher);

        observer.setOnCancelHandler(() -> forget(watcher));
        observer.setOnReadyHandler(() -> executor.execute(() -> drain(watcher)));

        executor.execute(() -> catchUp(watcher));
    }

    @Override
    public void onSignal(LiveSignal signal) {
        if (signal.isDeliveries()) {
            return;
        }
        Set<Watcher> watchers = byOrder.get(signal.orderId());
        if (watchers != null) {
            watchers.forEach(watcher -> executor.execute(() -> catchUp(watcher)));
        }
    }

    @Override
    public void resync() {
        byOrder.values().forEach(watchers -> watchers.forEach(watcher -> executor.execute(() -> catchUp(watcher))));
    }

    @Override
    public void closeAll() {
        byOrder.values().forEach(watchers -> watchers.forEach(watcher -> {
            try {
                watcher.observer.onError(Status.UNAVAILABLE.withDescription("Сервис останавливается, переподключитесь").asRuntimeException());
            } catch (RuntimeException ignored) {
                // клиент уже ушёл
            }
        }));
        byOrder.clear();
    }

    int watching(long orderId) {
        Set<Watcher> watchers = byOrder.get(orderId);
        return watchers == null ? 0 : watchers.size();
    }

    private void catchUp(Watcher watcher) {
        watcher.lock.lock();
        try {
            if (watcher.done) {
                return;
            }
            List<OrderEvent> events = orderEventRepository.findByOrderIdAfter(watcher.orderId, watcher.lastQueuedEventId);
            OrderAccess access = orderStatusService.requireAccess(watcher.orderId);
            for (OrderEvent event : events) {
                watcher.pending.add(OrderEventView.newBuilder()
                        .setEventId(event.id())
                        .setOrderId(event.orderId())
                        .setEventType(event.eventType().getDbValue())
                        .setDescription(event.description() == null ? "" : event.description())
                        .setStatus(access.status().getDbValue())
                        .setCreatedAt(event.createdAt().toString())
                        .build());
                watcher.lastQueuedEventId = event.id();
            }
            watcher.finalReached = access.status().isFinal();
        } catch (RuntimeException exception) {
            log.warn("gRPC WatchOrder: не удалось дочитать заказ {}: {}", watcher.orderId, exception.getMessage());
        } finally {
            watcher.lock.unlock();
        }
        drain(watcher);
    }

    /** Отправляет накопленное, пока транспорт готов; на финальном статусе закрывает поток. */
    private void drain(Watcher watcher) {
        watcher.lock.lock();
        try {
            if (watcher.done) {
                return;
            }
            while (!watcher.pending.isEmpty() && watcher.observer.isReady()) {
                watcher.observer.onNext(watcher.pending.poll());
            }
            if (watcher.pending.isEmpty() && watcher.finalReached) {
                watcher.done = true;
                watcher.observer.onCompleted();
                forget(watcher);
            }
        } catch (RuntimeException exception) {
            // Клиент отменил вызов между проверкой и отправкой.
            watcher.done = true;
            forget(watcher);
        } finally {
            watcher.lock.unlock();
        }
    }

    private void forget(Watcher watcher) {
        Set<Watcher> watchers = byOrder.get(watcher.orderId);
        if (watchers != null && watchers.remove(watcher) && watchers.isEmpty()) {
            byOrder.remove(watcher.orderId, watchers);
        }
    }

    private static final class Watcher {
        private final long orderId;
        private final ServerCallStreamObserver<OrderEventView> observer;
        private final Queue<OrderEventView> pending = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock();
        private long lastQueuedEventId;
        private boolean finalReached;
        private boolean done;

        private Watcher(long orderId, long afterEventId, ServerCallStreamObserver<OrderEventView> observer) {
            this.orderId = orderId;
            this.lastQueuedEventId = afterEventId;
            this.observer = observer;
        }
    }
}
