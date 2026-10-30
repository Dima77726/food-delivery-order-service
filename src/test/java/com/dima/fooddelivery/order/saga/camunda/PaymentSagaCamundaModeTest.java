package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.order.domain.OrderAccess;
import com.dima.fooddelivery.order.domain.OrderEventType;
import com.dima.fooddelivery.order.domain.OrderStatus;
import com.dima.fooddelivery.order.domain.OrderStatusChangedEvent;
import com.dima.fooddelivery.order.persistence.InboxRepository;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import com.dima.fooddelivery.order.saga.PaymentCommandWriter;
import com.dima.fooddelivery.order.saga.PaymentSaga;
import com.dima.fooddelivery.order.saga.PaymentTimeoutJob;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.order.service.OrderStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Режим {@code app.saga.engine=camunda} без брокера: что сага отдаёт процессу, а что делает сама.
 * Сам процесс (BPMN) проверяет {@link PaymentSagaBpmnTest}, переходы статусов — {@code PaymentSagaIT}.
 */
class PaymentSagaCamundaModeTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderStatusService orderStatusService = mock(OrderStatusService.class);
    private final PaymentCommandWriter paymentCommands = mock(PaymentCommandWriter.class);
    private final InboxRepository inbox = mock(InboxRepository.class);
    private final PaymentProcess process = mock(PaymentProcess.class);

    private PaymentSaga saga;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<PaymentProcess> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(process);
        doAnswer(invocation -> {
            invocation.<Consumer<PaymentProcess>>getArgument(0).accept(process);
            return null;
        }).when(provider).ifAvailable(any());

        saga = new PaymentSaga(orderRepository, orderStatusService, mock(OrderService.class),
                paymentCommands, inbox, provider);
    }

    @Test
    void shouldHandOutcomeToProcessInsteadOfChangingStatus() {
        PaymentEvent event = event(PaymentEvent.PAYMENT_SUCCEEDED);
        when(inbox.markProcessed(event.eventId(), "payment-events")).thenReturn(true);
        when(process.handles(event)).thenReturn(true);

        saga.handle(event, "payment-events");

        verify(process).publishOutcome(event);
        verify(orderStatusService, never()).tryTransition(anyLong(), any(), any(), any(), anyString());
    }

    /** Повтор из Kafka отсекает inbox ещё до процесса: брокеру он не нужен. */
    @Test
    void shouldNotPublishDuplicateOutcome() {
        PaymentEvent event = event(PaymentEvent.PAYMENT_FAILED);
        when(inbox.markProcessed(event.eventId(), "payment-events")).thenReturn(false);

        saga.handle(event, "payment-events");

        verify(process, never()).publishOutcome(any());
    }

    /** Возврат денег процесс не ведёт: запись в историю делает сама сага. */
    @Test
    void shouldRecordRefundItself() {
        PaymentEvent event = event(PaymentEvent.PAYMENT_REFUNDED);
        when(inbox.markProcessed(event.eventId(), "payment-events")).thenReturn(true);
        when(process.handles(event)).thenReturn(false);

        saga.handle(event, "payment-events");

        verify(process, never()).publishOutcome(any());
        verify(orderStatusService).recordEvent(eq(7L), eq(OrderEventType.ORDER_REFUNDED), anyString());
    }

    /** Таймер мог сработать у процесса, чей заказ уже оплачен или отменён: ничего не делаем. */
    @Test
    void shouldIgnoreTimeoutForOrderThatNoLongerAwaitsPayment() {
        when(orderStatusService.requireAccess(7L)).thenReturn(access(OrderStatus.PAID));

        saga.expireIfAwaiting(7L, Duration.ofMinutes(15));

        verify(orderStatusService, never()).tryTransition(anyLong(), any(), any(), any(), anyString());
        verifyNoInteractions(paymentCommands);
    }

    @Test
    void shouldCancelAndRefundWhenTimeoutFiresForPendingOrder() {
        OrderAccess pending = access(OrderStatus.PAYMENT_PENDING);
        when(orderStatusService.requireAccess(7L)).thenReturn(pending);
        when(orderStatusService.tryTransition(eq(7L), eq(OrderStatus.PAYMENT_PENDING), eq(OrderStatus.CANCELED),
                eq(OrderEventType.ORDER_CANCELED), anyString())).thenReturn(true);

        saga.expireIfAwaiting(7L, Duration.ofMinutes(15));

        verify(paymentCommands).requestRefund(pending);
    }

    /** PAYMENT_PENDING ведёт таймер процесса, задаче остаются только неоплаченные CREATED. */
    @Test
    void timeoutJobShouldLeavePendingOrdersToProcess() {
        new PaymentTimeoutJob(orderRepository, saga, Duration.ofMinutes(15), "camunda").cancelExpired();
        verify(orderRepository).findAwaitingPaymentSince(any(OffsetDateTime.class), eq(List.of(OrderStatus.CREATED)), anyInt());

        new PaymentTimeoutJob(orderRepository, saga, Duration.ofMinutes(15), "native").cancelExpired();
        verify(orderRepository).findAwaitingPaymentSince(any(OffsetDateTime.class),
                eq(List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING)), anyInt());
    }

    @Test
    void shouldTellProcessOnlyAboutCancelWhileAwaitingOutcome() {
        OrderCanceledMessageListener listener = new OrderCanceledMessageListener(process);

        listener.onStatusChanged(statusChange(OrderStatus.PAYMENT_PENDING, OrderStatus.CANCELED));
        listener.onStatusChanged(statusChange(OrderStatus.CREATED, OrderStatus.CANCELED));
        listener.onStatusChanged(statusChange(OrderStatus.PAID, OrderStatus.CANCELED));
        listener.onStatusChanged(statusChange(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID));

        verify(process).publishOrderCanceled(7L);
        verify(process).publishOrderCanceled(anyLong());
    }

    private static PaymentEvent event(String type) {
        return new PaymentEvent(UUID.randomUUID(), PaymentEvent.CURRENT_SCHEMA_VERSION, type, 7L, 70L, 1L,
                new BigDecimal("450.00"), "CARD", null, OffsetDateTime.now());
    }

    private static OrderAccess access(OrderStatus status) {
        return new OrderAccess(7L, 1L, 2L, status, new BigDecimal("450.00"));
    }

    private static OrderStatusChangedEvent statusChange(OrderStatus from, OrderStatus to) {
        return new OrderStatusChangedEvent(7L, 1L, 2L, from, to, OrderEventType.ORDER_CANCELED, new BigDecimal("450.00"));
    }
}
