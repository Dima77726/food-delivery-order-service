package com.dima.fooddelivery.live;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.service.OrderStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;

/**
 * Живые обновления: потоки SSE и долгие запросы.
 *
 * <p>Три транспорта для трёх задач — сравнение и выбор описаны в {@code docs/system-design}
 * платформы:
 * <ul>
 *     <li><b>SSE клиента</b> — история и статус заказа. Поток односторонний, а нужен именно он:
 *     клиент только смотрит. SSE идёт поверх обычного HTTP, проходит прокси без настройки
 *     протокола и умеет продолжать с места обрыва ({@code Last-Event-ID});</li>
 *     <li><b>SSE доски ресторана</b> — fan-out одного изменения на все вкладки ресторана;</li>
 *     <li><b>long polling курьера</b> — «ответь, когда появится доставка». Ответ — обычный
 *     JSON со списком, тот же, что у {@code GET /deliveries/available}: клиент, который не умеет
 *     ждать, работает с тем же ответом.</li>
 * </ul>
 *
 * <p>Ответы SSE несут {@code X-Accel-Buffering: no}: nginx перед шлюзом иначе копил бы кадры
 * в буфере и отдавал их пачкой.
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@EnableConfigurationProperties(LiveProperties.class)
@Tag(name = "Live", description = "Живые обновления: SSE и long polling")
@SecurityRequirement(name = "bearer-jwt")
public class LiveController {

    private static final String RETRY_AFTER_SECONDS = "5";

    private final OrderStreams orderStreams;
    private final RestaurantBoardStreams restaurantBoardStreams;
    private final DeliveryOfferWaiters deliveryOfferWaiters;
    private final OrderStatusService orderStatusService;
    private final LiveProperties properties;

    @GetMapping(path = "/api/v1/customers/{customerId}/orders/{orderId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Поток SSE заказа: события истории (order-event), статус (status), конец (end)")
    public ResponseEntity<SseEmitter> streamOrder(
            @Positive @PathVariable Long customerId,
            @Positive @PathVariable Long orderId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId
    ) {
        OrderAccess access = orderStatusService.requireAccess(orderId);
        if (!access.belongsToCustomer(customerId)) {
            // 404, а не 403: чужой заказ неотличим от несуществующего.
            throw new ResourceNotFoundException("Заказ с id=" + orderId + " не найден для customerId=" + customerId);
        }

        log.info("Поток заказа открыт: customerId={}, orderId={}, lastEventId={}", customerId, orderId, lastEventId);

        return stream(orderStreams.open(orderId, lastEventId));
    }

    @GetMapping(path = "/api/v1/restaurants/{restaurantId}/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Поток SSE доски ресторана: заказ сменил статус (order)")
    public ResponseEntity<SseEmitter> streamRestaurantBoard(@Positive @PathVariable Long restaurantId) {
        log.info("Поток доски открыт: restaurantId={}", restaurantId);

        return stream(restaurantBoardStreams.open(restaurantId));
    }

    @GetMapping("/api/v1/deliveries/available/poll")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Long polling свободных доставок: ответ при изменении списка или 304 по таймауту")
    public DeferredResult<ResponseEntity<List<DeliveryResponse>>> pollAvailableDeliveries(
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @Min(1) @Max(60) @RequestParam(defaultValue = "25") int timeoutSeconds
    ) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        if (timeout.compareTo(properties.maxPollTimeout()) > 0) {
            timeout = properties.maxPollTimeout();
        }

        return deliveryOfferWaiters.await(ifNoneMatch, timeout);
    }

    private static ResponseEntity<SseEmitter> stream(SseEmitter emitter) {
        if (emitter == null) {
            log.warn("Живые обновления: достигнут потолок открытых потоков");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
