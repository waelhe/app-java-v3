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
    }

    @Test
    void badRequestException_buildsProblemDetailWithContractFields() {
        BadRequestException ex = new BadRequestException("Invalid booking status: x");

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/bad-request"));
        assertThat(ex.getBody().getTitle()).isEqualTo("Bad Request");
        assertThat(ex.getBody().getDetail()).isEqualTo("Invalid booking status: x");
    }

    @Test
    void conflictException_buildsProblemDetailWithContractFields() {
        ConflictException ex = new ConflictException("Cannot transition from PENDING to COMPLETED");

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/conflict"));
        assertThat(ex.getBody().getTitle()).isEqualTo("Conflict");
        assertThat(ex.getBody().getDetail()).isEqualTo("Cannot transition from PENDING to COMPLETED");
    }
}
