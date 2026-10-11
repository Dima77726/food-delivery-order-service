package com.dima.fooddelivery.common.web;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Поведение сквозных заголовков на уровне HTTP.
 *
 * <p>Проверять это можно только запросом: фильтры живут в сервлет-цепочке, и вызов сервиса
 * напрямую их не задействует.
 */
@AutoConfigureMockMvc
class RequestCorrelationIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldGenerateCorrelationIdWhenClientDidNotSendOne() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String correlationId = result.getResponse().getHeader(RequestContext.CORRELATION_ID_HEADER);

        assertAll(
                () -> assertNotNull(correlationId, "метка обязана появиться даже без заголовка в запросе"),
                () -> assertTrue(correlationId.length() > 0)
        );
    }

    @Test
    void shouldKeepCorrelationIdSuppliedByClient() throws Exception {
        String supplied = "client-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(get("/v3/api-docs")
                        .header(RequestContext.CORRELATION_ID_HEADER, supplied))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                supplied,
                result.getResponse().getHeader(RequestContext.CORRELATION_ID_HEADER),
                "присланную клиентом метку нужно сохранить, иначе сшить логи с его стороной не выйдет"
        );
    }

    /**
     * Значение приходит снаружи и попадает в лог. Перевод строки в нём позволил бы
     * дописать в журнал поддельную строку — приём известен как log injection.
     */
    @Test
    void shouldStripDangerousCharactersFromSuppliedCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")
                        .header(RequestContext.CORRELATION_ID_HEADER, "abc\nINFO подделка"))
                .andExpect(status().isOk())
                .andReturn();

        String correlationId = result.getResponse().getHeader(RequestContext.CORRELATION_ID_HEADER);

        assertAll(
                () -> assertFalseContains(correlationId, "\n"),
                () -> assertFalseContains(correlationId, " "),
                () -> assertTrue(
                        correlationId.length() <= RequestContext.MAX_LENGTH,
                        "длина обязана быть ограничена"
                )
        );
    }

    @Test
    void shouldTruncateOverlongCorrelationId() throws Exception {
        String tooLong = "x".repeat(RequestContext.MAX_LENGTH * 3);

        MvcResult result = mockMvc.perform(get("/v3/api-docs")
                        .header(RequestContext.CORRELATION_ID_HEADER, tooLong))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                RequestContext.MAX_LENGTH,
                result.getResponse().getHeader(RequestContext.CORRELATION_ID_HEADER).length()
        );
    }

    /**
     * Ключевая проверка всей затеи.
     *
     * <p>Клиент присылает чужой {@code X-User-Id} вместе со своим токеном. Сервер обязан
     * полностью его проигнорировать и назвать того, кто подписан в токене. Если этот тест
     * когда-нибудь упадёт, значит появился способ выдать себя за другого пользователя.
     */
    @Test
    void shouldIgnoreUserIdSuppliedByClient() throws Exception {
        long ownId = testData.insertCustomer();
        String token = jwt.tokenFor(ownId, "CUSTOMER");

        String foreignId = String.valueOf(ownId + 1000);

        MvcResult result = mockMvc.perform(get("/api/v1/customers/{id}/orders", ownId)
                        .header("Authorization", "Bearer " + token)
                        .header(RequestContext.USER_ID_HEADER, foreignId))
                .andExpect(status().isOk())
                .andReturn();

        String reported = result.getResponse().getHeader(RequestContext.USER_ID_HEADER);

        assertAll(
                () -> assertEquals(String.valueOf(ownId), reported, "сервер обязан назвать владельца токена"),
                () -> assertNotEquals(foreignId, reported, "подставленный клиентом идентификатор не должен приниматься")
        );
    }

    @Test
    void shouldNotReportUserIdForAnonymousRequest() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")
                        .header(RequestContext.USER_ID_HEADER, "12345"))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(
                null,
                result.getResponse().getHeader(RequestContext.USER_ID_HEADER),
                "у анонимного запроса пользователя нет, и выдумывать его нельзя"
        );
    }

    private void assertFalseContains(String value, String forbidden) {
        assertTrue(!value.contains(forbidden), "в метке не должно остаться «" + forbidden + "»");
    }
}
