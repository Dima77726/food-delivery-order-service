package com.dima.fooddelivery.admin.api;

import com.dima.fooddelivery.audit.api.AuditEntryResponse;
import com.dima.fooddelivery.audit.api.AuditResponseMapper;
import com.dima.fooddelivery.audit.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Журнал аудита действий с заказами.
 *
 * <p>Переехал из монолита вместе с заказами: шлюз отправляет сюда {@code /api/v1/admin/audit/**}.
 * Управление пользователями — в identity-service ({@code /api/v1/admin/users/**}).
 *
 * <p>Путь {@code /api/v1/admin/**} закрыт ролью ADMIN ещё в {@code SecurityConfig} — на уровне
 * фильтра, до того как запрос дойдёт до контроллера. Дублирующий {@code @PreAuthorize} на классе
 * стоит намеренно: если кто-то однажды перепишет цепочку фильтров и забудет про этот префикс,
 * вторая проверка не даст админке открыться всем подряд.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Служебные операции: журнал аудита")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final int MAX_AUDIT_PAGE_SIZE = 500;

    private final AuditService auditService;

    @GetMapping("/audit")
    @Operation(summary = "Последние записи журнала аудита")
    public List<AuditEntryResponse> getAuditLog(
            @RequestParam(defaultValue = "100")
            @Positive
            @Max(value = MAX_AUDIT_PAGE_SIZE, message = "limit не больше " + MAX_AUDIT_PAGE_SIZE)
            int limit
    ) {
        return AuditResponseMapper.toResponses(auditService.getRecent(limit));
    }

    @GetMapping("/audit/resources/{resourceType}/{resourceId}")
    @Operation(summary = "История действий над конкретным ресурсом")
    public List<AuditEntryResponse> getAuditForResource(
            @PathVariable String resourceType,
            @PathVariable String resourceId,
            @RequestParam(defaultValue = "100")
            @Positive
            @Max(value = MAX_AUDIT_PAGE_SIZE, message = "limit не больше " + MAX_AUDIT_PAGE_SIZE)
            int limit
    ) {
        return AuditResponseMapper.toResponses(
                auditService.getByResource(resourceType, resourceId, limit)
        );
    }
}
