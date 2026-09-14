package com.marketplace.messaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * L34 (realestate systems plan §5 — lead capture): the public submission
 * body. Bean validation is the first 400 gate; the entity factory is the
 * second (the type-gate philosophy — an invalid lead never becomes a
 * row). The bounds mirror {@link ListingLead}'s documented constants.
 */
public record LeadRequest(

        @NotBlank
        @Size(max = 120)
        @Schema(description = "The name the provider should reply to.",
                example = "Sami Ahmad")
        String contactName,

        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^\\+?[0-9]{7,15}$",
                 message = "must be 7-15 digits with an optional leading '+'")
        @Schema(description = "Callback phone — 7-15 digits, optional leading '+'.",
                example = "+963991234567")
        String contactPhone,

        @NotBlank
        @Size(max = 2000)
        @Schema(description = "The message to the provider (max 2000 characters).",
                example = "Is the apartment still available for October?")
        String message
) {
}
