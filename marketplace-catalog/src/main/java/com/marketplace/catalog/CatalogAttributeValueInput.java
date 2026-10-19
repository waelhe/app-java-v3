package com.marketplace.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One typed attribute value supplied for a product or variant. Exactly one
 * of textValue, numberValue, booleanValue, or optionId must be supplied;
 * the catalog validates it against the assigned definition on the server.
 */
public record CatalogAttributeValueInput(
        UUID assignmentId,
        String textValue,
        BigDecimal numberValue,
        Boolean booleanValue,
        UUID optionId) {

    public int populatedValueCount() {
        int count = 0;
        if (textValue != null) count++;
        if (numberValue != null) count++;
        if (booleanValue != null) count++;
        if (optionId != null) count++;
        return count;
    }
}
