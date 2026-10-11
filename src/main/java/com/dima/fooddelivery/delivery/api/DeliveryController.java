package com.dima.fooddelivery.delivery.api;

import com.dima.fooddelivery.delivery.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Delivery", description = "Доставки и работа курьера")
@SecurityRequirement(name = "bearer-jwt")
public class DeliveryController {

    private final DeliveryService deliveryService;

    /**
     * Доставку создаёт ресторан, отметив заказ готовым. Курьер этого сделать не может —
     * иначе он назначал бы себе заказы, которых ещё нет на выдаче.
     */
    @PostMapping("/api/v1/restaurants/{restaurantId}/orders/{orderId}/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Создать доставку для готового заказа")
    public DeliveryResponse createDelivery(
            @Positive(message = "restaurantId должен быть положительным числом")
            @PathVariable Long restaurantId,

            @Positive(message = "orderId должен быть положительным числом")
            @PathVariable Long orderId
    ) {
        log.info("Создание доставки: restaurantId={}, orderId={}", restaurantId, orderId);

        return deliveryService.createDeliveryForOrder(orderId);
    }

    @GetMapping("/api/v1/deliveries/available")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Доставки, которые может взять курьер")
    public List<DeliveryResponse> getAvailableDeliveries() {
        return deliveryService.getAvailableDeliveries();
    }

    @GetMapping("/api/v1/couriers/{courierId}/deliveries")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Доставки курьера")
    public List<DeliveryResponse> getCourierDeliveries(
            @Positive(message = "courierId должен быть положительным числом")
            @PathVariable Long courierId
    ) {
        return deliveryService.getDeliveriesForCourier(courierId);
    }

    @PostMapping("/api/v1/couriers/{courierId}/deliveries/{deliveryId}/assign")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Взять доставку на себя")
    public DeliveryResponse assignCourierToDelivery(
            @Positive(message = "courierId должен быть положительным числом")
            @PathVariable Long courierId,

            @Positive(message = "deliveryId должен быть положительным числом")
            @PathVariable Long deliveryId
    ) {
        log.info("Назначение курьера: courierId={}, deliveryId={}", courierId, deliveryId);

        return deliveryService.assignCourierToDelivery(courierId, deliveryId);
    }

    @PostMapping("/api/v1/couriers/{courierId}/deliveries/{deliveryId}/pick-up")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Забрать заказ из ресторана")
    public DeliveryResponse pickUpDelivery(
            @Positive(message = "courierId должен быть положительным числом")
            @PathVariable Long courierId,

            @Positive(message = "deliveryId должен быть положительным числом")
            @PathVariable Long deliveryId
    ) {
        log.info("Забор заказа курьером: courierId={}, deliveryId={}", courierId, deliveryId);

        return deliveryService.pickUpDelivery(courierId, deliveryId);
    }

    @PostMapping("/api/v1/couriers/{courierId}/deliveries/{deliveryId}/deliver")
    @PreAuthorize("hasRole('COURIER') and @access.isSelf(#courierId)")
    @Operation(summary = "Завершить доставку")
    public DeliveryResponse deliverDelivery(
            @Positive(message = "courierId должен быть положительным числом")
            @PathVariable Long courierId,

            @Positive(message = "deliveryId должен быть положительным числом")
            @PathVariable Long deliveryId
    ) {
        log.info("Завершение доставки: courierId={}, deliveryId={}", courierId, deliveryId);

        return deliveryService.deliverDelivery(courierId, deliveryId);
    }
}
