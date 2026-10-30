package com.dima.fooddelivery.ledger;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Якорь журнала против настоящего узла Ethereum (Anvil): контракт разворачивается, корень
 * ложится в блок, а правка записи в базе после этого обнаруживается проверкой.
 */
class AuditAnchorIT extends AbstractIntegrationTest {

    private static final GenericContainer<?> ANVIL = new GenericContainer<>("ghcr.io/foundry-rs/foundry:v1.2.3")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("anvil"))
            .withCommand("--host", "0.0.0.0")
            .withExposedPorts(8545)
            .waitingFor(Wait.forLogMessage(".*Listening on.*", 1));

    static {
        ANVIL.start();
    }

    @AfterAll
    static void stopAnvil() {
        ANVIL.stop();
    }

    @DynamicPropertySource
    static void ledger(DynamicPropertyRegistry registry) {
        registry.add("app.ledger.enabled", () -> "true");
        registry.add("app.ledger.rpc-url", () -> "http://" + ANVIL.getHost() + ":" + ANVIL.getMappedPort(8545));
        registry.add("app.ledger.settle-delay", () -> "PT0S");
        // Таймер не нужен: тест заверяет сам.
        registry.add("app.ledger.anchor-interval", () -> "PT1H");
        registry.add("app.ledger.initial-delay", () -> "PT1H");
        registry.add("app.ledger.max-batch", () -> "2");
    }

    @Autowired
    private AuditAnchorService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldAnchorJournalAndDetectLaterEdit() {
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO audit_log (actor_id, actor_email, action, resource_type, resource_id, outcome, details,
                                           created_at)
                    VALUES (1, 'anna@demo.local', 'ORDER_CANCELED', 'ORDER', ?, 'SUCCESS', 'Заказ отменён клиентом',
                            CURRENT_TIMESTAMP - INTERVAL '1 minute')
                    """, String.valueOf(100 + i));
        }

        // max-batch=2: три записи — две пачки, вторая начинается там, где кончилась первая.
        List<AuditAnchor> anchors = service.anchorPending(10);
        AuditAnchor first = anchors.getFirst();
        AuditAnchor second = anchors.get(1);

        assertAll(
                () -> assertEquals(2, anchors.size()),
                () -> assertEquals(2, first.recordCount()),
                () -> assertEquals(first.toId() + 1, second.fromId()),
                () -> assertNotNull(first.txHash()),
                () -> assertTrue(first.merkleRoot().matches("0x[0-9a-f]{64}"))
        );

        assertEquals(AuditAnchorService.Verification.Status.VALID, service.verify(first.id()).status());

        jdbc.update("UPDATE audit_log SET details = 'Заказ доставлен' WHERE id = ?", first.fromId());

        AuditAnchorService.Verification tampered = service.verify(first.id());
        assertAll(
                () -> assertEquals(AuditAnchorService.Verification.Status.TAMPERED, tampered.status()),
                () -> assertEquals(first.merkleRoot(), tampered.onChainRoot()),
                // Соседнюю пачку правка не задела.
                () -> assertEquals(AuditAnchorService.Verification.Status.VALID, service.verify(second.id()).status())
        );
    }
}
