package com.dima.fooddelivery.dispatch;

import com.dima.fooddelivery.delivery.api.DeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Курьер на линии, предложения доставок и заработок. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Dispatch", description = "Автоназначение курьера: присутствие, предложения, заработок")
@SecurityRequirement(name = "bearer-jwt")
public class DispatchController {

    private final CourierPresence presence;
    private final DispatchService dispatchService;

    /**
     * Приложение курьера шлёт отметку раз в 30–60 секунд, пока он на линии: она обновляет точку в гео-индексе
     * и продлевает пульс. Перестал слать — через 90 секунд курьер выпадает из поиска сам.
     */
    @PutMapping("/api/v1/couriers/{courierId}/presence")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "На линии (с координатами) или ушёл с линии")
    public Map<String, Object> presence(@PathVariable Long courierId, @Valid @RequestBody Presence body) {
        if (body.online()) {
            presence.online(courierId, body.latitude(), body.longitude());
        } else {
            presence.offline(courierId);
        }
        return Map.of("online", presence.isOnline(courierId));
    }

    @GetMapping("/api/v1/couriers/{courierId}/presence")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    public Map<String, Object> presence(@PathVariable Long courierId) {
        return Map.of("online", presence.isOnline(courierId));
    }

    @GetMapping("/api/v1/couriers/{courierId}/offers")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Действующие предложения доставок этому курьеру")
    public List<DispatchService.Offer> offers(@PathVariable Long courierId) {
        return dispatchService.offersFor(courierId);
    }

    @PostMapping("/api/v1/couriers/{courierId}/offers/{offerId}/accept")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Принять предложение: доставка назначается курьеру")
    public DeliveryResponse accept(@PathVariable Long courierId, @PathVariable Long offerId) {
        return dispatchService.accept(courierId, offerId);
    }

    @PostMapping("/api/v1/couriers/{courierId}/offers/{offerId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Отказаться: предложение уйдёт следующему курьеру")
    public void decline(@PathVariable Long courierId, @PathVariable Long offerId) {
        dispatchService.decline(courierId, offerId);
    }

    @GetMapping("/api/v1/couriers/{courierId}/earnings")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Доставлено и чаевые")
    public DispatchService.Earnings earnings(@PathVariable Long courierId) {
        return dispatchService.earnings(courierId);
    }

    public record Presence(
            boolean online,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude
    ) {
    }
}
