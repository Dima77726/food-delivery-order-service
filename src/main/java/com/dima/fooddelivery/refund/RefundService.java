package com.dima.fooddelivery.refund;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderItem;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import com.dima.fooddelivery.order.saga.PaymentCommandWriter;
import com.dima.fooddelivery.order.service.OrderStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Частичные возвраты по позициям доставленного заказа: «не привезли напиток» — вернуть его стоимость.
 *
 * <pre>
 * клиент: заявка (позиции, причина) ──┬── сумма ≤ порога и мало возвратов за 30 дней ── одобрено сразу
 *                                     └── иначе ── PENDING ── поддержка: одобрить / отклонить
 * одобрено ── в той же транзакции команда REFUND_REQUESTED с суммой в outbox ── payment-service ── PAYMENT_REFUNDED
 * </pre>
 *
 * <ul>
 *     <li><b>Сумма считает сервер</b> по ценам позиций заказа, а не клиент: «верните 5000 за булочку»
 *     не пройдёт;</li>
 *     <li><b>Нельзя вернуть больше, чем было:</b> по каждой позиции учитываются уже поданные и одобренные
 *     заявки;</li>
 *     <li><b>Автоодобрение с порогом и частотой</b> — компромисс между скоростью (мелкие претензии клиент
 *     не ждёт) и злоупотреблением (кто возвращает каждый заказ, попадает к человеку);</li>
 *     <li><b>Одобрение и команда — одна транзакция</b> (outbox): одобренная заявка не может «потерять»
 *     возврат, а повтор одобрения не вернёт деньги дважды — статус меняется сравнением PENDING → APPROVED,
 *     а ключ идемпотентности заявки уходит в платёжный сервис.</li>
 * </ul>
 */
@Slf4j
@Service
public class RefundService {

    private static final TypeReference<List<RefundItem>> ITEMS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final PaymentCommandWriter paymentCommands;
    private final ObjectMapper objectMapper;
    private final BigDecimal autoApproveUpTo;
    private final int autoApproveMaxPer30Days;
    private final Duration window;

    public RefundService(
            JdbcTemplate jdbc,
            OrderRepository orderRepository,
            OrderStatusService orderStatusService,
            PaymentCommandWriter paymentCommands,
            ObjectMapper objectMapper,
            @Value("${app.refund.auto-approve-up-to:500}") BigDecimal autoApproveUpTo,
            @Value("${app.refund.auto-approve-max-per-30-days:2}") int autoApproveMaxPer30Days,
            @Value("${app.refund.window:P2D}") Duration window
    ) {
        this.jdbc = jdbc;
        this.orderRepository = orderRepository;
        this.orderStatusService = orderStatusService;
        this.paymentCommands = paymentCommands;
        this.objectMapper = objectMapper;
        this.autoApproveUpTo = autoApproveUpTo;
        this.autoApproveMaxPer30Days = autoApproveMaxPer30Days;
        this.window = window;
    }

    @Transactional
    public RefundRequest request(long customerId, long orderId, List<RequestedItem> requested, String reason) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Заказ с id=" + orderId + " не найден"));
        if (order.status() != OrderStatus.DELIVERED) {
            throw new BusinessRuleViolationException("Вернуть деньги за позиции можно только у доставленного заказа");
        }
        OffsetDateTime deliveredAt = jdbc.queryForObject("SELECT delivered_at FROM delivery WHERE order_id = ?", OffsetDateTime.class, orderId);
        if (deliveredAt != null && deliveredAt.plus(window).isBefore(OffsetDateTime.now())) {
            throw new BusinessRuleViolationException("Заявку на возврат можно подать в течение " + window.toHours() + " часов после доставки");
        }

        Map<Long, Integer> alreadyRequested = alreadyRequested(orderId);
        Map<Long, OrderItem> items = new HashMap<>();
        order.items().forEach(item -> items.put(item.id(), item));

        BigDecimal amount = BigDecimal.ZERO;
        List<RefundItem> lines = new java.util.ArrayList<>();
        for (RequestedItem line : requested) {
            OrderItem item = items.get(line.orderItemId());
            if (item == null) {
                throw new BusinessRuleViolationException("В заказе нет позиции " + line.orderItemId());
            }
            int left = item.quantity() - alreadyRequested.getOrDefault(item.id(), 0);
            if (line.quantity() < 1 || line.quantity() > left) {
                throw new BusinessRuleViolationException("По позиции «" + item.menuItemName() + "» можно вернуть не больше " + Math.max(left, 0) + " шт.");
            }
            BigDecimal lineAmount = item.price().multiply(BigDecimal.valueOf(line.quantity()));
            amount = amount.add(lineAmount);
            lines.add(new RefundItem(item.id(), item.menuItemName(), line.quantity(), lineAmount));
        }

