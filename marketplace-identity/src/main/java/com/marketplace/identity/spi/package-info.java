/**
 * The identity module's SPI surface — the named interface the application
 * module's {@code requires "identity :: identity-spi"} declares.
 *
 * <p>The wave-4 review (Major · Stability & Availability): the candidate
 * declared the {@code "identity"} named interface for the module's root
 * package only, while the application's GraphQL layer imports these SPI
 * types ({@code AccountLifecycleSpi}, {@code IdentitySpi}, the address
 * family) from this subpackage and its module descriptor requires
 * {@code "identity :: identity-spi"} — a Modulith verification failure
 * (the subpackage belonged to the module's open interior, not to any
 * declared named interface). This file closes that gap: the subpackage is
 * the explicitly published {@code identity-spi} named interface, and the
 * root package's own {@code @NamedInterface("identity")} stays the
 * separately-published surface it already was.</p>
 */
@org.springframework.modulith.NamedInterface("identity-spi")
package com.marketplace.identity.spi;
