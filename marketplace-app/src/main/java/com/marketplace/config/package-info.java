/**
 * A-02 (compliance plan 0.1) — the architecture gate now covers every
 * module in the model. The measured inventory: fifteen modules closed via
 * their base {@code package-info.java} and five more via the {@code
 * *Module.java} type-level declarations (the official alternative the
 * framework documents), leaving exactly two open — this module and the
 * shared foundation. This file closes the first: {@code OpenApiConfig}
 * and {@code ProviderStatsCacheConfig} import nothing from any
 * application module and expose no cross-module bean reference
 * (Documenter-measured), so the gate pins the module to exactly that
 * zero.
 *
 * <p>Official semantics, verified against the annotation's own bytecode:
 * an explicitly empty {@code allowedDependencies} array is
 * CLOSED-with-zero authorized dependencies — the attribute's default
 * value is the open token, not the empty array
 * (spring-modulith/reference/fundamentals.html — "Explicit Application
 * Module Dependencies").
 */
@org.springframework.modulith.NamedInterface("config")
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package com.marketplace.config;
