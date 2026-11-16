package com.marketplace.discovery;

import org.springframework.modulith.PackageInfo;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20): the home discovery rails' module
 * marker — the execution-plan unified §1.4 functional vision ("صفوف
 * اكتشاف موضوعية فوق سجل موحد") as a self-contained module on the
 * institutions-pattern shape. The module is a DETERMINISTIC PROJECTION
 * ASSEMBLER: every rail card is re-derived from its source record through
 * the shared-api ports at read time (no content copy, no per-rail table),
 * the caller's scope is the active membership's neighborhood alone (never
 * a silent widening), the empty rail is omitted from the response entirely
 * (AC-20-07), and the one owned write — the {@code discovery_impressions}
 * ledger (V176) — is born-complete and never mutated (the V93
 * {@code provider_follow_alerts} discipline: the row IS its own audit
 * trail, so no Envers mirror and no mutable state machinery).
 *
 * <p>The ML/personalization step stays behind decision D-13 until a
 * measured baseline and an evaluation set exist: FOR_YOU is deterministic
 * and explainable (recency + declared scope, the reason always present),
 * never a learned ordering (AC-20-06).</p>
 */
@PackageInfo
public final class DiscoveryModule {
    private DiscoveryModule() {
    }
}
