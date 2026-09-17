/**
 * The neighborhood community layer (neighborhood community plan §5 — the
 * twentieth domain module, the media/realestate vertical-module pattern
 * verbatim: {@code allowedDependencies = shared ONLY} + plain UUID
 * references with no JPA relations across module boundaries).
 *
 * <p>The module owns the community domain's own state — the neighborhood
 * membership anchor (L41) first, then the posts/feed/comments (L42), the
 * moderation reports (L45) and the catalog event bridge (L46). Everything
 * it needs from the outside world arrives through the shared-api ports:
 * the geo tree through {@code GeoLookupPort} (D-N2 — the neighborhood IS
 * a level-3 geo node; no parallel geography, no second hierarchy), the
 * purge/export contracts through {@code AuthoredContentPurgePort} and the
 * community export port (the b-2/b-3 house seams), the caller identity
 * through {@code CurrentUserProvider}.
 *
 * <p><b>Deliberately absent:</b> a dependency on the geo, identity or
 * catalog modules. The plan's D-N1 decision — the same decoupling the
 * realestate module proved over L31-L37 — keeps the Reactor's module
 * graph flat; {@code ModulithVerificationTest} guards the boundary.
 */
@org.springframework.modulith.NamedInterface("community")
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "shared :: shared-api",
                "shared :: shared-security",
                "shared :: shared-jpa"
        }
)
package com.marketplace.community;
