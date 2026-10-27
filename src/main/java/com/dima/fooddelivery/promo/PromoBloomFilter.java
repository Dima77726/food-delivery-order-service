package com.dima.fooddelivery.promo;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Р¤РёР»СЊС‚СЂ Р‘Р»СѓРјР° СЃСѓС‰РµСЃС‚РІСѓСЋС‰РёС… РїСЂРѕРјРѕРєРѕРґРѕРІ РІ Р±РёС‚РѕРІРѕР№ РєР°СЂС‚Рµ Redis: В«С‚РѕС‡РЅРѕ РЅРµС‚В» Р±РµР· РїРѕС…РѕРґР° РІ Р±Р°Р·Сѓ.
 *
 * <p>РњР°СЃСЃРёРІ РёР· {@code m} Р±РёС‚ Рё {@code k} С…РµС€-С„СѓРЅРєС†РёР№. Р”РѕР±Р°РІР»РµРЅРёРµ СЃС‚Р°РІРёС‚ {@code k} Р±РёС‚; РїСЂРѕРІРµСЂРєР° СЃРјРѕС‚СЂРёС‚
 * С‚Рµ Р¶Рµ {@code k} Р±РёС‚. Р’СЃРµ СЃС‚РѕСЏС‚ вЂ” В«РІРѕР·РјРѕР¶РЅРѕ, РµСЃС‚СЊВ» (РёРЅРѕРіРґР° Р»РѕР¶РЅРѕ); С…РѕС‚СЊ РѕРґРёРЅ СЃР±СЂРѕС€РµРЅ вЂ” В«С‚РѕС‡РЅРѕ РЅРµС‚В».
 * Р›РѕР¶РЅС‹С… В«РЅРµС‚В» РЅРµ Р±С‹РІР°РµС‚.
 *
 * <p><b>Р—Р°С‡РµРј.</b> РџСЂРѕРјРѕРєРѕРґС‹ РїРµСЂРµР±РёСЂР°СЋС‚ Р±РѕС‚Р°РјРё Рё РІРІРѕРґСЏС‚ СЃ РѕРїРµС‡Р°С‚РєР°РјРё. РќРµСЃСѓС‰РµСЃС‚РІСѓСЋС‰РёР№ РєРѕРґ РѕС‚СЃРµРєР°РµС‚СЃСЏ
 * РѕРґРЅРёРј Р·Р°РїСЂРѕСЃРѕРј Рє Redis (k GETBIT РєРѕРЅРІРµР№РµСЂРѕРј), Р° РІ Р±Р°Р·Сѓ РёРґСѓС‚ С‚РѕР»СЊРєРѕ В«РІРѕР·РјРѕР¶РЅРѕ, РµСЃС‚СЊВ». 65 536 Р±РёС‚
 * (8 РљР‘) Рё 4 С…РµС€Р° РЅР° С‚С‹СЃСЏС‡Сѓ РєРѕРґРѕРІ вЂ” РѕРєРѕР»Рѕ 0,0002% Р»РѕР¶РЅС‹С… СЃСЂР°Р±Р°С‚С‹РІР°РЅРёР№.
 *
 * <p><b>РџРѕС‡РµРјСѓ РІ Redis, Р° РЅРµ РІ РїР°РјСЏС‚Рё.</b> Р¤РёР»СЊС‚СЂ РІ РїР°РјСЏС‚Рё РєР°Р¶РґРѕРіРѕ СЌРєР·РµРјРїР»СЏСЂР° СѓР·РЅР°Р» Р±С‹ Рѕ РЅРѕРІРѕРј РєРѕРґРµ,
 * СЃРѕР·РґР°РЅРЅРѕРј РЅР° СЃРѕСЃРµРґРЅРµРј, С‚РѕР»СЊРєРѕ РїСЂРё РїРµСЂРµСЃС‚СЂРѕР№РєРµ вЂ” Рё РґРѕ РЅРµС‘ РѕС‚РІРµС‡Р°Р» Р±С‹ Р»РѕР¶РЅС‹Рј В«РЅРµС‚В», Р° СЌС‚Рѕ РµРґРёРЅСЃС‚РІРµРЅРЅР°СЏ
 * РѕС€РёР±РєР°, РєРѕС‚РѕСЂРѕР№ Сѓ С„РёР»СЊС‚СЂР° Р‘Р»СѓРјР° Р±С‹С‚СЊ РЅРµ РґРѕР»Р¶РЅРѕ. Р‘РёС‚РѕРІР°СЏ РєР°СЂС‚Р° РІ Redis РѕР±С‰Р°СЏ: SETBIT РїСЂРё СЃРѕР·РґР°РЅРёРё РєРѕРґР°
 * СЃСЂР°Р·Сѓ РІРёРґРµРЅ РІСЃРµРј. (РњРѕРґСѓР»СЊ RedisBloom СѓРјРµРµС‚ С‚Рѕ Р¶Рµ РєРѕРјР°РЅРґР°РјРё BF.*, РЅРѕ РІ РѕР±С‹С‡РЅРѕРј Redis РµРіРѕ РЅРµС‚.)
 *
 * <p>РЈРґР°Р»СЏС‚СЊ РёР· С„РёР»СЊС‚СЂР° РЅРµР»СЊР·СЏ (СЃР±СЂРѕСЃ Р±РёС‚Р° Р·Р°РґРµР» Р±С‹ С‡СѓР¶РёРµ РєРѕРґС‹), РїРѕСЌС‚РѕРјСѓ РѕРЅ РїРµСЂРµСЃС‚СЂР°РёРІР°РµС‚СЃСЏ С†РµР»РёРєРѕРј:
 * РІРѕ РІСЂРµРјРµРЅРЅС‹Р№ РєР»СЋС‡, Р·Р°С‚РµРј RENAME вЂ” Р°С‚РѕРјР°СЂРЅР°СЏ РїРѕРґРјРµРЅР°, С‡РёС‚Р°С‚РµР»Рё РЅРµ РІРёРґСЏС‚ РЅР°РїРѕР»РѕРІРёРЅСѓ РїРѕСЃС‚СЂРѕРµРЅРЅРѕРіРѕ.
 */
