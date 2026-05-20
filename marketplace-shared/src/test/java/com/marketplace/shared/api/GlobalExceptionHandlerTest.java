package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidation_returnsProblemDetailWithoutTimestampAndWithFieldErrors() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must be a well-formed email address"));

        MethodParameter methodParameter = new MethodParameter(
                TestController.class.getDeclaredMethod("submit", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        HttpServletRequest request = new StubHttpServletRequest("/api/users");
        var response = handler.handleValidation(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/validation"));
        assertThat(response.getInstance()).isEqualTo(URI.create("/api/users"));
        assertThat(response.getProperties()).doesNotContainKey("timestamp");
        assertThat(response.getProperties().get("fieldErrors")).isEqualTo(
                List.of(new ErrorResponse.FieldError("email", "must be a well-formed email address")));
    }



    @Test
    void handleResourceNotFound_usesExceptionProblemDetailContract() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Listing", 123L);

        HttpServletRequest request = new StubHttpServletRequest("/api/listings/123");
        ProblemDetail response = handler.handleResourceNotFound(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/not-found"));
        assertThat(response.getTitle()).isEqualTo("Not Found");
        assertThat(response.getInstance()).isEqualTo(URI.create("/api/listings/123"));
        assertThat(response.getProperties()).isNullOrEmpty();
    }

    static class TestController {
        public void submit(String email) {
        }
    }
}
