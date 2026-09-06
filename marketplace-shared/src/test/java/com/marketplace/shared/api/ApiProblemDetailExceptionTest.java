package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiProblemDetailExceptionTest {

    @Test
    void resourceNotFoundException_buildsProblemDetailWithContractFields() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Listing", 42L);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/not-found"));
        assertThat(ex.getBody().getTitle()).isEqualTo("Not Found");
        assertThat(ex.getBody().getDetail()).isEqualTo("Listing not found: 42");
        assertThat(ex.getBody().getProperties().get("errorCode")).isEqualTo("NF-001");
        assertThat(ex.getBody().getProperties().get("category")).isEqualTo("not-found");
    }

    @Test
    void badRequestException_buildsProblemDetailWithContractFields() {
        BadRequestException ex = new BadRequestException("Invalid booking status: x");

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/validation"));
        assertThat(ex.getBody().getTitle()).isEqualTo("Bad Request");
        assertThat(ex.getBody().getDetail()).isEqualTo("Invalid booking status: x");
        assertThat(ex.getBody().getProperties().get("errorCode")).isEqualTo("VAL-001");
        assertThat(ex.getBody().getProperties().get("category")).isEqualTo("validation");
    }

    @Test
    void conflictException_buildsProblemDetailWithContractFields() {
        ConflictException ex = new ConflictException("Cannot transition from PENDING to COMPLETED");

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/conflict"));
        assertThat(ex.getBody().getTitle()).isEqualTo("Conflict");
        assertThat(ex.getBody().getDetail()).isEqualTo("Cannot transition from PENDING to COMPLETED");
        assertThat(ex.getBody().getProperties().get("errorCode")).isEqualTo("CONFLICT-001");
        assertThat(ex.getBody().getProperties().get("category")).isEqualTo("conflict");
    }

    @Test
    void serviceUnavailableException_buildsProblemDetailWithContractFields() {
        ServiceUnavailableException ex = new ServiceUnavailableException("Media storage is not configured");

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/service-unavailable"));
        assertThat(ex.getBody().getTitle()).isEqualTo("Service Unavailable");
        assertThat(ex.getBody().getDetail()).isEqualTo("Media storage is not configured");
        assertThat(ex.getBody().getProperties().get("errorCode")).isEqualTo("SU-001");
        assertThat(ex.getBody().getProperties().get("category")).isEqualTo("availability");
    }
}
