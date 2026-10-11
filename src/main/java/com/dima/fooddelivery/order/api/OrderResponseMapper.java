package com.dima.fooddelivery.order.api;

import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderEvent;
import com.dima.fooddelivery.order.domain.OrderItem;
import com.dima.fooddelivery.order.domain.OrderSummary;

import java.util.List;

public final class OrderResponseMapper {

    public static OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.id(),
                order.cartId(),
                order.customerId(),
                order.restaurantId(),
                order.status().getDbValue(),
                order.totalAmount(),
                order.items().stream().map(OrderResponseMapper::toResponse).toList()
        );
    }

    public static OrderSummaryResponse toResponse(OrderSummary summary) {
        return new OrderSummaryResponse(
                summary.id(),
                summary.cartId(),
                summary.customerId(),
                summary.restaurantId(),
                summary.status().getDbValue(),
                summary.totalAmount(),
                summary.itemsCount(),
                summary.createdAt()
        );
    }

    public static OrderEventResponse toResponse(OrderEvent event) {
        return new OrderEventResponse(
                event.id(),
                event.orderId(),
                event.eventType().getDbValue(),
                event.description(),
                event.createdAt()
        );
    }

    public static List<OrderSummaryResponse> toSummaryResponses(List<OrderSummary> summaries) {
        return summaries.stream().map(OrderResponseMapper::toResponse).toList();
    }

    public static List<OrderEventResponse> toEventResponses(List<OrderEvent> events) {
        return events.stream().map(OrderResponseMapper::toResponse).toList();
    }

    private static OrderItemResponse toResponse(OrderItem item) {
        return new OrderItemResponse(
                item.id(),
                item.menuItemId(),
                item.menuItemName(),
                item.quantity(),
                item.price(),
                item.lineTotal()
        );
    }

    private OrderResponseMapper() {
    }
}
