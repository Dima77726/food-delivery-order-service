package com.dima.fooddelivery.live;

import com.dima.fooddelivery.order.domain.OrderStatus;

/**
 * Сигнал «что-то изменилось», пришедший через {@code LISTEN/NOTIFY} PostgreSQL.
 *
 * <p>Сигнал — звонок в дверь, а не посылка: он говорит, <em>где</em> изменилось, но не несёт
 * самих данных. Данные получатель читает из базы. Поэтому потерянный сигнал не теряет событий —
 * их догонит следующий сигнал или переподключение клиента, — а порядок сигналов не важен.
 *
 * <p>Формат полезной нагрузки — строка, а не JSON: полей три, и разбирать их проще, чем тянуть
 * ради этого сериализацию.
 * <ul>
 *     <li>{@code order:<orderId>} — у заказа появилось событие в истории;</li>
 *     <li>{@code order:<orderId>:<restaurantId>:<status>} — заказ сменил статус;</li>
 *     <li>{@code deliveries} — изменился список свободных доставок.</li>
 * </ul>
 *
 * @param orderId      заказ или {@code null} для сигнала о доставках
 * @param restaurantId ресторан, если сигнал о смене статуса
 * @param status       новый статус, если сигнал о смене статуса
 */
public record LiveSignal(Long orderId, Long restaurantId, OrderStatus status) {

    static final String ORDER_PREFIX = "order";
    static final String DELIVERIES = "deliveries";

    public boolean isDeliveries() {
        return orderId == null;
    }

    public boolean isStatusChange() {
        return status != null;
    }

    static String orderEventPayload(Long orderId) {
        return ORDER_PREFIX + ":" + orderId;
    }

    static String statusChangePayload(Long orderId, Long restaurantId, OrderStatus status) {
        return ORDER_PREFIX + ":" + orderId + ":" + restaurantId + ":" + status.getDbValue();
    }

    /**
     * @throws IllegalArgumentException на нагрузке, которую этот код не писал
     */
    static LiveSignal parse(String payload) {
        if (DELIVERIES.equals(payload)) {
            return new LiveSignal(null, null, null);
        }

        String[] parts = payload.split(":");

        if (!ORDER_PREFIX.equals(parts[0]) || (parts.length != 2 && parts.length != 4)) {
            throw new IllegalArgumentException("Неизвестный сигнал: " + payload);
        }

        Long orderId = Long.valueOf(parts[1]);

        if (parts.length == 2) {
            return new LiveSignal(orderId, null, null);
        }

        return new LiveSignal(orderId, Long.valueOf(parts[2]), OrderStatus.fromDbValue(parts[3]));
    }
}
