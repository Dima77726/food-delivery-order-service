package com.dima.fooddelivery.ledger;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AuditAnchorRepository {

    private static final RowMapper<AuditAnchor> ANCHOR = (rs, n) -> new AuditAnchor(
            rs.getLong("id"),
            rs.getLong("chain_id"),
            rs.getString("contract_address"),
            rs.getLong("chain_index"),
            rs.getLong("from_id"),
            rs.getLong("to_id"),
            rs.getInt("record_count"),
            rs.getString("merkle_root"),
            rs.getString("tx_hash"),
            (Long) rs.getObject("block_number"),
            rs.getObject("anchored_at", OffsetDateTime.class));

    private static final String SELECT_ANCHOR = """
            SELECT id, chain_id, contract_address, chain_index, from_id, to_id, record_count, merkle_root,
                   tx_hash, block_number, anchored_at
            FROM audit_anchor
            """;

    /**
     * Только неизменяемые колонки записи: {@code published_at} проставляет публикатор в сводный
     * журнал уже после действия, и в отпечаток она не входит.
     */
    private static final String SELECT_RECORDS = """
            SELECT id, actor_id, actor_email, action, resource_type, resource_id, outcome, details,
                   correlation_id, created_at
            FROM audit_log
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public Optional<String> findContract(long chainId) {
        return jdbc.query("SELECT address FROM ledger_contract WHERE chain_id = ?",
                (rs, n) -> rs.getString("address"), chainId).stream().findFirst();
    }

    public void saveContract(long chainId, String address) {
        jdbc.update("""
                INSERT INTO ledger_contract (chain_id, address) VALUES (?, ?)
                ON CONFLICT (chain_id) DO UPDATE SET address = EXCLUDED.address, deployed_at = CURRENT_TIMESTAMP
                """, chainId, address);
    }

    /** До какого id журнал уже заверен в этом контракте; 0 — ещё ничего. */
    public long lastAnchoredId(long chainId, String contract) {
        Long last = jdbc.queryForObject(
                "SELECT max(to_id) FROM audit_anchor WHERE chain_id = ? AND contract_address = ?",
                Long.class, chainId, contract);
        return last == null ? 0 : last;
    }

    /** Сколько якорей этого контракта уже есть в таблице. */
    public long countAnchors(long chainId, String contract) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_anchor WHERE chain_id = ? AND contract_address = ?",
                Long.class, chainId, contract);
        return count == null ? 0 : count;
    }

    /**
     * Следующая пачка: записи после {@code afterId}, сделанные раньше {@code settledBefore}.
     * Задержка нужна потому, что id выдаёт последовательность до коммита: запись с меньшим id
     * может закоммититься позже соседней, и без паузы её пропустил бы уже записанный диапазон.
     */
    public List<CanonicalRecord> nextBatch(long afterId, OffsetDateTime settledBefore, int limit) {
        return jdbc.query(SELECT_RECORDS + " WHERE id > ? AND created_at < ? ORDER BY id LIMIT ?",
                this::canonical, afterId, settledBefore, limit);
    }

    public List<CanonicalRecord> range(long fromId, long toId) {
        return jdbc.query(SELECT_RECORDS + " WHERE id BETWEEN ? AND ? ORDER BY id", this::canonical, fromId, toId);
    }

    public void insert(long chainId, String contract, AuditAnchorContract.Receipt receipt,
                       long fromId, long toId, int count, String root) {
        jdbc.update("""
                INSERT INTO audit_anchor (chain_id, contract_address, chain_index, from_id, to_id, record_count,
                                          merkle_root, tx_hash, block_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (chain_id, contract_address, chain_index) DO NOTHING
                """, chainId, contract, receipt.index(), fromId, toId, count, root,
                receipt.txHash(), receipt.blockNumber());
    }

    /** Якорь, найденный в цепочке, но не в таблице: сервис упал между транзакцией и записью сюда. */
    public void insertRecovered(long chainId, String contract, long index, AuditAnchorContract.OnChain onChain,
                                int count) {
        jdbc.update("""
                INSERT INTO audit_anchor (chain_id, contract_address, chain_index, from_id, to_id, record_count, merkle_root)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (chain_id, contract_address, chain_index) DO NOTHING
                """, chainId, contract, index, onChain.fromId(), onChain.toId(), count, onChain.root());
    }

    public List<AuditAnchor> findLatest(int limit) {
        return jdbc.query(SELECT_ANCHOR + " ORDER BY id DESC LIMIT ?", ANCHOR, limit);
    }

    public Optional<AuditAnchor> findById(long id) {
        return jdbc.query(SELECT_ANCHOR + " WHERE id = ?", ANCHOR, id).stream().findFirst();
    }

    /**
     * Запись журнала одной строкой JSON-массива — так её видит хэш. Массив, а не склейка через
     * разделитель: значение с разделителем внутри не сможет выдать себя за два соседних поля.
     */
    private CanonicalRecord canonical(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        String json = objectMapper.writeValueAsString(Arrays.asList(
                id,
                rs.getObject("actor_id"),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("outcome"),
                rs.getString("details"),
                rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant().toString()
        ));
        return new CanonicalRecord(id, json);
    }

    public record CanonicalRecord(long id, String json) {
    }
}
