package com.marketplace.knowledge;

/**
 * B-14 (compliance plan C.4 — the guide's own vocabulary, the «تعرف على»
 * row's five axes): what a knowledge entry documents about the
 * neighborhood and its residents. The V156 DB CHECK pins the
 * membership; a future sixth value widens by migration, not by
 * rebuilding this type.
 */
public enum KnowledgeCategory {

    /** أماكن الحي — مساجده ومدارسه ومتاحفه وحدائقه ومَعالمه */
    PLACES,
    /** خدمات الحي — ما يحتاجه السكان في يومهم */
    SERVICES,
    /** تاريخ الحي وأصله وقصصه */
    HISTORY,
    /** أهالي الحي — من يبنون المجتمع ويسندونه */
    PEOPLE,
    /** نصائح الحياة فيه — تجارب السكان المتراكمة */
    TIPS
}
