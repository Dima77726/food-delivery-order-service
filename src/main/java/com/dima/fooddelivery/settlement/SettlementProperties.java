package com.dima.fooddelivery.settlement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.ZoneId;

/**
 * Ежедневные расчёты с ресторанами.
 *
 * @param commissionRate доля платформы с выручки ресторана
 * @param zone           часовой пояс, в котором считаются «сутки»: заказ, доставленный в 00:30 по
 *                       Москве, относится к новому дню, хотя в UTC это ещё вчера
 * @param scheduleCron   когда запускать расчёт за вчера; «-» — не запускать по расписанию
 * @param storage        куда класть файл расчёта
 */
@ConfigurationProperties(prefix = "app.settlement")
public record SettlementProperties(
        @DefaultValue("0.15") BigDecimal commissionRate,
        @DefaultValue("Europe/Moscow") ZoneId zone,
        @DefaultValue("0 0 3 * * *") String scheduleCron,
        @DefaultValue Storage storage
) {

    /**
     * @param enabled  класть ли CSV в MinIO (S3). Выключено — строки расчёта только в базе и в API
     * @param endpoint адрес MinIO внутри сети
     * @param bucket   бакет для файлов; создаётся при первой записи
     */
    public record Storage(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://localhost:9000") String endpoint,
            String accessKey,
            String secretKey,
            @DefaultValue("settlements") String bucket
    ) {
    }
}
