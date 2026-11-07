package com.dima.fooddelivery.ledger;

import java.time.OffsetDateTime;

/**
 * Пачка журнала аудита, заверенная в блокчейне.
 *
 * @param chainIndex  номер записи в контракте
 * @param merkleRoot  корень, посчитанный при записи
 * @param txHash      {@code null} у якоря, восстановленного из цепочки
 */
public record AuditAnchor(
        long id,
        long chainId,
        String contractAddress,
        long chainIndex,
        long fromId,
        long toId,
        int recordCount,
        String merkleRoot,
        String txHash,
        Long blockNumber,
        OffsetDateTime anchoredAt
) {
}
