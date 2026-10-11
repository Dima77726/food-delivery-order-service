package com.dima.fooddelivery.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Проверки владения ресурсом для {@code @PreAuthorize}.
 *
 * <p>Бин называется {@code access}, чтобы в аннотациях получалось читаемо:
 * {@code @PreAuthorize("@access.isSelf(#customerId)")}.
 *
 * <p>Зачем это нужно: в URL проекта идентификатор владельца стоит прямо в пути —
 * {@code /api/v1/customers/{customerId}/orders}. Без такой проверки любой залогиненный
 * клиент подставил бы чужой {@code customerId} и прочитал чужие заказы. Роль CUSTOMER
 * у него при этом есть, так что {@code hasRole} тут не спасает — нужно сверять
 * идентификатор из пути с идентификатором из токена.
 *
 * <p>{@link #isSelf} и {@link #isAdmin} решаются по токену, без сетевых вызовов.
 * {@link #managesRestaurant} — вопрос к сервису пользователей по gRPC: список ресторанов
 * владельца меняется чаще, чем живёт токен, и класть его в токен нельзя.
 */
@Slf4j
@Component("access")
@RequiredArgsConstructor
public class ResourceAccessGuard {

    private static final String ADMIN_AUTHORITY = SecurityConfig.ROLE_PREFIX + SecurityConfig.ADMIN_ROLE;

    private final CurrentUser currentUser;
    private final RestaurantAccessClient restaurantAccessClient;

    /** Пользователь запрашивает собственные данные (или это администратор). */
    public boolean isSelf(Long userId) {
        if (isAdmin()) {
            return true;
        }

        boolean self = currentUser.id().map(id -> id.equals(userId)).orElse(false);

        if (!self) {
            log.warn(
                    "Попытка обратиться к чужим данным: requestedUserId={}, currentUserId={}",
                    userId,
                    currentUser.id().orElse(null)
            );
        }

        return self;
    }

    /** Пользователь управляет этим рестораном (или это администратор). */
    public boolean managesRestaurant(Long restaurantId) {
        if (isAdmin()) {
            return true;
        }

        return currentUser.id()
                .map(userId -> restaurantAccessClient.managesRestaurant(userId, restaurantId))
                .orElse(false);
    }

    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
