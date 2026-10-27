package com.dima.fooddelivery.promo;

import com.dima.fooddelivery.cart.service.CartService;
import com.dima.fooddelivery.pricing.SurgePricing;
import com.dima.fooddelivery.promo.PromoRepository.PromoCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Промокоды (администратор) и цены для корзины (клиент): сколько скинет код и сколько стоит доставка сейчас.
 */
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Pricing", description = "Промокоды и цена доставки")
@SecurityRequirement(name = "bearer-jwt")
public class PromoController {

    private final PromoService promoService;
    private final CartService cartService;
    private final SurgePricing surgePricing;

    @GetMapping("/api/v1/delivery-fee")
    @Operation(summary = "Цена доставки сейчас: база × коэффициент спроса (фиксируется при оформлении)")
    public SurgePricing.Quote deliveryFee() {
        return surgePricing.current();
    }

    @PostMapping("/api/v1/customers/{customerId}/restaurants/{restaurantId}/promo-check")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Проверить промокод для активной корзины: сколько скинет (409 с причиной, если не подходит)")
    public PromoCheck check(@PathVariable Long customerId, @PathVariable Long restaurantId, @Valid @RequestBody CodeRequest request) {
        BigDecimal itemsTotal = cartService.requireActiveCart(customerId, restaurantId).totalAmount();
        BigDecimal discount = promoService.preview(request.code(), customerId, restaurantId, itemsTotal);
        return new PromoCheck(PromoService.normalize(request.code()), itemsTotal, discount);
    }

    @PostMapping("/api/v1/admin/promo-codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создать промокод")
    public void create(@Valid @RequestBody CreatePromoRequest request) {
        promoService.create(new PromoCode(PromoService.normalize(request.code()), request.type(), request.value(),
                request.maxDiscount(), request.minOrderAmount() == null ? BigDecimal.ZERO : request.minOrderAmount(),
                request.restaurantId(), request.maxUses(), 0, request.validFrom(), request.validUntil(), true));
    }

    @GetMapping("/api/v1/admin/promo-codes")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Все промокоды с числом использований")
    public List<PromoCode> list() {
        return promoService.list();
    }

    @DeleteMapping("/api/v1/admin/promo-codes/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Выключить промокод (погашения остаются в истории)")
    public void deactivate(@PathVariable String code) {
        promoService.deactivate(code);
    }

    public record CodeRequest(@NotBlank String code) {
    }

    public record PromoCheck(String code, BigDecimal itemsTotal, BigDecimal discount) {
    }

    public record CreatePromoRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,40}$", message = "Код — 3–40 латинских букв, цифр, _ или -") String code,
            @NotNull PromoCode.Type type,
            @NotNull @DecimalMin("0.01") BigDecimal value,
            @DecimalMin("0.01") BigDecimal maxDiscount,
            @DecimalMin("0.00") BigDecimal minOrderAmount,
            Long restaurantId,
            @Min(1) int maxUses,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil
    ) {
    }
}
