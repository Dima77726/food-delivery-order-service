// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

/// @title Якорь журнала аудита order-service.
/// @notice Хранит Merkle-корни пачек записей audit_log. Сами записи остаются в PostgreSQL;
///         в цепочку попадает только 32-байтный отпечаток пачки. Поправь кто-то запись в базе
///         задним числом — корень, пересчитанный из базы, перестанет совпадать с записанным здесь.
/// @dev Пачки идут подряд и не пересекаются: fromId каждой следующей больше toId предыдущей.
///      Писать может только тот, кто развернул контракт (ключ сервиса), читать — кто угодно.
///      Байткод собран solc 0.8.28 (--optimize) в src/main/resources/ledger/AuditAnchor.bin.
contract AuditAnchor {
    struct Anchor {
        bytes32 root;
        uint64 fromId;
        uint64 toId;
        uint64 anchoredAt;
    }

    address public immutable owner;
    Anchor[] private anchors;

    event Anchored(uint256 indexed index, bytes32 root, uint64 fromId, uint64 toId);

    constructor() {
        owner = msg.sender;
    }

    function anchor(bytes32 root, uint64 fromId, uint64 toId) external returns (uint256 index) {
        require(msg.sender == owner, "not owner");
        require(toId >= fromId, "bad range");
        if (anchors.length > 0) {
            require(fromId > anchors[anchors.length - 1].toId, "overlap");
        }

        anchors.push(Anchor(root, fromId, toId, uint64(block.timestamp)));
        index = anchors.length - 1;

        emit Anchored(index, root, fromId, toId);
    }

    function count() external view returns (uint256) {
        return anchors.length;
    }

    function get(uint256 index) external view returns (bytes32 root, uint64 fromId, uint64 toId, uint64 anchoredAt) {
        Anchor storage a = anchors[index];
        return (a.root, a.fromId, a.toId, a.anchoredAt);
    }
}
