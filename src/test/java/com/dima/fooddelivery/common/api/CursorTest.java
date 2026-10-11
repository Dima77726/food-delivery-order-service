package com.dima.fooddelivery.common.api;

import com.dima.fooddelivery.common.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Курсор — единственное, что клиент возвращает приложению в неизменном виде, поэтому кодирование
 * и разбор проверяются отдельно от базы: ошибка здесь проявилась бы как молчаливый сдвиг окна
 * выборки, а не как падение.
 */
class CursorTest {

    @Test
    void shouldSurviveEncodeDecodeRoundTrip() {
        Cursor original = new Cursor(OffsetDateTime.parse("2026-09-07T12:30:45.123456Z"), 42L);

        Cursor restored = Cursor.decode(original.encode());

        assertAll(
                () -> assertEquals(original.createdAt().toInstant(), restored.createdAt().toInstant()),
                () -> assertEquals(original.id(), restored.id())
        );
    }

    /**
     * timestamptz в PostgreSQL хранит микросекунды. Потеряй курсор последние разряды —
     * условие «строго дальше» перестало бы быть строгим, и заказ, созданный в ту же секунду,
     * приехал бы клиенту дважды.
     */
    @Test
    void shouldKeepMicrosecondPrecision() {
        Cursor original = new Cursor(OffsetDateTime.parse("2026-09-07T12:30:45.000001Z"), 1L);

        assertEquals(
                original.createdAt().toInstant(),
                Cursor.decode(original.encode()).createdAt().toInstant()
        );
    }

    /**
     * Смещение приводится к UTC: момент времени от этого не меняется, а сравнение с колонкой
     * timestamptz становится независимым от того, в какой зоне работал клиент.
     */
    @Test
    void shouldNormalizeOffsetToUtc() {
        Cursor original = new Cursor(OffsetDateTime.parse("2026-09-07T15:30:45+03:00"), 7L);

        Cursor restored = Cursor.decode(original.encode());

        assertAll(
                () -> assertEquals(ZoneOffset.UTC, restored.createdAt().getOffset()),
                () -> assertEquals(original.createdAt().toInstant(), restored.createdAt().toInstant())
        );
    }

    @Test
    void shouldTreatMissingCursorAsStartOfList() {
        assertAll(
                () -> assertNull(Cursor.decode(null)),
                () -> assertNull(Cursor.decode("")),
                () -> assertNull(Cursor.decode("   "))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "не base64 вовсе",
            "bm8tc2VwYXJhdG9y",                             // base64 строки без разделителя
            "MjAyNi0wOS0wN1QxMjozMDo0NVp8bm90LWEtbnVtYmVy", // id не число
            "bm90LWEtZGF0ZXwxMg"                            // дата не разбирается
    })
    void shouldRejectMalformedCursor(String raw) {
        assertThrows(InvalidCursorException.class, () -> Cursor.decode(raw));
    }

    /**
     * Длинную строку декодер обязан отвергнуть до попытки её разобрать: мегабайт
     * в query-параметре — это не курсор, а попытка занять память на разборе.
     */
    @Test
    void shouldRejectOverlongCursor() {
        String tooLong = "A".repeat(Cursor.MAX_ENCODED_LENGTH + 1);

        assertThrows(InvalidCursorException.class, () -> Cursor.decode(tooLong));
    }

    /**
     * Непрозрачность курсора — часть контракта: клиент не должен вычитывать из него дату и id
     * и строить на этом свои запросы.
     */
    @Test
    void shouldNotExposeInternalsInEncodedForm() {
        String encoded = new Cursor(OffsetDateTime.parse("2026-09-07T12:30:45Z"), 42L).encode();

        assertAll(
                () -> assertTrue(encoded.indexOf('|') < 0, "разделитель не должен торчать наружу"),
                () -> assertFalse(encoded.contains("2026"), "дата не должна читаться глазами")
        );
    }
}