@Component
public class PromoBloomFilter {

    public static final String KEY = "promo:bloom";
    static final int BITS = 1 << 16;
    static final int HASHES = 4;

    private final StringRedisTemplate redis;

    public PromoBloomFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return {@code false}, РµСЃР»Рё С„РёР»СЊС‚СЂР° РЅРµС‚: SETBIT СЃРѕР·РґР°Р» Р±С‹ РєР»СЋС‡ СЃ Р±РёС‚Р°РјРё РѕРґРЅРѕРіРѕ СЌС‚РѕРіРѕ РєРѕРґР°, Рё РІСЃРµ
     * РѕСЃС‚Р°Р»СЊРЅС‹Рµ РєРѕРґС‹ СЃС‚Р°Р»Рё Р±С‹ В«С‚РѕС‡РЅРѕ РЅРµС‚В». РўРѕРіРґР° РІС‹Р·С‹РІР°СЋС‰РёР№ РїРµСЂРµСЃС‚СЂР°РёРІР°РµС‚ С„РёР»СЊС‚СЂ С†РµР»РёРєРѕРј.
     */
    public boolean add(String code) {
        if (!Boolean.TRUE.equals(redis.hasKey(KEY))) {
            return false;
        }
        for (long offset : offsets(code)) {
            redis.opsForValue().setBit(KEY, offset, true);
        }
        return true;
    }

    /**
     * РќРµС‚ РєР»СЋС‡Р° (РІС‹С‚РµСЃРЅРµРЅ РїСЂРё {@code allkeys-lru}, Redis РїРµСЂРµР·Р°РїСѓС‰РµРЅ Р±РµР· РґР°РЅРЅС‹С…) РёР»Рё Redis РЅРµРґРѕСЃС‚СѓРїРµРЅ вЂ”
     * В«РІРѕР·РјРѕР¶РЅРѕ, РµСЃС‚СЊВ»: РїСЂРѕРІРµСЂРєСѓ СЃРґРµР»Р°РµС‚ Р±Р°Р·Р°. РРЅР°С‡Рµ РїСЂРѕРїР°РІС€РёР№ С„РёР»СЊС‚СЂ РѕС‚РІРµС‡Р°Р» Р±С‹ В«С‚РѕС‡РЅРѕ РЅРµС‚В» РЅР° РІСЃРµ РєРѕРґС‹.
     */
    public boolean mightContain(String code) {
        long[] offsets = offsets(code);
        byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
        try {
            List<Object> replies = redis.executePipelined((RedisCallback<Object>) connection -> {
                connection.keyCommands().exists(key);
                for (long offset : offsets) {
                    connection.stringCommands().getBit(key, offset);
                }
                return null;
            });
            return !Boolean.TRUE.equals(replies.getFirst()) || replies.stream().skip(1).allMatch(Boolean.TRUE::equals);
        } catch (DataAccessException exception) {
            return true;
        }
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

    /** Р”РІРѕР№РЅРѕРµ С…РµС€РёСЂРѕРІР°РЅРёРµ (KirschвЂ“Mitzenmacher): k РїРѕР·РёС†РёР№ РёР· РґРІСѓС… С…РµС€РµР№, h1 + iВ·h2. */
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
