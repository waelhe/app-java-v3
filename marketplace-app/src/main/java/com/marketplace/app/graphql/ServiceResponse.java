package com.marketplace.app.graphql;

import java.util.UUID;

public record ServiceResponse(
    UUID id,
    String name,
    String description,
    double price,
    String status
) {
}
