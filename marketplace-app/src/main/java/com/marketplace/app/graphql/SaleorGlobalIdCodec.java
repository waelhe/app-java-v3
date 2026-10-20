package com.marketplace.app.graphql;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Converts the platform's native UUID identifiers to and from typed
 * Relay-style global IDs at the GraphQL boundary.
 *
 * <p>The wire format is standard Base64 over the UTF-8 payload
 * {@code GraphQLTypeName:canonical-uuid}, matching the typed-ID convention
 * used by Saleor/Relay while keeping database identifiers native UUIDs.
 * The codec deliberately does not resolve entities or authorize access:
 * callers must perform ownership and permission checks in the owning
 * domain use case after decoding.</p>
 *
 * <p>This class is a compatibility primitive only. It does not claim that
 * any Saleor root operation, nested type closure, or Dashboard journey is
 * implemented by its presence.</p>
 */
@Component
public final class SaleorGlobalIdCodec {

    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");
    private static final int MAX_TYPE_NAME_LENGTH = 128;
    private static final int MAX_GLOBAL_ID_LENGTH = 256;
    private static final String INVALID_GLOBAL_ID = "Invalid global ID";

    /**
     * Encodes a native UUID as a typed global ID.
     *
     * @param typeName GraphQL object type name, for example {@code Product}
     * @param nativeId native platform UUID
     * @return standard Base64 representation of {@code typeName:uuid}
     * @throws IllegalArgumentException when the type name or UUID is invalid
     */
    public String encode(String typeName, UUID nativeId) {
        validateTypeName(typeName);
        if (nativeId == null) {
            throw invalidGlobalId();
        }

        String payload = typeName + ":" + nativeId;
        String encoded = Base64.getEncoder()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > MAX_GLOBAL_ID_LENGTH) {
            throw invalidGlobalId();
        }
        return encoded;
    }

    /**
     * Decodes a typed global ID and verifies that its type is the expected
     * GraphQL type. Raw UUIDs and IDs for a different object type are rejected.
     *
     * @param globalId opaque client-supplied global ID
     * @param expectedTypeName GraphQL type expected by the calling resolver
     * @return decoded native UUID
     * @throws IllegalArgumentException for malformed, non-canonical, mistyped,
     *                                  or otherwise invalid global IDs
     */
    public UUID decode(String globalId, String expectedTypeName) {
        validateTypeName(expectedTypeName);
        if (globalId == null || globalId.isBlank()
                || globalId.length() > MAX_GLOBAL_ID_LENGTH) {
            throw invalidGlobalId();
        }

        final byte[] decodedBytes;
        try {
            decodedBytes = Base64.getDecoder().decode(globalId);
        } catch (IllegalArgumentException exception) {
            throw invalidGlobalId();
        }

        // Enforce a single canonical Base64 spelling rather than accepting
        // whitespace, URL-safe substitutions, or omitted padding variants.
        if (!Base64.getEncoder().encodeToString(decodedBytes).equals(globalId)) {
            throw invalidGlobalId();
        }

        final String payload;
        try {
            payload = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decodedBytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalidGlobalId();
        }

        int separator = payload.indexOf(':');
        if (separator <= 0 || separator != payload.lastIndexOf(':')
                || separator == payload.length() - 1) {
            throw invalidGlobalId();
        }

        String typeName = payload.substring(0, separator);
        if (!isValidTypeName(typeName) || !typeName.equals(expectedTypeName)) {
            throw invalidGlobalId();
        }

        String idValue = payload.substring(separator + 1);
        try {
            UUID nativeId = UUID.fromString(idValue);
            if (!nativeId.toString().equals(idValue)) {
                throw invalidGlobalId();
            }
            return nativeId;
        } catch (IllegalArgumentException exception) {
            throw invalidGlobalId();
        }
    }

    private static void validateTypeName(String typeName) {
        if (!isValidTypeName(typeName)) {
            throw invalidGlobalId();
        }
    }

    private static boolean isValidTypeName(String typeName) {
        return typeName != null
                && !typeName.isEmpty()
                && typeName.length() <= MAX_TYPE_NAME_LENGTH
                && GRAPHQL_NAME.matcher(typeName).matches();
    }

    private static IllegalArgumentException invalidGlobalId() {
        return new IllegalArgumentException(INVALID_GLOBAL_ID);
    }
}
