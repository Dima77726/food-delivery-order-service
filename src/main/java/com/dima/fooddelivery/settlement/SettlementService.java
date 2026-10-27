package com.dima.fooddelivery.settlement;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Запуск расчёта: по расписанию за вчера и вручную администратором за любой день.
 *
 * <p>Несколько экземпляров сервиса по расписанию стартуют одновременно — и это безопасно без
 * распределённой блокировки: репозиторий заданий не даст двум запускам одного экземпляра (тот же
 * день) идти параллельно ({@link JobExecutionAlreadyRunningException}), а завершённый — запустить
 * заново ({@link JobInstanceAlreadyCompleteException}). Общая база здесь и есть координатор.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final JobOperator jobOperator;
    private final Job dailySettlementJob;
    private final SettlementProperties properties;
    private final JdbcTemplate jdbc;

    /**
     * @param rerun пересчитать уже посчитанный день (новый экземпляр задания с параметром run)
     * @throws BusinessRuleViolationException если день уже посчитан и rerun не задан, или расчёт идёт
     */
    public Result run(LocalDate date, boolean rerun) {
        // Экземпляр задания различает параметры целиком: пересчёт (date + run) и обычный запуск (date) —
        // разные экземпляры, и Batch сам не заметил бы, что день уже посчитан пересчётом. Поэтому «день
        // посчитан» — это любой успешный запуск с этой датой.
        if (!rerun && completedFor(date)) {
            throw new BusinessRuleViolationException("Расчёт за " + date + " уже выполнен; для пересчёта передайте rerun=true");
        }
        JobParametersBuilder parameters = new JobParametersBuilder().addLocalDate("date", date);
        if (rerun) {
            parameters.addLong("run", System.currentTimeMillis());
        }
        try {
            JobExecution execution = jobOperator.start(dailySettlementJob, parameters.toJobParameters());
            log.info("Расчёт за {}: {}", date, execution.getStatus());
            long written = execution.getStepExecutions().stream().mapToLong(step -> step.getWriteCount()).sum();
            return new Result(date, execution.getId(), execution.getStatus(), written);
        } catch (JobInstanceAlreadyCompleteException exception) {
            throw new BusinessRuleViolationException("Расчёт за " + date + " уже выполнен; для пересчёта передайте rerun=true");
        } catch (JobExecutionAlreadyRunningException exception) {
            throw new BusinessRuleViolationException("Расчёт за " + date + " уже идёт");
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось запустить расчёт за " + date, exception);
        }
    }

    private boolean completedFor(LocalDate date) {
        Boolean done = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM batch_job_execution e
                    JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
                    JOIN batch_job_execution_params p ON p.job_execution_id = e.job_execution_id
                    WHERE i.job_name = ? AND e.status = 'COMPLETED'
                      AND p.parameter_name = 'date' AND p.parameter_value = ?
                )
                """, Boolean.class, SettlementJobConfig.JOB_NAME, date.toString());
        return Boolean.TRUE.equals(done);
    }

    @Scheduled(cron = "${app.settlement.schedule-cron:0 0 3 * * *}", zone = "${app.settlement.zone:Europe/Moscow}")
    void runForYesterday() {
        LocalDate yesterday = LocalDate.now(properties.zone()).minusDays(1);
        try {
            run(yesterday, false);
        } catch (BusinessRuleViolationException exception) {
            // Другой экземпляр уже посчитал или считает — норма, а не ошибка.
            log.info("Плановый расчёт за {} пропущен: {}", yesterday, exception.getMessage());
        }
    }

    /** @param linesWritten сколько строк расчёта записано */
    public record Result(LocalDate date, long executionId, BatchStatus status, long linesWritten) {
    }
}
