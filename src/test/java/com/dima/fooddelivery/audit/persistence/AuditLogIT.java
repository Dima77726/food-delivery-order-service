package com.dima.fooddelivery.audit.persistence;

import com.dima.fooddelivery.audit.domain.AuditEntry;
import com.dima.fooddelivery.audit.domain.AuditOutcome;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Журнал аудита на голом JDBC.
 *
 * <p>Тесты появились вместе с переходом модуля на {@code Connection} и
 * {@code PreparedStatement}: до этого у модуля не было ни одного, и весь слой доступа
 * к данным был переписан вслепую. Ошибка в нумерации параметров или в разборе строки
 * не проявилась бы до первого обращения к журналу в проде.
 */
class AuditLogIT extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldWriteAndReadBackEveryField() {
        String resourceId = "order-" + testData.uniqueSuffix();

        Long id = auditLogRepository.append(
                null,
                "actor@example.com",
                "CANCEL_ORDER",
                "ORDER",
                resourceId,
                AuditOutcome.FAILURE,
                "Отменено по требованию клиента",
                "corr-42"
        );

        AuditEntry entry = auditLogRepository.findByResource("ORDER", resourceId, 10).get(0);

        assertAll(
                () -> assertEquals(id, entry.id()),
                // Аноним обязан остаться анонимом. Примитивный getLong вернул бы здесь 0,
                // и действие оказалось бы записано на несуществующего пользователя.
                () -> assertNull(entry.actorId(), "анонимное действие не должно получить actorId"),
                () -> assertEquals("actor@example.com", entry.actorEmail()),
                () -> assertEquals("CANCEL_ORDER", entry.action()),
                () -> assertEquals("ORDER", entry.resourceType()),
                () -> assertEquals(resourceId, entry.resourceId()),
                () -> assertEquals(AuditOutcome.FAILURE, entry.outcome()),
                () -> assertEquals("Отменено по требованию клиента", entry.details()),
                () -> assertEquals("corr-42", entry.correlationId()),
                // Проставляется значением по умолчанию в базе, а не кодом.
                () -> assertTrue(entry.createdAt() != null, "created_at обязан заполниться базой")
        );
    }

    /**
     * Проверяет, что позиционные параметры расставлены по своим местам. Перепутанные местами
     * {@code action} и {@code resource_type} компилируются молча: оба String.
     */
    @Test
    void shouldFilterByActorAndByResource() {
        Long actorId = testData.insertCustomer();
        String resourceId = "restaurant-" + testData.uniqueSuffix();

        auditLogRepository.append(
                actorId, "owner@example.com", "OPEN_RESTAURANT", "RESTAURANT", resourceId,
                AuditOutcome.SUCCESS, null, null
        );

        List<AuditEntry> byActor = auditLogRepository.findByActor(actorId, 10);
        List<AuditEntry> byResource = auditLogRepository.findByResource("RESTAURANT", resourceId, 10);
        List<AuditEntry> byWrongType = auditLogRepository.findByResource("ORDER", resourceId, 10);

        assertAll(
                () -> assertEquals(1, byActor.size()),
                () -> assertEquals("OPEN_RESTAURANT", byActor.get(0).action()),
                () -> assertEquals(1, byResource.size()),
                () -> assertEquals(actorId, byResource.get(0).actorId()),
                () -> assertTrue(byWrongType.isEmpty(), "фильтр по типу ресурса обязан отсекать чужое")
        );
    }

    @Test
    void shouldRespectLimit() {
        String resourceId = "order-" + testData.uniqueSuffix();

        for (int i = 0; i < 5; i++) {
            auditLogRepository.append(
                    null, null, "STATUS_CHANGED", "ORDER", resourceId,
                    AuditOutcome.SUCCESS, "шаг " + i, null
            );
        }

        assertEquals(2, auditLogRepository.findByResource("ORDER", resourceId, 2).size());
    }

    /**
     * Главная проверка этого модуля, и ради неё тест выведен из общей транзакции.
     *
     * <p>Репозиторий берёт соединение у {@code DataSourceUtils}, а не у
     * {@code dataSource.getConnection()}. Разницу видно только здесь: со своим собственным
     * соединением запись ушла бы в базу немедленно и пережила бы откат — в журнале осталось бы
     * событие, которого не было. Такую ошибку не поймает ни один тест, выполняющийся внутри
     * транзакции, потому что там оба варианта выглядят одинаково.
     *
     * <p>{@code NOT_SUPPORTED} отключает транзакцию теста: она откатывается всегда, и на ней
     * ничего доказать нельзя. Транзакция здесь настоящая, своя, и откатывается по исключению.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollBackAuditEntryTogetherWithBusinessTransaction() {
        String resourceId = "order-" + System.nanoTime();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            auditLogRepository.append(
                    null, null, "DOOMED_ACTION", "ORDER", resourceId,
                    AuditOutcome.SUCCESS, null, null
            );

            throw new IllegalStateException("откатываем намеренно");
        }));

        Integer survived = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_id = ?",
                Integer.class,
                resourceId
        );

        assertEquals(0, survived, "запись аудита обязана откатиться вместе с бизнес-транзакцией");
    }
}
