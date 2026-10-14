package com.dima.fooddelivery.soap;

import com.dima.fooddelivery.order.domain.Order;
import com.dima.fooddelivery.order.domain.OrderSummary;
import com.dima.fooddelivery.order.service.OrderService;
import com.dima.fooddelivery.soap.partner.v1.AcceptOrderRequest;
import com.dima.fooddelivery.soap.partner.v1.AcceptOrderResponse;
import com.dima.fooddelivery.soap.partner.v1.GetOrderStatusRequest;
import com.dima.fooddelivery.soap.partner.v1.GetOrderStatusResponse;
import com.dima.fooddelivery.soap.partner.v1.ListOpenOrdersRequest;
import com.dima.fooddelivery.soap.partner.v1.ListOpenOrdersResponse;
import com.dima.fooddelivery.soap.partner.v1.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.GregorianCalendar;

/**
 * Заказы для касс ресторанов по SOAP.
 *
 * <p>Тонкий слой над {@link OrderService}, как и REST-контроллеры: проверка прав, вызов сервиса,
 * перенос полей. Переходы статусов и их правила — в сервисе, одни для обоих протоколов.
 * Права — {@code @access.managesRestaurant}, токен — обычный заголовок {@code Authorization: Bearer}.
 */
@Endpoint
@RequiredArgsConstructor
class OrderPartnerEndpoint {

    private final OrderService orderService;

    @PayloadRoot(namespace = PartnerSoapConfig.NAMESPACE, localPart = "ListOpenOrdersRequest")
    @PreAuthorize("@access.managesRestaurant(#request.restaurantId)")
    @ResponsePayload
    public ListOpenOrdersResponse listOpenOrders(@RequestPayload ListOpenOrdersRequest request) {
        ListOpenOrdersResponse response = new ListOpenOrdersResponse();

        orderService.getOpenOrdersForRestaurant(request.getRestaurantId()).stream()
                .map(OrderPartnerEndpoint::toOrder)
                .forEach(response.getOrder()::add);

        return response;
    }

    @PayloadRoot(namespace = PartnerSoapConfig.NAMESPACE, localPart = "GetOrderStatusRequest")
    @PreAuthorize("@access.managesRestaurant(#request.restaurantId)")
    @ResponsePayload
    public GetOrderStatusResponse getOrderStatus(@RequestPayload GetOrderStatusRequest request) {
        GetOrderStatusResponse response = new GetOrderStatusResponse();
        response.setOrder(toOrder(orderService.getOrderForRestaurant(request.getRestaurantId(), request.getOrderId())));

        return response;
    }

    @PayloadRoot(namespace = PartnerSoapConfig.NAMESPACE, localPart = "AcceptOrderRequest")
    @PreAuthorize("@access.managesRestaurant(#request.restaurantId)")
    @ResponsePayload
    public AcceptOrderResponse acceptOrder(@RequestPayload AcceptOrderRequest request) {
        orderService.acceptOrder(request.getRestaurantId(), request.getOrderId());

        AcceptOrderResponse response = new AcceptOrderResponse();
        response.setOrder(toOrder(orderService.getOrderForRestaurant(request.getRestaurantId(), request.getOrderId())));

        return response;
    }

    private static com.dima.fooddelivery.soap.partner.v1.Order toOrder(Order order) {
        return toOrder(order.id(), order.status().getDbValue(), order.totalAmount(), order.createdAt());
    }

    private static com.dima.fooddelivery.soap.partner.v1.Order toOrder(OrderSummary order) {
        return toOrder(order.id(), order.status().getDbValue(), order.totalAmount(), order.createdAt());
    }

    private static com.dima.fooddelivery.soap.partner.v1.Order toOrder(
            Long id, String status, BigDecimal totalAmount, OffsetDateTime createdAt
    ) {
        com.dima.fooddelivery.soap.partner.v1.Order result = new com.dima.fooddelivery.soap.partner.v1.Order();
        result.setId(id);
        // Значение перечисления в схеме совпадает с dbValue статуса: рассинхрон схемы и кода
        // обнаружит тест и валидация ответа, а не касса ресторана.
        result.setStatus(OrderStatus.valueOf(status));
        result.setTotalAmount(totalAmount);
        result.setCreatedAt(toXml(createdAt));

        return result;
    }

    private static XMLGregorianCalendar toXml(OffsetDateTime time) {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar.from(time.toZonedDateTime()));
        } catch (DatatypeConfigurationException exception) {
            throw new IllegalStateException("Не удалось сформировать время для SOAP-ответа", exception);
        }
    }
}
