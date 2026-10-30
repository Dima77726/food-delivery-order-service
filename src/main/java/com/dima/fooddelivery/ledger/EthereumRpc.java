package com.dima.fooddelivery.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Минимальный клиент JSON-RPC Ethereum: ровно те методы, что нужны якорю аудита.
 *
 * <p>Без web3j сознательно. Транзакции подписывает сам узел ({@code eth_sendTransaction} от
 * разблокированного счёта), поэтому ни криптографии secp256k1, ни RLP здесь нет, а кодирование
 * ABI для трёх функций контракта — десяток строк в {@link AuditAnchorContract}. Узел — локальный
 * Anvil из профиля {@code chain}; в публичной сети вместо {@code eth_sendTransaction} понадобилась
 * бы подпись ключом сервиса на своей стороне.
 */
@Slf4j
public class EthereumRpc {

    private static final Duration RECEIPT_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient http;
    private final AtomicLong ids = new AtomicLong();

    public EthereumRpc(RestClient http) {
        this.http = http;
    }

    public long chainId() {
        return quantity(call("eth_chainId", List.of()).asString()).longValueExact();
    }

    public String firstAccount() {
        JsonNode accounts = call("eth_accounts", List.of());
        if (accounts.isEmpty()) {
            throw new LedgerException("У узла нет разблокированных счетов: eth_sendTransaction некому подписать");
        }
        return accounts.get(0).asString();
    }

    public boolean hasCode(String address) {
        String code = call("eth_getCode", List.of(address, "latest")).asString();
        return code != null && code.length() > 2;
    }

    public String sendTransaction(String from, String to, String data) {
        Map<String, Object> tx = to == null
                ? Map.of("from", from, "data", data)
                : Map.of("from", from, "to", to, "data", data);

        return call("eth_sendTransaction", List.of(tx)).asString();
    }

    /** Ждёт, пока транзакция попадёт в блок. Anvil майнит сразу, публичной сети нужны секунды. */
    public JsonNode awaitReceipt(String txHash) {
        long deadline = System.nanoTime() + RECEIPT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode receipt = call("eth_getTransactionReceipt", List.of(txHash));
            if (receipt != null && !receipt.isNull()) {
                if (!"0x1".equals(receipt.path("status").asString())) {
                    throw new LedgerException("Транзакция " + txHash + " отклонена контрактом");
                }
                return receipt;
            }
            sleep();
        }
        throw new LedgerException("Транзакция " + txHash + " не попала в блок за " + RECEIPT_TIMEOUT.toSeconds() + " с");
    }

    public String ethCall(String to, String data) {
        return call("eth_call", List.of(Map.of("to", to, "data", data), "latest")).asString();
    }

    static BigInteger quantity(String hex) {
        return new BigInteger(hex.substring(2), 16);
    }

    private JsonNode call(String method, List<Object> params) {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(),
                "method", method, "params", params);

        JsonNode response = http.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new LedgerException("Пустой ответ узла на " + method);
        }
        if (response.has("error")) {
            throw new LedgerException(method + ": " + response.get("error").path("message").asString());
        }
        return response.get("result");
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LedgerException("Ожидание квитанции прервано");
        }
    }
}
