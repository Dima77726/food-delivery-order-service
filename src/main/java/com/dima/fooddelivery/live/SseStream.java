package com.dima.fooddelivery.live;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Поток SSE с замком.
 *
 * <p>В один поток пишут сразу несколько источников: догоняющее чтение после подписки, сигнал
 * из базы и пульс. Без замка их кадры могли бы перемешаться, а «последний отправленный
 * идентификатор» — разойтись с тем, что на самом деле ушло клиенту.
 */
class SseStream {

    private final SseEmitter emitter;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    SseStream(SseEmitter emitter) {
        this.emitter = emitter;
    }

    SseEmitter emitter() {
        return emitter;
    }

    ReentrantLock lock() {
        return lock;
    }

    boolean isClosed() {
        return closed;
    }

    void markClosed() {
        closed = true;
    }

    /** @return {@code false}, если клиент уже ушёл: поток закрыт и его пора забыть */
    boolean send(String id, String name, Object data) {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
        if (id != null) {
            event.id(id);
        }
        return send(event);
    }

    boolean heartbeat() {
        return send(SseEmitter.event().comment("keepalive"));
    }

    void complete() {
        closed = true;
        emitter.complete();
    }

    private boolean send(SseEmitter.SseEventBuilder event) {
        if (closed) {
            return false;
        }
        lock.lock();
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            // IOException — клиент закрыл соединение; IllegalStateException — поток уже завершён.
            closed = true;
            return false;
        } finally {
            lock.unlock();
        }
    }
}
