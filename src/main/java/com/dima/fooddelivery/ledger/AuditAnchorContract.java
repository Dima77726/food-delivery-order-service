package com.dima.fooddelivery.ledger;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Контракт {@code AuditAnchor} (src/main/solidity/AuditAnchor.sol) со стороны Java: развернуть,
 * записать корень, прочитать корень. Кодирование ABI — вручную: у функций только статические
 * аргументы, каждый занимает одно 32-байтное слово.
 */
public class AuditAnchorContract {

    // Селекторы — первые 4 байта keccak256 сигнатуры, из `solc --hashes`.
    private static final String ANCHOR = "755c698a"; // anchor(bytes32,uint64,uint64)
    private static final String GET = "9507d39a";    // get(uint256)
    private static final String COUNT = "06661abd";  // count()

    /** keccak256("Anchored(uint256,bytes32,uint64,uint64)") — topic[0] события. */
    private static final String ANCHORED_TOPIC = "0xa9202c03b7f7ac161714b3646352350da289cce4618c321c6e8d8c7ee1cb68f1";

    private final EthereumRpc rpc;
    private final String from;
    private final String address;

    private AuditAnchorContract(EthereumRpc rpc, String from, String address) {
        this.rpc = rpc;
        this.from = from;
        this.address = address;
    }

    public static AuditAnchorContract at(EthereumRpc rpc, String from, String address) {
        return new AuditAnchorContract(rpc, from, address);
    }

    public static AuditAnchorContract deploy(EthereumRpc rpc, String from) {
        String txHash = rpc.sendTransaction(from, null, "0x" + bytecode());
        String address = rpc.awaitReceipt(txHash).path("contractAddress").asString();
        return new AuditAnchorContract(rpc, from, address);
    }

    public String address() {
        return address;
    }

    public Receipt anchor(String root, long fromId, long toId) {
        String data = "0x" + ANCHOR + word(root) + word(fromId) + word(toId);
        String txHash = rpc.sendTransaction(from, address, data);
        JsonNode receipt = rpc.awaitReceipt(txHash);

        for (JsonNode log : receipt.path("logs")) {
            if (ANCHORED_TOPIC.equals(log.path("topics").path(0).asString())) {
                long index = EthereumRpc.quantity(log.path("topics").path(1).asString()).longValueExact();
                long block = EthereumRpc.quantity(receipt.path("blockNumber").asString()).longValueExact();
                return new Receipt(index, txHash, block);
            }
        }
        throw new LedgerException("В квитанции " + txHash + " нет события Anchored");
    }

    public long count() {
        return EthereumRpc.quantity(rpc.ethCall(address, "0x" + COUNT)).longValueExact();
    }

    public OnChain get(long index) {
        String result = rpc.ethCall(address, "0x" + GET + word(index)).substring(2);
        return new OnChain(
                "0x" + result.substring(0, 64),
                new BigInteger(result.substring(64, 128), 16).longValueExact(),
                new BigInteger(result.substring(128, 192), 16).longValueExact(),
                new BigInteger(result.substring(192, 256), 16).longValueExact()
        );
    }

    private static String word(long value) {
        return "%064x".formatted(value);
    }

    private static String word(String bytes32) {
        String hex = bytes32.startsWith("0x") ? bytes32.substring(2) : bytes32;
        if (hex.length() != 64) {
            throw new IllegalArgumentException("bytes32 — это 64 шестнадцатеричных символа");
        }
        return hex;
    }

    private static String bytecode() {
        try (InputStream in = AuditAnchorContract.class.getResourceAsStream("/ledger/AuditAnchor.bin")) {
            if (in == null) {
                throw new IllegalStateException("Нет байткода контракта ledger/AuditAnchor.bin");
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Транзакция записи корня: индекс в контракте, хэш транзакции, номер блока. */
    public record Receipt(long index, String txHash, long blockNumber) {
    }

    /** Корень так, как его хранит контракт; {@code anchoredAt} — время блока, секунды Unix. */
    public record OnChain(String root, long fromId, long toId, long anchoredAt) {
    }
}
