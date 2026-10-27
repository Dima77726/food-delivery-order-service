package com.dima.fooddelivery.live;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Потолок открытых потоков SSE на экземпляр и метрика {@code live.streams.open}.
 *
 * <p>Поток SSE дешевле потока ОС — после возврата из контроллера он не держит поток Tomcat, —
 * но не бесплатен: открытый сокет, буфер, запись в реестре. Без потолка одна страница,
 * открытая в сотне вкладок, или клиент с ошибкой в переподключении заняли бы всё.
 * Лучше честный 503 с {@code Retry-After}, чем медленная смерть экземпляра.
 */
@Component
public class StreamLimiter {

    private final AtomicInteger open = new AtomicInteger();
    private final int max;

    public StreamLimiter(LiveProperties properties, MeterRegistry meterRegistry) {
        this.max = properties.maxStreams();
        Gauge.builder("live.streams.open", open, AtomicInteger::get)
                .description("Открытые потоки SSE")
                .register(meterRegistry);
    }

    /** @return {@code false}, если потолок достигнут и поток открывать нельзя */
    boolean tryAcquire() {
        while (true) {
            int current = open.get();
            if (current >= max) {
                return false;
            }
            if (open.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void release() {
        open.decrementAndGet();
    }
}
