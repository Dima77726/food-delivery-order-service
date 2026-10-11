package com.dima.fooddelivery.audit.persistence;

import com.dima.fooddelivery.audit.domain.AuditEntry;
import com.dima.fooddelivery.audit.domain.AuditOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Журнал аудита на голом JDBC: {@link DataSource}, {@link Connection},
 * {@link PreparedStatement}, {@link ResultSet}. Ни Hibernate, ни JdbcTemplate.
 *
 * <p>Это четвёртый и последний подход к данным в проекте, и держится он здесь ради одного:
 * показать, что именно делают за вас остальные три. Модуль выбран под задачу — таблица одна,
 * связей нет, только INSERT и SELECT, так что цена многословности минимальна. Соседи для
 * сравнения: {@code RestaurantRepository} — Spring Data JPA, {@code AppUserRepository} —
 * Hibernate вручную, {@code OrderRepository} — Spring JDBC.
 *
 * <p>Методов обновления и удаления нет намеренно: журнал, который можно поправить,
 * ничего не доказывает.
 *
 * <p><b>Главное, что нужно понять из этого класса.</b> Соединение берётся у
 * {@link DataSourceUtils}, а не у {@code dataSource.getConnection()}, и это не стилистика.
 * DataSource на каждый вызов выдаёт новое соединение — со своей транзакцией, ничего не знающей
 * о той, что открыл Spring. Запись аудита ушла бы в базу отдельно и осталась бы там даже
 * после отката бизнес-операции: в журнале появилось бы событие, которого не было.
 * {@code DataSourceUtils} возвращает соединение, уже привязанное к текущей транзакции, и
 * запись живёт и умирает вместе с ней. Ровно это же делает внутри себя JdbcTemplate.
 *
 * <p>Отсюда второе, менее очевидное: соединение <b>не закрывается</b> в try-with-resources,
 * хотя рука к этому и тянется. Закрыть транзакционное соединение значит оборвать транзакцию
 * на середине. Освобождает его {@link DataSourceUtils#releaseConnection}, который закрывает
 * соединение, только если оно ничьё. Statement и ResultSet при этом закрываются как обычно —
 * они принадлежат этому вызову и никому больше.
 */
@Repository
@RequiredArgsConstructor
public class AuditLogRepository {

    private static final String INSERT_ENTRY = """
            INSERT INTO audit_log (
                actor_id, actor_email, action, resource_type, resource_id,
                outcome, details, correlation_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private static final String SELECT_ENTRY = """
            SELECT a.id, a.actor_id, a.actor_email, a.action, a.resource_type,
                   a.resource_id, a.outcome, a.details, a.created_at, a.correlation_id
            FROM audit_log a
            """;

    private static final String ORDER_AND_LIMIT = " ORDER BY a.created_at DESC, a.id DESC LIMIT ?";

    private final DataSource dataSource;

    /**
     * Переводит {@link SQLException} в иерархию {@code DataAccessException} — ту самую,
     * которой пользуются остальные модули. Без него наружу полезло бы проверяемое исключение
     * драйвера, и обработчик ошибок пришлось бы учить ещё одному семейству типов.
     *
     * <p>Ещё одна работа, которую JdbcTemplate делает молча.
     */
    private final SQLExceptionTranslator exceptionTranslator = new SQLExceptionSubclassTranslator();

    public Long append(
            Long actorId,
            String actorEmail,
            String action,
            String resourceType,
            String resourceId,
            AuditOutcome outcome,
            String details,
            String correlationId
    ) {
        Connection connection = DataSourceUtils.getConnection(dataSource);

        try (PreparedStatement statement = connection.prepareStatement(INSERT_ENTRY)) {
            // Параметры нумеруются с единицы и только по позиции: имён, как у
            // NamedParameterJdbcTemplate, здесь нет. Вставленный в середину списка столбец
            // сдвигает всю нумерацию, и компилятор об этом не скажет ни слова.
            //
            // setObject с явным типом, а не setLong: actor_id допускает NULL — действие могло
            // быть анонимным, — а setLong принимает примитив и на null бросил бы NPE.
            statement.setObject(1, actorId, Types.BIGINT);
            statement.setString(2, actorEmail);
            statement.setString(3, action);
            statement.setString(4, resourceType);
            statement.setString(5, resourceId);
            statement.setString(6, outcome.name());
            statement.setString(7, details);
            statement.setString(8, correlationId);

            // executeQuery, а не executeUpdate: RETURNING id делает из INSERT запрос,
            // возвращающий строку.
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("INSERT с RETURNING не вернул идентификатор");
                }

                return resultSet.getLong("id");
            }
        } catch (SQLException exception) {
            throw translate("append audit entry", INSERT_ENTRY, exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    public List<AuditEntry> findRecent(int limit) {
        return query(
                SELECT_ENTRY + ORDER_AND_LIMIT,
                statement -> statement.setInt(1, limit)
        );
    }

    public List<AuditEntry> findByActor(Long actorId, int limit) {
        return query(
                SELECT_ENTRY + " WHERE a.actor_id = ?" + ORDER_AND_LIMIT,
                statement -> {
                    statement.setLong(1, actorId);
                    statement.setInt(2, limit);
                }
        );
    }

    public List<AuditEntry> findByResource(String resourceType, String resourceId, int limit) {
        return query(
                SELECT_ENTRY + " WHERE a.resource_type = ? AND a.resource_id = ?" + ORDER_AND_LIMIT,
                statement -> {
                    statement.setString(1, resourceType);
                    statement.setString(2, resourceId);
                    statement.setInt(3, limit);
                }
        );
    }

    /**
     * Общая обвязка для трёх чтений: получить соединение, подготовить запрос, пройти по
     * результату, всё закрыть, перевести исключение, отпустить соединение.
     *
     * <p>Вынесена не ради красоты — без неё эти двадцать строк были бы скопированы трижды,
     * и достаточно один раз забыть {@code releaseConnection} в одной из копий, чтобы пул
     * соединений исчерпался под нагрузкой.
     *
     * <p>Стоит заметить, что получилось: метод, принимающий SQL, привязку параметров и
     * способ разобрать строку. Это и есть {@code JdbcTemplate} в миниатюре — примерно
     * из этих соображений он и существует.
     */
    private List<AuditEntry> query(String sql, StatementBinder binder) {
        Connection connection = DataSourceUtils.getConnection(dataSource);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);

            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuditEntry> entries = new ArrayList<>();

                // Курсор начинается перед первой строкой, поэтому цикл на next(),
                // а не на каком-либо признаке размера: количество строк заранее неизвестно.
                while (resultSet.next()) {
                    entries.add(mapEntry(resultSet));
                }

                return entries;
            }
        } catch (SQLException exception) {
            throw translate("read audit log", sql, exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * Ручной разбор строки — то, что в JPA делает Hibernate по аннотациям, а в Spring JDBC
     * взял бы на себя RowMapper.
     *
     * <p>{@code getObject} с классом, а не {@code getLong}, для {@code actor_id}: примитивный
     * {@code getLong} вернул бы 0 вместо NULL, и анонимное действие оказалось бы записано
     * на пользователя с идентификатором ноль. Отличить одно от другого потом было бы нечем.
     */
    private AuditEntry mapEntry(ResultSet resultSet) throws SQLException {
        return new AuditEntry(
                resultSet.getLong("id"),
                resultSet.getObject("actor_id", Long.class),
                resultSet.getString("actor_email"),
                resultSet.getString("action"),
                resultSet.getString("resource_type"),
                resultSet.getString("resource_id"),
                AuditOutcome.fromDbValue(resultSet.getString("outcome")),
                resultSet.getString("details"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getString("correlation_id")
        );
    }

    private DataAccessException translate(String task, String sql, SQLException exception) {
        DataAccessException translated = exceptionTranslator.translate(task, sql, exception);

        // Транслятор возвращает null, если не узнал ошибку. Проглотить её в этом случае
        // было бы худшим из вариантов: сбой базы стал бы невидимым.
        return translated != null ? translated : new UncategorizedSQLException(task, sql, exception);
    }

    /**
     * Отдельный интерфейс нужен потому, что {@code PreparedStatement} бросает проверяемое
     * {@link SQLException}, и стандартный {@code Consumer} такую лямбду не примет.
     */
    @FunctionalInterface
    private interface StatementBinder {

        void bind(PreparedStatement statement) throws SQLException;
    }
}
