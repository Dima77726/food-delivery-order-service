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

    /**
     * @param rerun пересчитать уже посчитанный день (новый экземпляр задания с параметром run)
     * @throws BusinessRuleViolationException если день уже посчитан и rerun не задан, или расчёт идёт
     */
    public Result run(LocalDate date, boolean rerun) {
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
