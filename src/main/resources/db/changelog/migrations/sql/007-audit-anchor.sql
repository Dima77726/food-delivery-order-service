-- Якоря журнала аудита в блокчейне (пакет ledger).
--
-- Каждая строка — пачка записей audit_log с id от from_id до to_id, чей Merkle-корень записан
-- в контракт AuditAnchor. Сама таблица ничего не доказывает — доказательство лежит в цепочке;
-- здесь только адрес, по которому его искать, и то, что посчитали при записи.
CREATE TABLE IF NOT EXISTS audit_anchor
(
    id               BIGSERIAL PRIMARY KEY,
    chain_id         BIGINT      NOT NULL,
    contract_address VARCHAR(42) NOT NULL,
    chain_index      BIGINT      NOT NULL,
    from_id          BIGINT      NOT NULL,
    to_id            BIGINT      NOT NULL,
    record_count     INT         NOT NULL,
    merkle_root      VARCHAR(66) NOT NULL,
    -- NULL у якоря, восстановленного из цепочки после сбоя между транзакцией и записью сюда.
    tx_hash          VARCHAR(66),
    block_number     BIGINT,
    anchored_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_audit_anchor_chain_index UNIQUE (chain_id, contract_address, chain_index),
    CONSTRAINT chk_audit_anchor_range CHECK (to_id >= from_id)
);

COMMENT ON TABLE audit_anchor IS 'Пачки журнала аудита, чьи Merkle-корни записаны в блокчейн';

-- Контракт, в который пишет сервис. Один на цепочку: на новой цепочке (Anvil без сохранённого
-- состояния) сервис развернёт контракт заново и запишет сюда новый адрес.
CREATE TABLE IF NOT EXISTS ledger_contract
(
    chain_id    BIGINT PRIMARY KEY,
    address     VARCHAR(42) NOT NULL,
    deployed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
