package com.dima.fooddelivery.ledger;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerkleTreeTest {

    @Test
    void singleRecordRootShouldBePrefixedLeafHash() throws Exception {
        assertEquals("0x" + HexFormat.of().formatHex(sha256(new byte[]{0x00}, "a".getBytes(StandardCharsets.UTF_8))),
                MerkleTree.root(List.of("a")));
    }

    @Test
    void twoRecordsShouldHashLeavesUnderNodePrefix() throws Exception {
        byte[] a = sha256(new byte[]{0x00}, "a".getBytes(StandardCharsets.UTF_8));
        byte[] b = sha256(new byte[]{0x00}, "b".getBytes(StandardCharsets.UTF_8));

        assertEquals("0x" + HexFormat.of().formatHex(sha256(new byte[]{0x01}, a, b)), MerkleTree.root(List.of("a", "b")));
    }

    /** Любая правка, перестановка или удаление записи меняет корень. */
    @Test
    void anyChangeShouldChangeRoot() {
        String root = MerkleTree.root(List.of("a", "b", "c"));

        assertAll(
                () -> assertNotEquals(root, MerkleTree.root(List.of("a", "B", "c"))),
                () -> assertNotEquals(root, MerkleTree.root(List.of("b", "a", "c"))),
                () -> assertNotEquals(root, MerkleTree.root(List.of("a", "b"))),
                () -> assertEquals(root, MerkleTree.root(List.of("a", "b", "c")))
        );
    }

    /** Префиксы не дают выдать внутренний узел за лист: корень пары не равен листу из её склейки. */
    @Test
    void nodeShouldNotCollideWithLeaf() {
        assertNotEquals(MerkleTree.root(List.of("ab")), MerkleTree.root(List.of("a", "b")));
    }

    @Test
    void emptyBatchShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.root(List.of()));
    }

    private static byte[] sha256(byte[]... parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }
}
