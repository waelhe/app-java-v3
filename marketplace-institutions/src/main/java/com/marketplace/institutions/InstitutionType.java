package com.marketplace.institutions;

/**
 * B-13 (compliance plan C.3): the institution vocabulary — the eight
 * stored values (the V154 DB CHECK pins the membership; a future ninth
 * value widens by migration, not by rebuilding this type — the plan's
 * §4.2 explicit decision pattern for stored vocabularies).
 */
public enum InstitutionType {

    /** مدرسة */
    SCHOOL,
    /** جامعة */
    UNIVERSITY,
    /** عيادة / مستشفى */
    CLINIC,
    /** مسجد */
    MOSQUE,
    /** جمعية خيرية */
    CHARITY,
    /** جهة حكومية */
    GOVERNMENT,
    /** شركة */
    COMPANY,
    /** منظمة أهلية */
    NGO
}
