package com.dima.fooddelivery.promo;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Фильтр Блума существующих промокодов в битовой карте Redis: «точно нет» без похода в базу.
 *
 * <p>Массив из {@code m} бит и {@code k} хеш-функций. Добавление ставит {@code k} бит; проверка смотрит
 * те же {@code k} бит. Все стоят — «возможно, есть» (иногда ложно); хоть один сброшен — «точно нет».
 * Ложных «нет» не бывает.
 *
 * <p><b>Зачем.</b> Промокоды перебирают ботами и вводят с опечатками. Несуществующий код отсекается
 * одним запросом к Redis (k GETBIT конвейером), а в базу идут только «возможно, есть». 65 536 бит
 * (8 КБ) и 4 хеша на тысячу кодов — около 0,0002% ложных срабатываний.
 *
 * <p><b>Почему в Redis, а не в памяти.</b> Фильтр в памяти каждого экземпляра узнал бы о новом коде,
 * созданном на соседнем, только при перестройке — и до неё отвечал бы ложным «нет», а это единственная
 * ошибка, которой у фильтра Блума быть не должно. Битовая карта в Redis общая: SETBIT при создании кода
 * сразу виден всем. (Модуль RedisBloom умеет то же командами BF.*, но в обычном Redis его нет.)
 *
 * <p>Удалять из фильтра нельзя (сброс бита задел бы чужие коды), поэтому он перестраивается целиком:
 * во временный ключ, затем RENAME — атомарная подмена, читатели не видят наполовину построенного.
 */
@Component
public class PromoBloomFilter {

    static final String KEY = "promo:bloom";
    static final int BITS = 1 << 16;
    static final int HASHES = 4;

    private final StringRedisTemplate redis;

    public PromoBloomFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void add(String code) {
        for (long offset : offsets(code)) {
            redis.opsForValue().setBit(KEY, offset, true);
        }
    }

    public boolean mightContain(String code) {
        long[] offsets = offsets(code);
        byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
        List<Object> bits = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (long offset : offsets) {
                connection.stringCommands().getBit(key, offset);
            }
            return null;
        });
        return bits.stream().allMatch(Boolean.TRUE::equals);
    }

    public void rebuild(Collection<String> codes) {
        String temp = KEY + ":building:" + System.nanoTime();
        for (String code : codes) {
            for (long offset : offsets(code)) {
                redis.opsForValue().setBit(temp, offset, true);
            }
        }
        if (codes.isEmpty()) {
            redis.delete(KEY);
        } else {
            redis.rename(temp, KEY);
        }
    }

    /** Двойное хеширование (Kirsch–Mitzenmacher): k позиций из двух хешей, h1 + i·h2. */
    static long[] offsets(String code) {
        byte[] bytes = code.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        long h1 = crc.getValue();
        long h2 = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h2 = (h2 ^ (b & 0xff)) * 0x100000001b3L;
        }
        h2 |= 1;
        long[] offsets = new long[HASHES];
        for (int i = 0; i < HASHES; i++) {
            offsets[i] = Math.floorMod(h1 + (long) i * h2, (long) BITS);
        }
        return offsets;
    }
}
