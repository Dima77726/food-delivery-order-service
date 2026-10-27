package com.dima.fooddelivery.refund;

import com.dima.fooddelivery.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Заявки на частичный возврат: клиент подаёт, поддержка решает. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Refunds", description = "Частичные возвраты по позициям доставленного заказа")
@SecurityRequirement(name = "bearer-jwt")
public class RefundController {

    private final RefundService refundService;
    private final CurrentUser currentUser;

    @PostMapping("/api/v1/customers/{customerId}/orders/{orderId}/refund-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Подать заявку на возврат по позициям (мелкие одобряются сразу)")
    public RefundService.RefundRequest request(@PathVariable Long customerId, @PathVariable Long orderId, @Valid @RequestBody Request body) {
        return refundService.request(customerId, orderId,
                body.items().stream().map(i -> new RefundService.RequestedItem(i.orderItemId(), i.quantity())).toList(), body.reason());
    }

    @GetMapping("/api/v1/customers/{customerId}/orders/{orderId}/refund-requests")
    @PreAuthorize("hasRole('CUSTOMER') and @access.isSelf(#customerId)")
    @Operation(summary = "Заявки на возврат по заказу")
    public List<RefundService.RefundRequest> list(@PathVariable Long customerId, @PathVariable Long orderId) {
        return refundService.forOrder(customerId, orderId);
    }

    @GetMapping("/api/v1/admin/refund-requests")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Заявки, ждущие решения")
    public List<RefundService.RefundRequest> pending() {
        return refundService.pending();
    }

    @PostMapping("/api/v1/admin/refund-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Одобрить: команда возврата уходит в payment-service")
    public RefundService.RefundRequest approve(@PathVariable long id) {
        return refundService.approve(id, currentUser.requireId());
    }

    @PostMapping("/api/v1/admin/refund-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Отклонить")
    public RefundService.RefundRequest reject(@PathVariable long id) {
        return refundService.reject(id, currentUser.requireId());
    }

    public record Request(
            @NotEmpty List<@Valid Item> items,
            @NotBlank @Size(max = 500) String reason
    ) {
        public record Item(long orderItemId, @Min(1) int quantity) {
        }
    }
}
