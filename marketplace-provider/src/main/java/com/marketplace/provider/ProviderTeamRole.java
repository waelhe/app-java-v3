package com.marketplace.provider;

/**
 * W5b (the journeys register's J29 row — the provider team delegation): the
 * delegated authority's class. Two write-capable roles — the register's
 * «بصلاحيات» first cut:
 *
 * <ul>
 *   <li>{@code MANAGER} — the senior seat: every write the EDITOR holds on
 *       the declared surfaces, named for the branch-manager persona the
 *       register's «تفويض فريق/فرع» describes (a later wave may widen it —
 *       today the class is the name, not a wider key);</li>
 *   <li>{@code EDITOR} — the working seat: the profile and business-page
 *       declared surfaces (hours, services, areas).</li>
 * </ul>
 *
 * <p>Both roles sit ABOVE the EDITOR floor by construction —
 * {@code isActiveEditorOrAbove} accepts either — so a future read-only
 * {@code VIEWER} class can be added WITHOUT touching a single gate (the
 * vocabulary's own law, the V234 CHECK pins it at the database level
 * until that day).
 */
public enum ProviderTeamRole {
    MANAGER,
    EDITOR
}
