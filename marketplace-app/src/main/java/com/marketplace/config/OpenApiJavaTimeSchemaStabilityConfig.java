package com.marketplace.config;

import io.swagger.v3.core.util.PrimitiveType;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the generated-contract rendering of {@code java.time.LocalTime},
 * {@code java.time.OffsetTime} and {@code java.time.Duration} to the bare
 * {@code string} schema — the exact rendering every published baseline of
 * this API has ever carried and the rendering springdoc 3.1.0 itself held.
 *
 * <p><strong>Why this exists (measured 2026-10-24, PR #722 CI):</strong>
 * springdoc-openapi 3.1.1 change #3351 stopped forcing these three types to a
 * bare string and let swagger-core 2.2.55 resolve them natively, which stamps
 * a {@code format} on them ({@code time-local} for LocalTime, {@code time}
 * for OffsetTime, {@code duration} for Duration — the EXTERNAL_CLASSES
 * registration measured in swagger-core 2.2.55's PrimitiveType.java;
 * springdoc-openapi CHANGELOG
 * {@code [3.1.1] - 2026-09-06}, PR springdoc/springdoc-openapi#3351: the diff
 * removed {@code customClasses().put("java.time.Duration"/"java.time.LocalTime"/
 * "java.time.OffsetTime", PrimitiveType.STRING)} from
 * {@code SpringDocUtils.initExtraSchemas()}). Against the v0.1.0 baseline the
 * official openapi-diff 2.1.7 report classified those format stamps as
 * incompatible parameter and response-schema changes on
 * {@code POST /api/v1/providers/{providerId}/availability/rules} — the exact
 * failure that turned the phase-2b gate red while every other job stayed
 * green (the same tree under springdoc 3.1.0 produces a clean diff).
 *
 * <p><strong>The mechanism (official, surgical):</strong>
 * {@link PrimitiveType#customClasses()} is swagger-core's public override map,
 * consulted by {@code PrimitiveType.fromType} <em>before</em> the built-in
 * {@code EXTERNAL_CLASSES} registry that carries 3.1.1's new time mappings —
 * the identical mechanism and the identical three entries springdoc 3.1.0
 * used before #3351 removed them. Registering them here restores the 3.1.0
 * resolution byte-for-byte: {@code STRING.createProperty()} is a bare
 * {@code StringSchema} and {@code STRING.createProperty31()} a bare string
 * {@code JsonSchema} — no format in either OpenAPI mode.
 *
 * <p><strong>Governance (version-policy §6 discipline for this deliberate
 * deviation from the 3.1.1 default):</strong></p>
 * <ul>
 *   <li><strong>Reason:</strong> the 3.1.1 upgrade is a security patch
 *       (CVE-2026-75838 + seven GHSAs — five MCP ones made opt-in by
 *       springdoc.ai.mcp.enabled defaulting to false, two non-MCP ones
 *       fixed by the release itself) — a security line move must not
 *       silently evolve the published API contract; separating the two
 *       concerns keeps the contract frozen while the security fixes land.</li>
 *   <li><strong>Compatibility evidence:</strong> swagger-core 2.2.55 sources
 *       ({@code PrimitiveType.fromType} lookup order) and the green OpenAPI
 *       gate of the 3.1.0 tree against the same v0.1.0 baseline (measured
 *       2026-10-24, PR #714 run 40056156849).</li>
 *   <li><strong>Owner:</strong> platform maintainers.</li>
 *   <li><strong>Follow-up review:</strong> the next release re-baseline —
 *       adopting the richer {@code time-local}/{@code time}/{@code
 *       duration} formats is
 *       a deliberate contract-evolution decision that must ride a documented
 *       baseline-tag cut (the allowlist lifecycle), not a dependency bump.</li>
 * </ul>
 */
@Configuration
public class OpenApiJavaTimeSchemaStabilityConfig {

    /**
     * Installs the three contract pins. Idempotent map puts; runs at bean
     * initialization, safely before springdoc's first (lazy) schema
     * resolution at {@code GET /v3/api-docs}.
     */
    @PostConstruct
    void restoreSpringDoc310JavaTimeRendering() {
        PrimitiveType.customClasses().put("java.time.LocalTime", PrimitiveType.STRING);
        PrimitiveType.customClasses().put("java.time.OffsetTime", PrimitiveType.STRING);
        PrimitiveType.customClasses().put("java.time.Duration", PrimitiveType.STRING);
    }
}
