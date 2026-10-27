package com.dima.fooddelivery.live;

import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.delivery.service.DeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/**
 * Long polling для курьеров: «ответь, когда список свободных доставок изменится».
 *
 * <p>Версию списка клиент передаёт стандартным {@code If-None-Match}, сервер отдаёт её в
 * {@code ETag}. Отличается версия — ответ сразу. Совпадает — запрос паркуется
 * ({@link DeferredResult}: поток Tomcat освобождается, держится только соединение) до сигнала
 * об изменении или до таймаута, после которого уходит {@code 304 Not Modified}. Клиент тут же
 * повторяет запрос — получается почти мгновенная реакция без постоянного соединения.
 *
 * <p>Против «громового стада» (thundering herd): сигнал будит сразу всех ждущих курьеров,
 * и если каждый пойдёт в базу за списком, один сигнал превратится в сотню одинаковых запросов.
 * Поэтому список по сигналу читается <em>один раз</em> и раздаётся всем, а сигналы, пришедшие во
 * время чтения, схлопываются в одно повторное чтение.
 */
@Slf4j
@Component
public class DeliveryOfferWaiters implements LiveChannel {

    private final Set<Waiter> waiters = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("live-deliveries").daemon().factory());

    private final DeliveryService deliveryService;

    public DeliveryOfferWaiters(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * @param knownEtag версия списка, которая уже есть у клиента, или {@code null}
     */
    public DeferredResult<ResponseEntity<List<DeliveryResponse>>> await(String knownEtag, Duration timeout) {
        List<DeliveryResponse> current = deliveryService.getAvailableDeliveries();
        String etag = etagOf(current);

        DeferredResult<ResponseEntity<List<DeliveryResponse>>> result = new DeferredResult<>(timeout.toMillis());

        if (!etag.equals(knownEtag)) {
            result.setResult(ok(current, etag));
            return result;
        }

        Waiter waiter = new Waiter(result, etag);
        waiters.add(waiter);

        result.onTimeout(() -> result.setResult(ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build()));
        result.onCompletion(() -> waiters.remove(waiter));

        // Сигнал мог прийти между чтением списка выше и регистрацией: перепроверка закрывает эту щель.
        scheduleRefresh();

        return result;
    }

    @Override
    public void onSignal(LiveSignal signal) {
        if (signal.isDeliveries()) {
            scheduleRefresh();
        }
    }

    @Override
    public void resync() {
        scheduleRefresh();
    }

    @Override
    public void closeAll() {
        // Пустой 304 вместо обрыва: клиент повторит запрос уже к другому экземпляру.
        waiters.forEach(waiter -> waiter.result.setResult(
                ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(waiter.etag).build()));
        waiters.clear();
    }

    int waiting() {
        return waiters.size();
    }

    private void scheduleRefresh() {
        if (waiters.isEmpty() || !refreshScheduled.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::refresh);
    }

    private void refresh() {
        // Сброс флага до чтения: сигнал, пришедший во время чтения, запланирует ещё одно.
        refreshScheduled.set(false);

        try {
            List<DeliveryResponse> current = deliveryService.getAvailableDeliveries();
            String etag = etagOf(current);

            for (Waiter waiter : waiters) {
                if (!waiter.etag.equals(etag)) {
                    waiter.result.setResult(ok(current, etag));
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Живые обновления: не удалось перечитать свободные доставки: {}", exception.getMessage());
        }
    }

    private static ResponseEntity<List<DeliveryResponse>> ok(List<DeliveryResponse> deliveries, String etag) {
        return ResponseEntity.ok().eTag(etag).body(deliveries);
    }

    /**
     * Версия — контрольная сумма идентификаторов свободных доставок. Список меняется только
     * появлением и исчезновением доставок, поэтому идентификаторов достаточно.
     */
    static String etagOf(List<DeliveryResponse> deliveries) {
        CRC32 crc = new CRC32();
        crc.update(deliveries.stream()
                .map(delivery -> String.valueOf(delivery.id()))
                .collect(Collectors.joining(","))
                .getBytes(StandardCharsets.US_ASCII));
        return "\"" + Long.toHexString(crc.getValue()) + "-" + deliveries.size() + "\"";
    }

    private record Waiter(DeferredResult<ResponseEntity<List<DeliveryResponse>>> result, String etag) {
    }
}
