package com.dima.fooddelivery.ledger;

import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.function.Supplier;

/**
 * Якоря журнала аудита в блокчейне: список, проверка пачки и внеочередное заверение.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ledger.enabled", havingValue = "true")
@Tag(name = "Audit anchors", description = "Журнал аудита, заверенный в блокчейне (Merkle-корни в контракте AuditAnchor)")
@SecurityRequirement(name = "bearer-jwt")
public class AuditAnchorController {

    private final AuditAnchorService service;

    @GetMapping("/api/v1/admin/audit-anchors")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Последние заверенные пачки журнала, новые первыми")
    public List<AuditAnchor> list(@RequestParam(defaultValue = "20") int limit) {
        return service.latest(Math.clamp(limit, 1, 200));
    }

    @GetMapping("/api/v1/admin/audit-anchors/{id}/verification")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Пересчитать корень пачки из базы и сверить с блокчейном: VALID, TAMPERED или CHAIN_UNAVAILABLE")
    public AuditAnchorService.Verification verify(@PathVariable long id) {
        return onChain(() -> service.verify(id));
    }

    @PostMapping("/api/v1/admin/audit-anchors/run")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Заверить накопившиеся записи сейчас, не дожидаясь расписания")
    public List<AuditAnchor> run() {
        return onChain(() -> service.anchorPending(20));
    }

    private static <T> T onChain(Supplier<T> action) {
        try {
            return action.get();
        } catch (LedgerException | RestClientException exception) {
            throw new UpstreamUnavailableException("Блокчейн-узел недоступен: " + exception.getMessage(), exception);
        }
    }
}
