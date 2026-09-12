package com.marketplace.geo;

/**
 * Depth in the administrative hierarchy (realestate systems plan L30/D-R1 —
 * the plan's SMALLINT levels). Exactly one root level exists per tree; a
 * non-root node's parent must sit exactly one level above it (the
 * "hierarchical level skip is impossible" acceptance criterion — enforced
 * in the entity factory and mirrored by the V47 row-local CHECKs for what
 * a row can assert on its own).
 */
public enum GeoLevel {
    COUNTRY(0),
    GOVERNORATE(1),
    CITY(2),
    NEIGHBORHOOD(3);

    /** The plan's stored level (SMALLINT column value). */
    private final int level;

    GeoLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /** The level a child of this level must have. */
    public GeoLevel childLevel() {
        GeoLevel[] values = values();
        return values[Math.min(ordinal() + 1, values.length - 1)];
    }

    /** Resolves the stored level back to the enum (defensive on read). */
    public static GeoLevel of(int level) {
        for (GeoLevel value : values()) {
            if (value.level == level) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown geo level: " + level);
    }
}