        UUID key = UUID.randomUUID();
        long id = jdbc.queryForObject("""
                INSERT INTO refund_request (order_id, customer_id, amount, items, reason, command_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, orderId, customerId, amount, objectMapper.writeValueAsString(lines), reason, key);

        orderStatusService.recordEvent(orderId, OrderEventType.ORDER_REFUNDED, "Заявка на возврат " + amount.toPlainString() + " ₽: " + reason);

        if (amount.compareTo(autoApproveUpTo) <= 0 && recentApproved(customerId) < autoApproveMaxPer30Days) {
            approve(id, null);
            log.info("Возврат одобрен автоматически: requestId={}, orderId={}, amount={}", id, orderId, amount);
        } else {
            log.info("Возврат ждёт решения поддержки: requestId={}, orderId={}, amount={}", id, orderId, amount);
        }
        return require(id);
    }

    /** @param adminId кто одобрил; {@code null} — автоматически */
    @Transactional
    public RefundRequest approve(long id, Long adminId) {
        RefundRequest request = require(id);
        if (jdbc.update("""
                UPDATE refund_request SET status = 'APPROVED', decided_by = ?, decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, adminId, id) == 0) {
            throw new BusinessRuleViolationException("Заявка уже рассмотрена");
        }
        OrderAccess order = orderStatusService.requireAccess(request.orderId());
        paymentCommands.requestPartialRefund(order, request.amount(), request.commandId());
        return require(id);
    }

    @Transactional
    public RefundRequest reject(long id, long adminId) {
        if (jdbc.update("""
                UPDATE refund_request SET status = 'REJECTED', decided_by = ?, decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, adminId, id) == 0) {
            throw new BusinessRuleViolationException("Заявка уже рассмотрена");
        }
        RefundRequest request = require(id);
        orderStatusService.recordEvent(request.orderId(), OrderEventType.ORDER_REFUNDED, "Заявка на возврат отклонена поддержкой");
        return request;
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> forOrder(long customerId, long orderId) {
        return jdbc.query(SELECT + " WHERE order_id = ? AND customer_id = ? ORDER BY id DESC", this::map, orderId, customerId);
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> pending() {
        return jdbc.query(SELECT + " WHERE status = 'PENDING' ORDER BY created_at", this::map);
    }

    private static final String SELECT = """
            SELECT id, order_id, customer_id, amount, items, reason, status, decided_by, decided_at, command_id, created_at
            FROM refund_request
            """;

    private RefundRequest require(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Заявка на возврат с id=" + id + " не найдена"));
    }

    private Map<Long, Integer> alreadyRequested(long orderId) {
        Map<Long, Integer> counts = new HashMap<>();
        for (String json : jdbc.queryForList("SELECT items FROM refund_request WHERE order_id = ? AND status <> 'REJECTED'", String.class, orderId)) {
            for (RefundItem item : objectMapper.readValue(json, ITEMS)) {
                counts.merge(item.orderItemId(), item.quantity(), Integer::sum);
            }
        }
        return counts;
    }

    private int recentApproved(long customerId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM refund_request
                WHERE customer_id = ? AND status = 'APPROVED' AND created_at > CURRENT_TIMESTAMP - INTERVAL '30 days'
                """, Integer.class, customerId);
    }

    private RefundRequest map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RefundRequest(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("customer_id"), rs.getBigDecimal("amount"),
                objectMapper.readValue(rs.getString("items"), ITEMS), rs.getString("reason"), rs.getString("status"),
                rs.getObject("decided_by", Long.class), rs.getObject("decided_at", OffsetDateTime.class),
                rs.getObject("command_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    public record RequestedItem(long orderItemId, int quantity) {
    }

    public record RefundItem(long orderItemId, String name, int quantity, BigDecimal amount) {
    }

    /** @param decidedBy {@code null} у одобренных автоматически */
    public record RefundRequest(long id, long orderId, long customerId, BigDecimal amount, List<RefundItem> items, String reason,
                                String status, Long decidedBy, OffsetDateTime decidedAt, UUID commandId, OffsetDateTime createdAt) {
    }
}
