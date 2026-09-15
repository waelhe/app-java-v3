package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — criterion 7): the search module's
 * contribution to the account export (the standing {@code *ExportPort}
 * house pattern, R3 — the identity module's aggregation sees only this
 * shared-api type). The export includes the subject's soft-deleted saved
 * searches too: a deleted saved search is still the subject's stored
 * criteria until the retention window closes (b-5's discrimination —
 * deletion at the surface is a visibility flag, not an erasure).
 */
public interface SavedSearchExportPort {

    List<SavedSearchExportEntry> exportForOwner(UUID userId);
}
