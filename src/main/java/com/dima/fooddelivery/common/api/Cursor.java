package com.dima.fooddelivery.common.api;

import com.dima.fooddelivery.common.exception.InvalidCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Позиция в отсортированном списке: та самая пара {@code (created_at, id)}, по которой идёт
 * сортировка. Курсор указывает на последнюю уже отданную строку, а не на «номер страницы».
 *
 * <p>Зачем это вместо OFFSET. При {@code LIMIT 20 OFFSET 10000} база честно читает и отбрасывает
 * десять тысяч строк — чем дальше листает клиент, тем медленнее ответ. Условие
 * {@code (created_at, id) < (?, ?)} по тому же индексу, что и ORDER BY, попадает в нужное место
 * за один поиск, и цена страницы не зависит от её удалённости от начала.
 *
 * <p>Второе отличие важнее первого: между запросом десятой и одиннадцатой страницы список
 * меняется. Новый заказ сдвигает всё вниз, и клиент с OFFSET увидит одну и ту же строку дважды,
 * а удаление строки — наоборот, заставит его пропустить соседнюю. Курсор привязан к содержимому,
 * а не к порядковому номеру, поэтому вставки выше по списку на выдачу не влияют.
 *
 * <p>Наружу курсор уходит base64url-строкой. Это не защита — раскодировать её тривиально, —
 * а знак клиенту: значение непрозрачное, его нельзя собирать руками и на его формат нельзя
 * закладываться. Формат внутри поменяется вместе с сортировкой, и сломать этим чужой код
 * не должно быть возможно.
 *
 * <p>Подделка курсора ничего не даёт: выборка всегда дополнительно ограничена владельцем
 * (customerId или restaurantId), и произвольная пара «дата + id» лишь сдвинет окно внутри
 * собственных данных вызывающего.
 */
public record Cursor(OffsetDateTime createdAt, long id) {

    /**
     * Длина закодированной метки времени с идентификатором — около 50 символов. Ограничение
     * с запасом отсекает попытку скормить декодеру мегабайт base64 вместо курсора.
     */
    public static final int MAX_ENCODED_LENGTH = 200;

    private static final char SEPARATOR = '|';

    public String encode() {
        String payload = createdAt.toInstant() + String.valueOf(SEPARATOR) + id;

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Разбирает курсор из query-параметра.
     *
     * @param raw значение параметра; {@code null} или пустая строка означают «с начала списка»
     * @return разобранный курсор или {@code null}, если запрошена первая страница
     * @throws InvalidCursorException если строка не является курсором этого приложения
     */
    public static Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        if (raw.length() > MAX_ENCODED_LENGTH) {
            throw new InvalidCursorException("Курсор длиннее " + MAX_ENCODED_LENGTH + " символов");
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCursorException("Курсор не является строкой base64url");
        }

        int separator = payload.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new InvalidCursorException("Курсор не содержит идентификатор");
        }

        try {
            Instant createdAt = Instant.parse(payload.substring(0, separator));
            long id = Long.parseLong(payload.substring(separator + 1));

            return new Cursor(createdAt.atOffset(ZoneOffset.UTC), id);
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw new InvalidCursorException("Курсор повреждён и не может быть разобран");
        }
    }
}
