package com.marketplace.shared.api;

/**
 * The trust vocabulary — §6.5's «الثقة الأربع» (جوهر الهوية — من المرفق),
 * the four SEPARATE trust facts the plan names, each with its own
 * evidence, lifecycle and authority. The values carry the section's
 * literal semantics, verbatim:
 *
 * <ol>
 *   <li>{@link #VERIFIED_LOCAL_MEMBER} — «عضو موثق محليًا — أدلة ارتباط
 *       بالحي/المنطقة»;</li>
 *   <li>{@link #VERIFIED_BUSINESS} — «جهة/مزود موثق — تحقق هوية أو بيانات
 *       نشاط بحسب نوع الجهة»;</li>
 *   <li>{@link #COMMUNITY_ENDORSEMENT} — «توصية مجتمعية — تجربة/تزكية
 *       منسوبة لشخص حقيقي»;</li>
 *   <li>{@link #VERIFIED_SOURCE} — «محتوى موثوق المصدر — معلومة من جهة
 *       رسمية أو مصدر قابل للتحقق».</li>
 * </ol>
 *
 * <p><b>Deliberately NOT one mega boolean (§4.5's own prohibition —
 * «موثق» بلا تفسير boolean واحد ممنوع):</b> توثيق شخص ≠ توثيق إقامة ≠
 * توثيق ملكية عمل ≠ صلاحية ناشر رسمي — كلٌّ واقعة مختلفة بصلاحيات
 * مختلفة. The section's no-conflation rules bind every consumer:
 * «لا خلط بينها: الإقامة لا تجعل كل معلومة صحيحة، وتوثيق النشاط لا يجعل
 * تقييمه ممتازًا».
 *
 * <p><b>The endorsement stays an ADDITIONAL signal, never a membership
 * condition (D-14):</b> «التزكية إشارة ثقة إضافية لا شرط عضوية إلزامي» —
 * the parallel path to verification with an optional social endorsement,
 * and «إثبات السكن منفصل عن المتابعة/الاشتراك»: a documented membership
 * is possible alongside following other neighborhoods and groups — not
 * every follow is this kind of membership.
 *
 * <p><b>Module ownership (§4.3):</b> the attestation RECORD lives with
 * the account (marketplace-identity — the {@code verification_attestations}
 * table, V184); the verification WORKFLOWS behind each type keep their
 * own owners (§6.2: official-entity verification is separate from a
 * commercial-account verification or a community membership; §4.3:
 * documented neighborhood membership → community+geo, business files →
 * provider). This shared-api enum is the cross-module contract vocabulary
 * (the {@code UserRoleChanged} String-vocabulary shape: the stored enum
 * NAME is what crosses boundaries).
 *
 * <p>Phase 1 (the unified plan §10): the vocabulary lands with the V184
 * table and its identity-owned state machine (PENDING → GRANTED/REJECTED,
 * GRANTED → REVOKED). Later phases consume it at the discovery envelope's
 * trust/provenance dimension (§4.5) — never conflated, never a single
 * flag.
 */
public enum TrustType {

    /** «عضو موثق محليًا» — neighborhood/area affiliation evidence (§6.5-1). */
    VERIFIED_LOCAL_MEMBER,

    /** «جهة/مزود موثق» — identity or activity-data verification by entity type (§6.5-2). */
    VERIFIED_BUSINESS,

    /** «توصية مجتمعية» — experience/endorsement attributed to a real person (§6.5-3, D-14). */
    COMMUNITY_ENDORSEMENT,

    /** «محتوى موثوق المصدر» — information from an official entity or verifiable source (§6.5-4). */
    VERIFIED_SOURCE
}
