package com.dima.fooddelivery.live;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Параметры живых обновлений: потоки SSE и долгие запросы (long polling).
 *
 * @param enabled         слушать сигналы базы. Выключенный режим оставляет эндпоинты рабочими,
 *                        но новое приходит только при переподключении клиента
 * @param heartbeat       как часто слать в открытые потоки пустой комментарий. Прокси (nginx,
 *                        шлюз, балансировщик) закрывают соединение, по которому долго ничего не идёт
 * @param streamTimeout   сколько живёт один поток SSE. По истечении сервер закрывает его сам,
 *                        клиент переподключается с {@code Last-Event-ID} и ничего не теряет
 * @param maxStreams      потолок открытых потоков на экземпляр, сверх него — 503. Каждый поток —
 *                        открытое соединение и запись в памяти
 * @param maxPollTimeout  потолок ожидания долгого запроса. Меньше таймаутов прокси по пути
 *                        (nginx — 60 с), иначе ответ «ничего нового» оборвался бы ошибкой
 */
@ConfigurationProperties(prefix = "app.live")
public record LiveProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT15S") Duration heartbeat,
        @DefaultValue("PT30M") Duration streamTimeout,
        @DefaultValue("500") int maxStreams,
        @DefaultValue("PT30S") Duration maxPollTimeout
) {
}
