package com.dima.fooddelivery.live;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

/**
 * Слушает канал {@code live_updates} и раздаёт сигналы потокам SSE и долгим запросам.
 *
 * <p>Соединение своё, мимо пула: {@code LISTEN} живёт, пока живо соединение, а соединение из
 * Hikari пришлось бы держать вечно — пул стал бы на одно соединение меньше, а детектор утечек
 * каждые 20 секунд писал бы в лог предупреждение.
 *
 * <p>Обрыв соединения — штатная ситуация (перезапуск базы, сеть): поток переподключается
 * с нарастающей паузой. Сигналы, отправленные, пока его не было, потеряны, поэтому после
 * переподключения все каналы догоняют своих подписчиков из базы ({@link LiveChannel#resync}).
 *
 * <p>{@link SmartLifecycle} с фазой по умолчанию останавливается раньше веб-сервера.
 * На остановке закрываются и открытые потоки: иначе плавное завершение ждало бы их все
 * двадцать секунд своего таймаута — соединение SSE само не заканчивается.
 */
@Slf4j
@Component
public class PgNotificationListener implements SmartLifecycle {

    private static final Duration POLL = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final List<LiveChannel> channels;
    private final LiveProperties properties;

    private volatile boolean running;
    private volatile Thread worker;
    private volatile Connection connection;

    public PgNotificationListener(DataSource dataSource, List<LiveChannel> channels, LiveProperties properties) {
        this.dataSource = dataSource;
        this.channels = channels;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;

        if (!properties.enabled()) {
            log.info("Живые обновления: сигналы базы не слушаются (app.live.enabled=false)");
            return;
        }

        worker = Thread.ofPlatform().name("pg-listen").daemon().start(this::listenLoop);
    }

    @Override
    public void stop() {
        running = false;

        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
        closeQuietly(connection);

        channels.forEach(LiveChannel::closeAll);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listenLoop() {
        Duration backoff = Duration.ofSeconds(1);
        boolean reconnect = false;

        while (running) {
            try (Connection conn = openConnection()) {
                connection = conn;

                try (Statement statement = conn.createStatement()) {
                    statement.execute("LISTEN " + LiveSignals.CHANNEL);
                }

                log.info("Живые обновления: подписка на канал {} оформлена", LiveSignals.CHANNEL);
                backoff = Duration.ofSeconds(1);

                if (reconnect) {
                    channels.forEach(LiveChannel::resync);
                }

                PGConnection pg = conn.unwrap(PGConnection.class);

                while (running) {
                    PGNotification[] notifications = pg.getNotifications((int) POLL.toMillis());

                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            dispatch(notification.getParameter());
                        }
                    }
                }
            } catch (SQLException exception) {
                if (!running) {
                    return;
                }

                log.warn("Живые обновления: соединение со слушателем потеряно, повтор через {}: {}",
                        backoff, exception.getMessage());
                reconnect = true;

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }

                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    private void dispatch(String payload) {
        LiveSignal signal;
        try {
            signal = LiveSignal.parse(payload);
        } catch (IllegalArgumentException exception) {
            log.warn("Живые обновления: пропущен непонятный сигнал: {}", payload);
            return;
        }

        for (LiveChannel channel : channels) {
            try {
                channel.onSignal(signal);
            } catch (RuntimeException exception) {
                // Сбой одного канала не должен остановить раздачу остальным и сам поток слушателя.
                log.error("Живые обновления: канал {} не обработал сигнал {}",
                        channel.getClass().getSimpleName(), payload, exception);
            }
        }
    }

    /**
     * Те же адрес и учётные данные, что у пула: Hikari их знает, а при запуске из тестов
     * их подставляет Testcontainers — отдельные свойства пришлось бы дублировать.
     */
    private Connection openConnection() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        return DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Закрываем на остановке: сообщать уже некому.
        }
    }
}
