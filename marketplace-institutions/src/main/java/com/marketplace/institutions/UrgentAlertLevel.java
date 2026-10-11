package com.marketplace.institutions;

/**
 * D-3/D-4 (the delegated urgent alert — CMP-46): the alert's urgency
 * vocabulary — the three stored values (the V178 DB CHECK pins the
 * membership; a future value widens by migration, not by rebuilding this
 * type — the plan's §4.2 explicit decision pattern for stored
 * vocabularies).
 *
 * <p><b>CMP-46's own rule: مستوى الاستعجال نص لا إشارة شعبية</b> — the
 * level is RENDERED as text on every surface (the port's card carries it
 * as a String verbatim); it is never a ranking weight, never a community
 * vote axis, never an AI label. A CRITICAL alert outranks nothing by
 * itself — it is one delegated source's declared urgency, displayed
 * honestly, ordered only by its own validity window (the freshest
 * first).</p>
 */
public enum UrgentAlertLevel {

    /** خطر وشيك — أعلى درجات الاستعجال المعلنة من المصدر المفوض. */
    CRITICAL,

    /** خطر جسيم — إجراء موصى به من المصدر المفوض. */
    SEVERE,

    /** تنبيه استرشادي — معلومة رسمية تستحق الانتباه. */
    ADVISORY
}
