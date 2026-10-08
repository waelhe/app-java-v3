package com.marketplace.jobs;

/**
 * B-12 (compliance plan C.2): the employment vocabulary — the five stored
 * values (the V153 DB CHECK pins the membership; a future sixth value
 * widens by migration, not by rebuilding this type — the plan's §4.2
 * explicit decision pattern for stored vocabularies).
 */
public enum EmploymentType {

    /** دوام كامل */
    FULL_TIME,
    /** دوام جزئي */
    PART_TIME,
    /** عقد مؤقت */
    CONTRACT,
    /** تدريب */
    INTERNSHIP,
    /** تطوع */
    VOLUNTEER
}
