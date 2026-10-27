package com.dima.fooddelivery.settlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Расчёты: ресторан видит свои строки (JSON и CSV), администратор запускает расчёт за день.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Settlement", description = "Ежедневные расчёты с ресторанами (Spring Batch)")
@SecurityRequirement(name = "bearer-jwt")
public class SettlementController {

    private final SettlementService service;
    private final SettlementRepository repository;

    @GetMapping("/api/v1/restaurants/{restaurantId}/settlements")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Расчёты ресторана за период, новые первыми")
    public List<SettlementLine> list(
            @PathVariable Long restaurantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        LocalDate end = to == null ? LocalDate.now() : to;
        return repository.findByRestaurant(restaurantId, from == null ? end.minusDays(30) : from, end);
    }

    @GetMapping(path = "/api/v1/restaurants/{restaurantId}/settlements.csv", produces = "text/csv")
    @PreAuthorize("@access.managesRestaurant(#restaurantId)")
    @Operation(summary = "Те же расчёты файлом CSV")
    public ResponseEntity<byte[]> csv(
            @PathVariable Long restaurantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        byte[] body = SettlementExporter.csv(list(restaurantId, from, to)).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"settlements-" + restaurantId + ".csv\"")
                .body(body);
    }

    @PostMapping("/api/v1/admin/settlements/{date}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Посчитать день (409, если уже посчитан и rerun не задан)")
    public SettlementService.Result run(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean rerun
    ) {
        return service.run(date, rerun);
    }
}
