package com.marketplace.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical typed-option combination key — ONE derivation shared by
 * every writer that binds a variant's {@code optionCombinationKey}: the
 * registration path ({@code ProductVariantService.register}) and the
 * assignment-scope move guard ({@code ProductTypeAttributeWriteService}
 * .updateAssignments — the 2026-11-06 #808 round review, Major · Data
 * Integrity &amp; Integration). The key is the SHA-256 of the
 * selection-scope assignments' own {@code assignmentId=optionId} pairs,
 * sorted by the assignment id's string form and joined with the newline
 * separator — the exact bytes the registration path computed at the
 * variant's own creation, so a re-derivation over a CHANGED selection
 * schema stays byte-compatible with the same schema's fresh
 * registrations (the move guard's whole guarantee).
 */
final class VariantCombinationKeys {

    private VariantCombinationKeys() {
    }

    /**
     * The canonical key over the selection-scope assignments — null when
     * the selection set is empty (the legacy free-form variant's own
     * answer; the unique index ignores null keys by its own partial
     * predicate).
     *
     * @param selectionAssignments the schema's VARIANT_SELECTION-scope
     *                             assignments (the caller already fixed
     *                             the post-move scope)
     * @param valuesByAssignment   the variant's typed values keyed by the
     *                             assignment id; an assignment the values
     *                             do not cover is SKIPPED here exactly as
     *                             the registration path skips it (the
     *                             registration's own coverage gate and the
     *                             move guard's coverage verdict own the
     *                             completeness law — this derivation only
     *                             hashes)
     */
    static String canonical(List<ProductTypeAttributeAssignment> selectionAssignments,
                            Map<UUID, CatalogAttributeValueInput> valuesByAssignment) {
        if (selectionAssignments.isEmpty()) {
            return null;
        }
        String canonical = selectionAssignments.stream()
                .filter(a -> valuesByAssignment.containsKey(a.getId()))
                .sorted(Comparator.comparing(a -> a.getId().toString()))
                .map(a -> a.getId() + "=" + valuesByAssignment.get(a.getId()).optionId())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 implementation is required", impossible);
        }
    }
}
