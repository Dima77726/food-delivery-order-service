package com.dima.fooddelivery.soap;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.XsdSchemaCollection;
import org.springframework.xml.xsd.commons.CommonsXsdSchemaCollection;

import java.util.List;

/**
 * Партнёрский SOAP API заказов: приём и статусы заказов для касс ресторанов.
 *
 * <p>Контракт первичен: схема {@code soap/partner-order-v1.xsd} приезжает в jar
 * {@code soap-contracts}, из неё же сгенерированы классы сообщений. WSDL не пишется руками, а
 * строится по этой схеме — {@code GET /ws/orders.wsdl}. Схема разделена по сервисам, чтобы WSDL
 * заказов не рекламировал операции каталога.
 *
 * <p>Запрос уходит в {@link OrderPartnerEndpoint} по имени корневого элемента.
 */
@EnableWs
@Configuration
class PartnerSoapConfig implements WsConfigurer {

    static final String NAMESPACE = "urn:fooddelivery:partner:v1";

    /** Адрес, по которому партнёр шлёт запросы. По нему же шлюз выбирает сервис. */
    static final String ORDERS_PATH = "/ws/orders";

    private static final String ORDER_SCHEMA = "soap/partner-order-v1.xsd";

    /**
     * Проверка по схеме и на входе, и на выходе. Вход проверять обязательно: JAXB «прощает»
     * многое, а схема требует положительные идентификаторы и деньги с двумя знаками. Выход
     * проверяется, чтобы ошибка маппинга всплыла у нас в тестах, а не у партнёра в кассе.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        PayloadValidatingInterceptor validation = new PayloadValidatingInterceptor();
        validation.setSchema(new ClassPathResource(ORDER_SCHEMA));
        validation.setValidateRequest(true);
        validation.setValidateResponse(true);
        validation.setAddValidationErrorDetail(true);

        // Создан не контейнером, поэтому его жизненный цикл на нас: без этого вызова валидатор схемы
        // не собирается, и каждый запрос падает с NullPointerException.
        try {
            validation.afterPropertiesSet();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось подготовить проверку SOAP-сообщений по схеме", exception);
        }

        interceptors.add(validation);
    }

    /**
     * Сервлет регистрируется вручную: автоконфигурация Spring Boot отступает, как только
     * включён {@code @EnableWs}, и без этого {@code /ws/*} достался бы MVC и отвечал 404.
     * Адреса в WSDL подменяются на адрес, по которому к сервису обратились (через шлюз тоже).
     */
    @Bean
    ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);

        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    @Bean
    PartnerFaultExceptionResolver partnerFaultExceptionResolver() {
        return new PartnerFaultExceptionResolver();
    }

    /** Имя бина — имя файла: {@code GET /ws/orders.wsdl}. */
    @Bean(name = "orders")
    DefaultWsdl11Definition ordersWsdl() {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("OrderPartnerPort");
        wsdl.setServiceName("OrderPartnerService");
        wsdl.setLocationUri(ORDERS_PATH);
        wsdl.setTargetNamespace(NAMESPACE);
        wsdl.setSchemaCollection(orderSchemas());

        return wsdl;
    }

    /** Общая часть подключена в схеме через xs:include; inline кладёт её прямо в WSDL, чтобы клиенту хватило одного файла. */
    @Bean
    XsdSchemaCollection orderSchemas() {
        CommonsXsdSchemaCollection schemas = new CommonsXsdSchemaCollection(new ClassPathResource(ORDER_SCHEMA));
        schemas.setInline(true);

        return schemas;
    }
}
