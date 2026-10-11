package com.dima.fooddelivery.order.grpc;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.delivery.domain.Delivery;
import com.dima.fooddelivery.delivery.persistence.DeliveryRepository;
import com.dima.fooddelivery.grpc.order.v1.DeliveryView;
import com.dima.fooddelivery.grpc.order.v1.GetDeliveryRequest;
import com.dima.fooddelivery.grpc.order.v1.GetOrderRequest;
import com.dima.fooddelivery.grpc.order.v1.ListOrdersAfterRequest;
import com.dima.fooddelivery.grpc.order.v1.ListOrdersAfterResponse;
import com.dima.fooddelivery.grpc.order.v1.OrderItemView;
import com.dima.fooddelivery.grpc.order.v1.OrderQueryServiceGrpc;
import com.dima.fooddelivery.grpc.order.v1.OrderView;
import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderItem;
import com.dima.fooddelivery.order.persistence.OrderRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

/**
 * gRPC-вход в заказы для других сервисов — только чтение.
 *
 * <p>Читают монолитные модули, которые ещё не вынесены: отзывы проверяют, что заказ свой
 * и доставлен; граф рекомендаций строится по позициям заказов; трек курьера пишется только
 * по его доставке. Права здесь не проверяются — это делает вызывающий, по своему токену:
 * сервис заказов не знает, от чьего имени его спрашивают.
 *
 * <p>Транзакция только читающая и нужна ради одного снимка: заказ и его доставка читаются
 * двумя запросами и не должны разойтись между ними.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryGrpcService extends OrderQueryServiceGrpc.OrderQueryServiceImplBase {

    /** Больше за раз не отдаётся: ответ gRPC целиком лежит в памяти обеих сторон. */
    private static final int MAX_BATCH = 500;

    private final OrderRepository orderRepository;
    private final DeliveryRepository deliveryRepository;

    @Override
    @Transactional(readOnly = true)
    public void getOrder(GetOrderRequest request, StreamObserver<OrderView> responseObserver) {
        respond(responseObserver, () -> {
            Order order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Заказ с id=" + request.getOrderId() + " не найден"));

            return toView(order);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public void listOrdersAfter(ListOrdersAfterRequest request, StreamObserver<ListOrdersAfterResponse> responseObserver) {
        respond(responseObserver, () -> {
            int limit = Math.clamp(request.getLimit(), 1, MAX_BATCH);
            List<Order> orders = orderRepository.findAllAfterId(request.getAfterOrderId(), limit);

            return ListOrdersAfterResponse.newBuilder()
                    .addAllOrders(orders.stream().map(this::toView).toList())
                    .build();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public void getDelivery(GetDeliveryRequest request, StreamObserver<DeliveryView> responseObserver) {
        respond(responseObserver, () -> deliveryRepository.findById(request.getDeliveryId())
                .map(OrderQueryGrpcService::toView)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Доставка с id=" + request.getDeliveryId() + " не найдена")));
    }

    private OrderView toView(Order order) {
        OrderView.Builder view = OrderView.newBuilder()
                .setId(order.id())
                .setCustomerId(order.customerId())
                .setRestaurantId(order.restaurantId())
                .setStatus(order.status().getDbValue())
                .setTotalAmount(order.totalAmount().toPlainString())
                .setCreatedAt(order.createdAt().toString())
                .addAllItems(order.items().stream().map(OrderQueryGrpcService::toView).toList());

        deliveryRepository.findByOrderId(order.id())
                .map(OrderQueryGrpcService::toView)
                .ifPresent(view::setDelivery);

        return view.build();
    }

    private static OrderItemView toView(OrderItem item) {
        return OrderItemView.newBuilder()
                .setMenuItemId(item.menuItemId())
                .setMenuItemName(item.menuItemName())
                .setQuantity(item.quantity())
                .setPrice(item.price().toPlainString())
                .build();
    }

    private static DeliveryView toView(Delivery delivery) {
        DeliveryView.Builder view = DeliveryView.newBuilder()
                .setId(delivery.id())
                .setOrderId(delivery.orderId())
                .setStatus(delivery.status().getDbValue());

        if (delivery.courierId() != null) {
            view.setCourierId(delivery.courierId());
        }

        return view.build();
    }

    /**
     * «Нет такого» — NOT_FOUND, всё прочее уходит как INTERNAL: подробности внутренней ошибки
     * клиенту ни к чему, они остаются в логе сервиса заказов.
     */
    private <T> void respond(StreamObserver<T> responseObserver, Supplier<T> action) {
        T response;

        try {
            response = action.get();
        } catch (ResourceNotFoundException exception) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(exception.getMessage()).asRuntimeException());
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
