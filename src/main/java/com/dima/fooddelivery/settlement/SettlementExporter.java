package com.dima.fooddelivery.settlement;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Шаг export: строки расчёта за день → CSV → объектное хранилище (MinIO, S3 API).
 *
 * <p>Файловая интеграция — самый старый и до сих пор самый частый способ обмена с бухгалтерией и
 * банками: система партнёра забирает файл по расписанию. Ключ объекта детерминирован
 * ({@code ГГГГ/ММ/settlements-ГГГГ-ММ-ДД.csv}), поэтому перезапуск задания перезаписывает тот же
 * файл, а не плодит копии. Первая строка — UTF-8 BOM: так кириллицу в будущих колонках правильно
 * откроет Excel.
 */
@Slf4j
@Component
public class SettlementExporter {

    private final SettlementRepository repository;
    private final SettlementProperties.Storage storage;
    private final MinioClient client;

    public SettlementExporter(SettlementRepository repository, SettlementProperties properties) {
        this.repository = repository;
        this.storage = properties.storage();
        this.client = storage.enabled()
                ? MinioClient.builder().endpoint(storage.endpoint()).credentials(storage.accessKey(), storage.secretKey()).build()
                : null;
    }

    public void export(LocalDate date) throws Exception {
        List<SettlementLine> lines = repository.findByDate(date);
        String csv = csv(lines);

        if (client == null) {
            log.info("Расчёт за {}: {} строк; хранилище выключено, файл не записан", date, lines.size());
            return;
        }

        if (!client.bucketExists(BucketExistsArgs.builder().bucket(storage.bucket()).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(storage.bucket()).build());
        }
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectArgs.builder()
                .bucket(storage.bucket())
                .object(objectKey(date))
                .contentType("text/csv; charset=utf-8")
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .build());
        log.info("Расчёт за {}: {} строк записаны в {}/{}", date, lines.size(), storage.bucket(), objectKey(date));
    }

    static String objectKey(LocalDate date) {
        return "%d/%02d/settlements-%s.csv".formatted(date.getYear(), date.getMonthValue(), date);
    }

    static String csv(List<SettlementLine> lines) {
        StringBuilder out = new StringBuilder("﻿").append(SettlementLine.CSV_HEADER).append("\r\n");
        lines.forEach(line -> out.append(line.toCsv()).append("\r\n"));
        return out.toString();
    }
}
