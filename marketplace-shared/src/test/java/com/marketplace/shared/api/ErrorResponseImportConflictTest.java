package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.ErrorResponse;

class ErrorResponseImportConflictTest {

    @Test
    void springErrorResponseAndLocalPayloadCanCoexistWithoutImportConflict() {
        ApiErrorPayload payload = new ApiErrorPayload(400, "Bad Request", "Validation failed", "/api/test");
        ErrorResponse springContract = new ResourceNotFoundException("Listing", 99L);

        assertThat(payload.error()).isEqualTo("Bad Request");
        assertThat(springContract.getStatusCode().value()).isEqualTo(404);
    }
}
