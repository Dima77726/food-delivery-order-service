package com.dima.fooddelivery.ledger;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.ledger.AuditAnchorRepository.CanonicalRecord;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Заверяет журнал аудита в блокчейне и проверяет, что он не менялся после этого.
 *
 * <p><b>Что это даёт.</b> Журнал лежит в PostgreSQL, и тот, у кого есть доступ к базе, может
 * поправить запись задним числом — следов не останется. Раз в несколько минут сервис считает
 * Merkle-корень новых записей и пишет его в контракт {@code AuditAnchor}. Изменить корень там
 * нельзя ни сервису, ни администратору базы. Проверка ({@link #verify}) заново считает корень
 * из базы и сравнивает с записанным в цепочке: не совпал — пачку меняли.
 *
 * <p><b>Почему корень, а не записи.</b> В цепочку уходит 32 байта на пачку любой длины, а
 * содержимое журнала — персональные данные и детали заказов — остаётся в базе.
 *
 * <p><b>Сбой между транзакцией и записью в таблицу</b> не теряет якорь: перед каждой пачкой
 * сервис сверяет число якорей в контракте и в таблице и дописывает недостающие из цепочки.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.ledger.enabled", havingValue = "true")
public class AuditAnchorService {

    private final AuditAnchorRepository repository;
    private final EthereumRpc rpc;
    private final MeterRegistry meterRegistry;
    private final String configuredFrom;
    private final Duration settleDelay;
    private final int maxBatch;

    private volatile Binding binding;

    public AuditAnchorService(
            AuditAnchorRepository repository,
            MeterRegistry meterRegistry,
            @Value("${app.ledger.rpc-url}") String rpcUrl,
            @Value("${app.ledger.from:}") String configuredFrom,
            @Value("${app.ledger.settle-delay:PT30S}") Duration settleDelay,
            @Value("${app.ledger.max-batch:500}") int maxBatch
    ) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.rpc = new EthereumRpc(RestClient.create(rpcUrl));
        this.configuredFrom = configuredFrom;
        this.settleDelay = settleDelay;
        this.maxBatch = maxBatch;
    }

    /** Заверяет следующую пачку. Пусто — новых устоявшихся записей нет. */
    public Optional<AuditAnchor> anchorNext() {
        Binding chain = binding();
        recoverFromChain(chain);

        long after = repository.lastAnchoredId(chain.chainId(), chain.contract().address());
        List<CanonicalRecord> batch = repository.nextBatch(after, OffsetDateTime.now().minus(settleDelay), maxBatch);
        if (batch.isEmpty()) {
            return Optional.empty();
        }

        long fromId = batch.getFirst().id();
        long toId = batch.getLast().id();
        String root = MerkleTree.root(batch.stream().map(CanonicalRecord::json).toList());

        AuditAnchorContract.Receipt receipt = chain.contract().anchor(root, fromId, toId);
        repository.insert(chain.chainId(), chain.contract().address(), receipt, fromId, toId, batch.size(), root);
        meterRegistry.counter("audit.anchors.created").increment();

        log.info("Журнал аудита заверен: записи {}..{} ({} шт.), root={}, tx={}, block={}",
                fromId, toId, batch.size(), root, receipt.txHash(), receipt.blockNumber());

        return repository.findLatest(1).stream().findFirst();
    }

    /** Заверяет всё накопившееся, но не больше {@code maxBatches} пачек за раз. */
    public List<AuditAnchor> anchorPending(int maxBatches) {
        List<AuditAnchor> created = new ArrayList<>();
        for (int i = 0; i < maxBatches; i++) {
            Optional<AuditAnchor> anchor = anchorNext();
            if (anchor.isEmpty()) {
                break;
            }
            created.add(anchor.get());
        }
        return created;
    }

    public List<AuditAnchor> latest(int limit) {
        return repository.findLatest(limit);
    }

    /**
     * Пересчитывает корень пачки из базы и сравнивает с цепочкой. Корень из таблицы
     * {@code audit_anchor} — только справочно: доверять ему нельзя, его можно поправить вместе
     * с журналом.
     */
    public Verification verify(long anchorId) {
        AuditAnchor anchor = repository.findById(anchorId)
                .orElseThrow(() -> new ResourceNotFoundException("Якорь с id=" + anchorId + " не найден"));

        List<CanonicalRecord> records = repository.range(anchor.fromId(), anchor.toId());
        String recomputed = records.isEmpty() ? null : MerkleTree.root(records.stream().map(CanonicalRecord::json).toList());

        if (!rpc.hasCode(anchor.contractAddress())) {
            return new Verification(anchor, Verification.Status.CHAIN_UNAVAILABLE, null, recomputed, records.size(),
                    "Контракта " + anchor.contractAddress() + " нет в цепочке: узел пересоздан без сохранённого состояния");
        }

        AuditAnchorContract.OnChain onChain = AuditAnchorContract.at(rpc, null, anchor.contractAddress())
                .get(anchor.chainIndex());

        boolean sameRange = onChain.fromId() == anchor.fromId() && onChain.toId() == anchor.toId();
        boolean valid = sameRange && onChain.root().equals(recomputed) && records.size() == anchor.recordCount();

        return new Verification(anchor, valid ? Verification.Status.VALID : Verification.Status.TAMPERED,
                onChain.root(), recomputed, records.size(),
                valid ? "Записи совпадают с заверенными в блоке " + anchor.blockNumber()
                        : "Журнал изменён после заверения: корень из базы не совпадает с корнем в цепочке");
    }

    private void recoverFromChain(Binding chain) {
        long inTable = repository.countAnchors(chain.chainId(), chain.contract().address());
        long onChain = chain.contract().count();

        for (long index = inTable; index < onChain; index++) {
            AuditAnchorContract.OnChain anchor = chain.contract().get(index);
            int count = repository.range(anchor.fromId(), anchor.toId()).size();
            repository.insertRecovered(chain.chainId(), chain.contract().address(), index, anchor, count);
            log.warn("Якорь {} восстановлен из цепочки: записи {}..{}", index, anchor.fromId(), anchor.toId());
        }
    }

    /**
     * Цепочка и контракт, с которыми работает сервис. Контракт разворачивается при первом
     * обращении и после пересоздания узла: адрес из таблицы без кода в цепочке — уже не контракт.
     */
    private Binding binding() {
        Binding current = binding;
        if (current != null && rpc.hasCode(current.contract().address())) {
            return current;
        }

        synchronized (this) {
            long chainId = rpc.chainId();
            String from = configuredFrom.isBlank() ? rpc.firstAccount() : configuredFrom;

            Optional<String> known = repository.findContract(chainId).filter(rpc::hasCode);
            AuditAnchorContract contract;
            if (known.isPresent()) {
                contract = AuditAnchorContract.at(rpc, from, known.get());
            } else {
                contract = AuditAnchorContract.deploy(rpc, from);
                repository.saveContract(chainId, contract.address());
                log.info("Контракт AuditAnchor развёрнут: chainId={}, address={}", chainId, contract.address());
            }

            binding = new Binding(chainId, contract);
            return binding;
        }
    }

    private record Binding(long chainId, AuditAnchorContract contract) {
    }

    public record Verification(
            AuditAnchor anchor,
            Status status,
            String onChainRoot,
            String recomputedRoot,
            int recordsNow,
            String message
    ) {
        public enum Status { VALID, TAMPERED, CHAIN_UNAVAILABLE }
    }
}
