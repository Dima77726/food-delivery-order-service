package com.dima.fooddelivery.settlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Задание Spring Batch «расчёт за день»: доставленные заказы → строки расчёта по ресторанам → CSV-файл.
 *
 * <pre>
 *  dailySettlement(date)
 *   ├─ шаг aggregate (chunk = 100):  JDBC-курсор «ресторан, заказов, выручка за сутки»
 *   │                                 → процессор: комиссия и выплата
 *   │                                 → JDBC upsert в settlement_line (пачкой, одной транзакцией на chunk)
 *   └─ шаг export (tasklet):          settlement_line за день → CSV → MinIO settlements/ГГГГ/ММ/…csv
 * </pre>
 *
 * <p><b>Почему Spring Batch, а не цикл в @Scheduled.</b> Пакетная обработка — это не «пройти по
 * строкам», а три вещи, которые цикл не даёт:
 * <ul>
 *     <li><b>chunk-обработка</b>: читаем по одному, пишем пачкой и фиксируем пачку транзакцией —
 *     миллион заказов не держит ни память, ни одну гигантскую транзакцию;</li>
 *     <li><b>журнал запусков</b> (таблицы BATCH_*): сколько прочитано, записано, пропущено, чем
 *     закончилось — и перезапуск упавшего задания с того шага, где оно упало;</li>
 *     <li><b>экземпляр задания = задание + параметры</b>. Расчёт за 27.10 — один экземпляр; второй
 *     успешный запуск с теми же параметрами Batch не даст (JobInstanceAlreadyCompleteException) —
 *     защита от двойного расчёта без своих флагов. Пересчитать вручную можно, добавив параметр run.</li>
 * </ul>
 *
 * <p>Сутки считаются в часовом поясе {@code app.settlement.zone}: границы суток переводятся в
 * моменты времени, и доставка попадает в день по {@code delivered_at}.
 *
 * <p>Выручка ресторана — стоимость блюд ({@code order_terms.items_total}), а не сумма оплаты: доставка
 * — доход платформы, чаевые — курьера, а скидку по промокоду оплачивает платформа, ресторан получает
 * за блюда полностью. У старых заказов без расшифровки сумма оплаты и есть стоимость блюд.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementJobConfig {

    public static final String JOB_NAME = "dailySettlement";
    static final int CHUNK = 100;

    @Bean
    Job dailySettlementJob(JobRepository jobRepository, Step settlementAggregateStep, Step settlementExportStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(settlementAggregateStep)
                .next(settlementExportStep)
                .build();
    }

    @Bean
    Step settlementAggregateStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<RestaurantDay> settlementReader,
            ItemProcessor<RestaurantDay, SettlementLine> settlementProcessor,
            JdbcBatchItemWriter<SettlementLine> settlementWriter
    ) {
        return new StepBuilder("aggregate", jobRepository)
                .<RestaurantDay, SettlementLine>chunk(CHUNK)
                .transactionManager(transactionManager)
                .reader(settlementReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .build();
    }

    @Bean
    Step settlementExportStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, SettlementExporter exporter) {
        return new StepBuilder("export", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    LocalDate date = chunkContext.getStepContext().getStepExecution().getJobParameters().getLocalDate("date");
                    exporter.export(date);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    /** {@code @StepScope}: читатель создаётся на каждый запуск шага — только так ему достаётся параметр date. */
    @Bean
    @StepScope
    JdbcCursorItemReader<RestaurantDay> settlementReader(
            DataSource dataSource,
            SettlementProperties properties,
            @Value("#{jobParameters['date']}") LocalDate date
    ) {
        ZonedDateTime from = date.atStartOfDay(properties.zone());
        ZonedDateTime to = date.plusDays(1).atStartOfDay(properties.zone());

        return new JdbcCursorItemReaderBuilder<RestaurantDay>()
                .name("settlementReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT o.restaurant_id, COUNT(*) AS orders_count,
                               SUM(COALESCE(t.items_total, o.total_amount)) AS revenue
                        FROM customer_order o
                        JOIN delivery d ON d.order_id = o.id
                        LEFT JOIN order_terms t ON t.order_id = o.id
                        WHERE o.status = 'DELIVERED'
                          AND d.delivered_at >= ? AND d.delivered_at < ?
                        GROUP BY o.restaurant_id
                        ORDER BY o.restaurant_id
                        """)
                .preparedStatementSetter(statement -> {
                    statement.setObject(1, from.toOffsetDateTime());
                    statement.setObject(2, to.toOffsetDateTime());
                })
                .rowMapper((rs, rowNum) -> new RestaurantDay(date, rs.getLong("restaurant_id"), rs.getInt("orders_count"), rs.getBigDecimal("revenue")))
                .build();
    }

    @Bean
    ItemProcessor<RestaurantDay, SettlementLine> settlementProcessor(SettlementProperties properties) {
        return day -> {
            // Комиссия округляется до копейки вниз в пользу ресторана: выплата ≥ «точной».
            BigDecimal commission = day.revenue().multiply(properties.commissionRate()).setScale(2, RoundingMode.DOWN);
            return new SettlementLine(day.date(), day.restaurantId(), day.ordersCount(), day.revenue(), commission,
                    day.revenue().subtract(commission));
        };
    }

    @Bean
    JdbcBatchItemWriter<SettlementLine> settlementWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<SettlementLine>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO settlement_line (settlement_date, restaurant_id, orders_count, revenue, commission, payout)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (settlement_date, restaurant_id) DO UPDATE
                        SET orders_count = EXCLUDED.orders_count, revenue = EXCLUDED.revenue,
                            commission = EXCLUDED.commission, payout = EXCLUDED.payout, calculated_at = CURRENT_TIMESTAMP
                        """)
                .itemPreparedStatementSetter((line, statement) -> {
                    statement.setObject(1, line.date());
                    statement.setLong(2, line.restaurantId());
                    statement.setInt(3, line.ordersCount());
                    statement.setBigDecimal(4, line.revenue());
                    statement.setBigDecimal(5, line.commission());
                    statement.setBigDecimal(6, line.payout());
                })
                .build();
    }

    /** Строка читателя: агрегат по ресторану за сутки до расчёта комиссии. */
    public record RestaurantDay(LocalDate date, long restaurantId, int ordersCount, BigDecimal revenue) {
    }
}
