package com.marketplace.app.graphql;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SaleorMutationErrors.class);

    private static final String GENERIC_REJECTION = "The operation was rejected";

    private SaleorMutationErrors() {
    }

    /**
     * The payload message for an arbitrary caught failure — the SAME
     * authored-message rule every payload error field rides
     * ({@code safeMessage} below), exposed for the bulk paths that build
     * per-row payload errors from transport-level exceptions instead of
     * the owner's {@code BulkRowResult} (the 2026-11-03 #806 review,
     * Minor · Functional Correctness: the row-level
     * {@code customerBulkUpdate} used to discard the row failure's
     * class and answer every failed row a generic INVALID).
     *
     * @param failure the caught row-level failure
     * @return the operator-authored message when the failure type is
     *         authored, else the generic rejection
     */
    static String wireMessage(Throwable failure) {
        return safeMessage(failure);
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

    /**
     * The payload vocabulary translation (the wave-4 review, Minor · Data
     * Integrity): each mutation's payload carries its OWN Saleor error
     * enum, and the vocabularies DIVERGE — {@code MenuErrorCode} carries
     * no {@code ALREADY_EXISTS}, and {@code ShopErrorCode},
     * {@code PageErrorCode} and {@code MenuErrorCode} carry no
     * {@code OUT_OF_SCOPE_PERMISSION}. A raw {@code valueOf} on a native
     * code a vocabulary lacks throws {@code IllegalArgumentException} and
     * destroys the payload build.
     *
     * <p>The translation contract: a code the vocabulary CARRIES passes
     * through verbatim; the duplicate-creation code translates to the
     * vocabulary's own {@code UNIQUE} (the Saleor SDL's duplicate-code
     * for exactly the menus family); every other gap answers the
     * universal {@code GRAPHQL_ERROR} fallback. The mapping is data
     * driven off the enum itself — no per-vocabulary tables to drift.</p>
     *
     * @param vocabulary the payload's own error enum
     * @param nativeCode the platform-native code from {@link #codeOf}
     * @param <E>        the payload error enum type
     * @return the representable vocabulary constant, never null
     */
    static <E extends Enum<E>> E translate(Class<E> vocabulary, String nativeCode) {
        E direct = constantOf(vocabulary, nativeCode);
        if (direct != null) {
            return direct;
        }
        if ("ALREADY_EXISTS".equals(nativeCode)) {
            E unique = constantOf(vocabulary, "UNIQUE");
            if (unique != null) {
                return unique;
            }
        }
        E fallback = constantOf(vocabulary, "GRAPHQL_ERROR");
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException(
                "The payload error vocabulary carries no GRAPHQL_ERROR fallback: "
                        + vocabulary.getName());
    }

    private static <E extends Enum<E>> E constantOf(Class<E> vocabulary, String name) {
        if (name == null) {
            // The 2026-11-02 #806 review (Minor · Stability): Enum.valueOf
            // throws NullPointerException — not the IllegalArgumentException
            // the catch ladder maps — so an absent name must answer before
            // the lookup; translate then renders the GRAPHQL_ERROR fallback
            // its own contract promises (the return is never null).
            return null;
        }
        try {
            return Enum.valueOf(vocabulary, name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    /**
     * The payload message (the wave-4 review, Major · Security & Privacy;
     * the wave-7 review, Minor · Security & Privacy): ONLY the mapped
     * domain exceptions' messages reach the wire — the owner modules'
     * {@code ApiProblemDetailException} family ({@code
     * ResourceNotFoundException}, {@code ConflictException}), whose
     * sentences are operator-authored by construction. JDK and library
     * types are NEVER authored, no matter who threw them: a raw {@code
     * getMessage()} can carry property paths, SQL text, enum names or
     * argument values — internal detail the §5 contract never leaks — so
     * an unmapped throwable (a {@code DataIntegrityViolationException}'s
     * SQL text, a library's {@code IllegalArgumentException}, a bean
     * validation's {@code ConstraintViolationException}) answers the
     * generic rejection, and the exception goes to the internal log at
     * the level its taxonomy earns: WARN with the stack trace for the
     * unexpected, DEBUG for the classified client-input pair — a
     * row-level bulk policy answers one malformed row per client
     * mistake, the per-row wire error is the report, and a
     * WARN-plus-stack per row would flood the operator log (the
     * 2026-11-02 #806 review, Minor · Performance & Scalability). Any
     * future authored validation sentence must travel a domain exception
     * type of the owner's own API surface (added to {@code authored}
     * below), never a JDK or library type.
     */
    private static String safeMessage(Throwable failure) {
        boolean authored = failure instanceof ResourceNotFoundException
                || failure instanceof ConflictException;
        if (!authored) {
            if (failure instanceof IllegalArgumentException
                    || failure instanceof jakarta.validation.ConstraintViolationException) {
                // The client-input pair the codeOf ladder already
                // classifies (REQUIRED/INVALID): a row-level bulk policy
                // answers one malformed row per client mistake — the
                // per-row wire error is the report, so the stack trace
                // rides DEBUG (never a WARN-plus-stack per row) and the
                // message is not interpolated back (the exception
                // carries it).
                log.debug("Client input rejected; the raw message stays internal",
                        failure);
            } else {
                log.warn("Mutation failure mapped to the generic payload "
                        + "rejection; the raw message stays internal", failure);
            }
            return GENERIC_REJECTION;
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? GENERIC_REJECTION : message;
    }
}
