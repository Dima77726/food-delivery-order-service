package com.dima.fooddelivery.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Параметры постраничного запроса, привязываемые из query string.
 *
 * <p>Поля объявлены как {@link Integer}, а не {@code int}, ради компактного конструктора:
 * у примитива нельзя отличить «параметр не прислали» от «прислали 0». Подстановка значений
 * по умолчанию происходит до валидации, поэтому запрос вообще без параметров проходит.
 *
 * <p>Верхняя граница размера страницы обязательна. Без неё клиент присылает {@code size=1000000}
 * и превращает безобидный GET в способ выгрести таблицу целиком, заодно положив приложение
 * на сборке ответа в памяти.
 */
public record PageRequestParams(

        @Min(value = 0, message = "номер страницы не может быть отрицательным")
        @Schema(description = "Номер страницы, с нуля", defaultValue = "0")
        Integer page,

        @Min(value = 1, message = "размер страницы должен быть не меньше 1")
        @Max(value = MAX_PAGE_SIZE, message = "размер страницы не больше " + MAX_PAGE_SIZE)
        @Schema(description = "Размер страницы", defaultValue = "20")
        Integer size
) {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public PageRequestParams {
        page = page == null ? 0 : page;
        size = size == null ? DEFAULT_PAGE_SIZE : size;
    }

    public int offset() {
        return page * size;
    }
}
