@org.springframework.modulith.NamedInterface("pricing")
@org.springframework.modulith.ApplicationModule(
    // L26 (feature-expansion roadmap §5): shared-security joins the allow-list —
    // the calendar service needs CurrentUserProvider for the listing-ownership
    // check, the same triple the media module already declares (the strict
    // module that owns listing-scoped host writes). No other module gains a
    // dependency on pricing: booking consumes the L26 EffectivePricePort
    // through shared-api, never through this package.
        allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.pricing;
