package com.marketplace.app.graphql;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One typed value on a product or variant assignment. Exactly one value field
 * is permitted by the owning catalog use case; numberValue stays decimal text
 * on the GraphQL wire to avoid floating-point loss.
 */
public record StoreProductAttributeValueInput(
        @NotNull UUID assignmentId,
        @Size(max = 4000) String textValue,
        @Size(max = 100) @Pattern(regexp = "^-?(?:0|[1-9][0-9]*)(?:\\\\.[0-9]+)?$") String numberValue,
        Boolean booleanValue,
        UUID optionId) { }
