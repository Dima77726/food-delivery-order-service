package com.dima.fooddelivery.common.api;

import java.util.List;

/**
 * Порция результатов и метка, с которой продолжать.
 *
 * <p>Чем отличается от {@link PageResponse}: здесь нет ни номера страницы, ни
 * {@code totalElements}, ни {@code totalPages}. Это не экономия, а следствие модели — при
 * листании курсором «страница 3 из 12» не имеет смысла: список живёт, и общее число строк,
 * посчитанное на первом запросе, к третьему уже неверно. Заодно уходит COUNT по всей выборке,
 * который в постраничном варианте выполняется на каждый запрос.
 *
 * <p>{@code nextCursor} равен {@code null}, когда список закончился. Отдельный {@code hasNext}
 * дублирует эту информацию сознательно: сравнение с {@code null} легко потерять в клиентском
 * коде, а цикл «пока hasNext» читается однозначно.
 */
public record CursorPageResponse<T>(
        List<T> content,
        int size,
        String nextCursor,
        boolean hasNext
) {

    public static <T> CursorPageResponse<T> of(List<T> content, int size, String nextCursor) {
        return new CursorPageResponse<>(content, size, nextCursor, nextCursor != null);
    }
}
