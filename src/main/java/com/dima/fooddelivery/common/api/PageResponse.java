package com.dima.fooddelivery.common.api;

import java.util.List;

/**
 * Страница результатов.
 *
 * <p>Свой тип, а не {@code org.springframework.data.domain.Page}: Spring Data в проекте нет,
 * а тащить её ради одного класса — значит получить в ответе два десятка служебных полей
 * ({@code pageable}, {@code sort}, {@code first}, {@code numberOfElements} и прочее),
 * которые придётся объяснять каждому потребителю API.
 *
 * <p>{@code totalElements} стоит отдельного запроса COUNT. Это осознанная плата: без него
 * клиент не может показать «страница 3 из 12» и не знает, когда список кончился.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
