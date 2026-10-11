package com.dima.fooddelivery.common.exception;

import com.dima.fooddelivery.common.api.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldMapResourceNotFoundToNotFoundResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants/99");

        ResponseEntity<ApiErrorResponse> response = handler.handleResourceNotFound(
                new ResourceNotFoundException("not found"),
                request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().status());
        assertEquals("Not Found", response.getBody().error());
        assertEquals("not found", response.getBody().message());
        assertEquals("/api/v1/restaurants/99", response.getBody().path());
    }

    @Test
    void shouldMapBusinessRuleViolationToConflictResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");

        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessRuleViolation(
                new BusinessRuleViolationException("invalid transition"),
                request
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getBody().status());
        assertEquals("Conflict", response.getBody().error());
        assertEquals("invalid transition", response.getBody().message());
        assertEquals("/api/v1/orders", response.getBody().path());
    }
}
