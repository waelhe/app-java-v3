package com.marketplace.shared.api;

/**
 * The report-resolution verdict's cross-module vocabulary — the
 * operator's two moves on an OPEN community report (the
 * {@code DisputeResolution} precedent: the boundary vocabulary lives in
 * shared-api, the owning module maps it onto its own state machine).
 */
public enum ReportResolution {

    /**
     * Closes the report as-is — the operator's explicit no-action: the
     * reported content stays visible, the report leaves the queue.
     */
    DISMISS,

    /**
     * The atomic resolution: hides the reported content, alerts the
     * author after commit, and closes the report RESOLVED — the
     * operator's note recorded with the action.
     */
    HIDE_CONTENT
}
