package com.marketplace.institutions;

import org.springframework.modulith.PackageInfo;

/**
 * B-13 (compliance plan C.3 — the owner's 2026-10-07 ruling, recorded
 * verbatim: «المؤسسات فيها جزء من المجتمع»): the institution-specific
 * EDGES — the registry (سجل الجهة), the schema.org JSON-LD block, and
 * the institutional verification — on the reviews-pattern self-contained
 * module shape. The MEMBERSHIP machinery itself stays in community (the
 * ruling's own division: the membership generalization rides the
 * existing {@code NeighborhoodMembership} machine INSIDE community via
 * the CR protocol, never duplicated here — «بلا تكرار أي آلية عضوية
 * خارج community»).
 */
@PackageInfo
public final class InstitutionsModule {
    private InstitutionsModule() {
    }
}
