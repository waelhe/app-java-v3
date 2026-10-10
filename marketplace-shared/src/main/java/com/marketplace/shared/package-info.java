/**
 * A-02 (compliance plan 0.1) — the architecture gate now covers every
 * module in the model: fifteen modules were already closed via their base
 * {@code package-info.java}, five more via the {@code *Module.java}
 * type-level declarations (the official alternative the framework
 * documents), and this file closes the shared foundation itself — the
 * last open module next to the app-level {@code config} one.
 *
 * <p>Measured code facts: the module has zero outgoing imports and zero
 * cross-module bean references (Documenter-measured — the session
 * invalidator listens to shared-api's own events, framework types sit
 * outside the application modules), and every one of its five packages is
 * already an explicitly declared named interface ({@code shared-api},
 * {@code shared-config}, {@code shared-email}, {@code shared-jpa}, {@code
 * shared-security}). Closing the base therefore changes nothing for any
 * consumer: the same five channels remain the only windows into the
 * platform's foundation, each already authorized on the consumers' side
 * of the gate.
 *
 * <p>Official semantics, verified against the annotation's own bytecode:
 * an explicitly empty {@code allowedDependencies} array is
 * CLOSED-with-zero authorized dependencies — the attribute's default
 * value is the open token, not the empty array
 * (spring-modulith/reference/fundamentals.html — "Explicit Application
 * Module Dependencies").
 */
@org.springframework.modulith.NamedInterface("shared")
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package com.marketplace.shared;
