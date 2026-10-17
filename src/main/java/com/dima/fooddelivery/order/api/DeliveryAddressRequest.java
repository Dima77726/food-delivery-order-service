package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.domain.DeliveryAddress;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Адрес доставки от клиента: строка адреса обязательна, уточнение и точка на карте — нет.
 */
public record DeliveryAddressRequest(
        @NotBlank(message = "адрес доставки обязателен")
        @Size(max = 500, message = "адрес доставки не длиннее 500 символов")
        String address,

        @Size(max = 200, message = "уточнение адреса не длиннее 200 символов")
        String details,

        @DecimalMin(value = "-90", message = "широта от -90 до 90")
        @DecimalMax(value = "90", message = "широта от -90 до 90")
        BigDecimal latitude,

        @DecimalMin(value = "-180", message = "долгота от -180 до 180")
        @DecimalMax(value = "180", message = "долгота от -180 до 180")
        BigDecimal longitude
) {

    @AssertTrue(message = "широта и долгота задаются вместе")
    public boolean isPointComplete() {
        return (latitude == null) == (longitude == null);
    }

    /**
     * Шесть знаков после запятой — около десяти сантиметров, точнее карта всё равно не покажет.
     */
    public DeliveryAddress toDomain() {
        return new DeliveryAddress(
                address.strip(),
                details == null || details.isBlank() ? null : details.strip(),
                latitude == null ? null : latitude.setScale(6, RoundingMode.HALF_UP),
                longitude == null ? null : longitude.setScale(6, RoundingMode.HALF_UP)
        );
    }
}
