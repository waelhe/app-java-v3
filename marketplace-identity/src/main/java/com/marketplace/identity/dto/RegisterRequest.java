package com.marketplace.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration request payload.
 * <p>Validation follows OWASP Authentication Cheat Sheet:
 * <ul>
 *   <li>Email: standard format validation</li>
 *   <li>Password: min 8 chars (enforced by service layer for complex rules)</li>
 * </ul>
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 200) String displayName
) {
}
