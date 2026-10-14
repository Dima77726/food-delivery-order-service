package com.dima.fooddelivery.soap;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.common.web.RequestContext;
import com.dima.fooddelivery.soap.partner.v1.FaultCode;
import com.dima.fooddelivery.soap.partner.v1.PartnerFault;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.AbstractEndpointExceptionResolver;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapMessage;

import java.util.Locale;

/**
 * Превращает исключения предметной области в SOAP Fault с деталью {@link PartnerFault}.
 *
 * <p>Партнёр разбирает ответ программой, поэтому у ошибки есть код (для ветвления) и текст
 * (для человека), как и у REST-ответов. Ошибки клиента — {@code Client}, сбои на нашей стороне —
 * {@code Server}: по этому признаку решают, повторять ли запрос.
 *
 * <p>Текст внутренней ошибки наружу не отдаётся: стек и детали остаются в журнале, партнёру
 * достаточно метки запроса, по которой их найдут.
 */
@Slf4j
class PartnerFaultExceptionResolver extends AbstractEndpointExceptionResolver {

    private static final JAXBContext FAULT_CONTEXT = faultContext();

    /**
     * Первым в очереди: среди стандартных обработчиков Spring WS есть тот, что превращает любое
     * исключение в {@code Server}-fault с текстом исключения, и без приоритета он перехватил бы
     * ошибки раньше нас.
     */
    PartnerFaultExceptionResolver() {
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    protected boolean resolveExceptionInternal(MessageContext messageContext, Object endpoint, Exception exception) {
        FaultCode code = codeOf(exception);
        String message = messageOf(code, exception);

        if (code == FaultCode.INTERNAL) {
            log.error("SOAP-запрос завершился ошибкой", exception);
        } else {
            log.warn("SOAP-запрос отклонён: code={}, reason={}", code, exception.getMessage());
        }

        SoapBody body = ((SoapMessage) messageContext.getResponse()).getSoapBody();
        boolean ourFault = code == FaultCode.UNAVAILABLE || code == FaultCode.INTERNAL;
        SoapFault fault = ourFault
                ? body.addServerOrReceiverFault(message, Locale.ROOT)
                : body.addClientOrSenderFault(message, Locale.ROOT);

        PartnerFault detail = new PartnerFault();
        detail.setCode(code);
        detail.setMessage(message);
        detail.setCorrelationId(RequestContext.correlationId().orElse(null));

        try {
            FAULT_CONTEXT.createMarshaller().marshal(detail, fault.addFaultDetail().getResult());
        } catch (JAXBException marshalFailure) {
            // Деталь — дополнение к faultstring: без неё ответ остаётся корректным Fault.
            log.warn("Не удалось записать деталь SOAP Fault", marshalFailure);
        }

        return true;
    }

    private static FaultCode codeOf(Exception exception) {
        if (exception instanceof AccessDeniedException || exception instanceof AccessDeniedForResourceException) {
            return FaultCode.FORBIDDEN;
        }

        if (exception instanceof ResourceNotFoundException) {
            return FaultCode.NOT_FOUND;
        }

        if (exception instanceof BusinessRuleViolationException) {
            return FaultCode.CONFLICT;
        }

        if (exception instanceof UpstreamUnavailableException) {
            return FaultCode.UNAVAILABLE;
        }

        return FaultCode.INTERNAL;
    }

    private static String messageOf(FaultCode code, Exception exception) {
        return switch (code) {
            case FORBIDDEN -> "Нет прав на этот ресторан или заказ";
            case UNAVAILABLE -> "Сервис временно недоступен, повторите позже";
            case INTERNAL -> "Внутренняя ошибка сервиса";
            default -> exception.getMessage();
        };
    }

    private static JAXBContext faultContext() {
        try {
            return JAXBContext.newInstance(PartnerFault.class);
        } catch (JAXBException exception) {
            throw new IllegalStateException("Не удалось подготовить JAXB для PartnerFault", exception);
        }
    }
}
