package com.dima.fooddelivery.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Merkle-корень пачки записей журнала: один 32-байтный отпечаток на сколько угодно записей.
 *
 * <p>SHA-256 с разделением доменов, как в RFC 6962 (Certificate Transparency): лист —
 * {@code H(0x00 || запись)}, узел — {@code H(0x01 || левый || правый)}. Без префиксов можно было бы
 * выдать внутренний узел за лист и подделать доказательство. Непарный последний узел уровня
 * поднимается вверх без изменений.
 */
public final class MerkleTree {

    private static final byte LEAF = 0x00;
    private static final byte NODE = 0x01;

    private MerkleTree() {
    }

    /** Корень в виде {@code 0x} + 64 шестнадцатеричных символа — как он лежит в контракте. */
    public static String root(List<String> canonicalRecords) {
        if (canonicalRecords.isEmpty()) {
            throw new IllegalArgumentException("Пустую пачку не заверяют");
        }

        List<byte[]> level = new ArrayList<>(canonicalRecords.size());
        for (String record : canonicalRecords) {
            level.add(hash(LEAF, record.getBytes(StandardCharsets.UTF_8)));
        }

        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                next.add(i + 1 < level.size() ? hash(NODE, level.get(i), level.get(i + 1)) : level.get(i));
            }
            level = next;
        }

        return "0x" + HexFormat.of().formatHex(level.getFirst());
    }

    private static byte[] hash(byte prefix, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix);
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
