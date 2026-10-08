package com.marketplace.jobs;

/**
 * B-12 (compliance plan C.2): the workplace vocabulary — the three stored
 * values (the V153 DB CHECK pins the membership, the same widening-by-
 * migration discipline as {@link EmploymentType}).
 */
public enum WorkplaceType {

    /** في الموقع */
    ONSITE,
    /** عن بُعد */
    REMOTE,
    /** هجين */
    HYBRID
}
