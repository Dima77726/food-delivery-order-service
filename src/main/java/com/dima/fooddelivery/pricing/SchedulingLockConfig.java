package com.dima.fooddelivery.pricing;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock: распределённая блокировка задач {@code @Scheduled}.
 *
 * <p>Расписание срабатывает на каждом экземпляре сервиса. Задаче с {@code @SchedulerLock} нужно сначала
 * занять строку в таблице {@code shedlock} (UPDATE … WHERE lock_until &lt; now()) — занял один, остальные
 * пропускают этот такт. {@code lockAtMostFor} — страховка от упавшего держателя: замок истечёт сам.
 * Время берётся у базы ({@code usingDbTime}), а не у экземпляров: часы серверов расходятся.
 *
 * <p>Не всякой задаче нужна блокировка. Публикатор outbox берёт строки с {@code SKIP LOCKED} — несколько
 * экземпляров просто делят работу. Блокировка нужна там, где два одновременных выполнения дают
 * неверный результат: два пересчёта спроса с разным сглаживанием, два напоминания ресторану.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class SchedulingLockConfig {

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build());
    }
}
