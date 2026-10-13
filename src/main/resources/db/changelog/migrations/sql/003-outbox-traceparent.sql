-- Контекст трассы запроса, в котором записано событие (W3C traceparent). Нужен, чтобы публикация
-- в Kafka продолжала трассу запроса, а не начинала новую.
ALTER TABLE order_event_outbox ADD COLUMN IF NOT EXISTS traceparent VARCHAR(55);
