package com.marketplace.app.graphql;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * mutation-error taxonomy mapper — the frozen §5 contract's "native
 * domain validation errors are mapped into the Saleor wire shape" leg.
 * One place, one mapping, every payload error field rides it.
 *
 * <p>Authorization failures do NOT become payload errors: they propagate
 * as classified GraphQL errors through the standing
 * {@code GraphQlExceptionResolver} (AccessDeniedException → the AUTHZ
 * taxonomy) — the boundary the read roots already ride.</p>
 */
final class SaleorMutationErrors {

    private SaleorMutationErrors() {
    }

    /** The platform-native error → the Saleor wire code vocabulary. */
    static String codeOf(Throwable failure) {
        if (failure instanceof ResourceNotFoundException) {
            return "NOT_FOUND";
        }
        if (failure instanceof ConflictException) {
            return "ALREADY_EXISTS";
        }
        if (failure instanceof AccessDeniedException) {
            // Never reached in payload mapping (authorization propagates as
            // a classified GraphQL error) — the guard stays for safety.
            return "OUT_OF_SCOPE_PERMISSION";
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof jakarta.validation.ConstraintViolationException) {
            return messageRequires(failure.getMessage()) ? "REQUIRED" : "INVALID";
        }
        return "GRAPHQL_ERROR";
    }

    private static boolean messageRequires(String message) {
        return message != null && message.toLowerCase().contains("is required");
    }

    /** The payload error triple the generated builders consume. */
    record WireError(String field, String message, String code) {
    }

    static WireError of(Throwable failure) {
        return new WireError(null, safeMessage(failure), codeOf(failure));
    }

    static WireError of(Throwable failure, String field) {
        return new WireError(field, safeMessage(failure), codeOf(failure));
    }

    /** No internal detail ever leaks (the codec precedent's uniform message rule). */
    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "The operation was rejected" : message;
    }
}
