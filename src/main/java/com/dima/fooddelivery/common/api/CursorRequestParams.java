package com.dima.fooddelivery.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Параметры курсорного запроса: сколько отдать и с какого места продолжить.
 *
 * <p>Номера страницы здесь нет и быть не может — в этом весь смысл: клиент не выбирает
 * произвольное место в списке, он идёт по нему подряд, предъявляя {@code nextCursor}
 * из предыдущего ответа. Первая страница запрашивается без курсора.
 *
 * <p>Ограничения размера те же, что у {@link PageRequestParams}: два способа листать один
 * и тот же список не должны иметь разные потолки, иначе клиент обойдёт лимит, просто
 * переключившись на другой эндпоинт.
 */
public record CursorRequestParams(

        @Size(max = Cursor.MAX_ENCODED_LENGTH, message = "курсор слишком длинный")
        @Schema(description = "Курсор из поля nextCursor предыдущего ответа; пусто — начать сначала")
        String cursor,

        @Min(value = 1, message = "размер страницы должен быть не меньше 1")
        @Max(value = PageRequestParams.MAX_PAGE_SIZE,
                message = "размер страницы не больше " + PageRequestParams.MAX_PAGE_SIZE)
        @Schema(description = "Сколько записей вернуть", defaultValue = "20")
        Integer size
) {

    public CursorRequestParams {
        size = size == null ? PageRequestParams.DEFAULT_PAGE_SIZE : size;
    }

    /**
     * @return позиция, с которой продолжать, или {@code null} для первой страницы
     */
    public Cursor decoded() {
        return Cursor.decode(cursor);
    }
}
