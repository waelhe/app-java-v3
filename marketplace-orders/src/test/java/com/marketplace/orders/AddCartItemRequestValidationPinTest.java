package com.marketplace.orders;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic pin: mirrors the EXACT HTTP leg of {@code POST /api/v1/me/cart/items}
 * (Jackson 3 deserialization of the request body into the record, then jakarta
 * validation of the record) so the Integration Test's VAL-001 root is visible
 * without containers. If a valid-looking body violates a constraint here, the
 * field and message name the constraint — the piece the IT's truncated
 * assertion message could not carry.
 */
class AddCartItemRequestValidationPinTest {

    private static ValidatorFactory factory;

    @BeforeAll
    static void boot() {
        factory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void shutdown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void theJourneysExactBodyDeserializesAndValidates() {
        ObjectMapper mapper = new ObjectMapper();
        UUID product = UUID.randomUUID();
        String body = """
                {"productId":"%s","quantity":3}"""
                .formatted(product);

        CartController.AddCartItemRequest request = mapper.readValue(body,
                CartController.AddCartItemRequest.class);

        assertThat(request.productId()).isEqualTo(product);
        assertThat(request.quantity()).isEqualTo(3);

        Validator validator = factory.getValidator();
        Set<ConstraintViolation<CartController.AddCartItemRequest>> violations =
                validator.validate(request);
        assertThat(violations)
                .as("the IT body must pass every constraint: %s",
                        violations.stream()
                                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                                .toList())
                .isEmpty();
    }
}
