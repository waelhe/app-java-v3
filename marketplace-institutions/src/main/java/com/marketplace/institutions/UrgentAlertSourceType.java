package com.marketplace.institutions;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): the delegating
 * body's vocabulary — the six stored values (the V178 DB CHECK pins the
 * membership; a future value widens by migration, not by rebuilding this
 * type — the plan's §4.2 explicit decision pattern for stored
 * vocabularies).
 *
 * <p>The type is the alert's honest ATTRIBUTION axis (it rides the
 * {@code UrgentAlertsPort.UrgentAlertCard.sourceType} verbatim): the
 * surface shows WHO declares the alert — the authority is the source's
 * own delegation, never the community's (AC-20-06).</p>
 */
public enum UrgentAlertSourceType {

    /** بلدية / أمانة */
    MUNICIPALITY,

    /** الدفاع المدني */
    CIVIL_DEFENSE,

    /** شركة المرافق (كهرباء، ماء، صرف صحي) */
    UTILITIES,

    /** سلطة صحية */
    HEALTH_AUTHORITY,

    /** سلطة تعليمية */
    EDUCATION_AUTHORITY,

    /** جهة مفوَّضة أخرى (موثقة بالاسم — التصنيف يصف، الاسم يحمل الهوية) */
    OTHER_DELEGATED
}
