package com.marketplace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * S9 guard-readiness gate: the architectural boundary enforcement must never
 * vanish SILENTLY. {@link ModulithVerificationTest} and
 * {@link ModulithDocumentationTest} skip themselves on JDK 26+
 * ({@code assumeTrue}) because the ArchUnit pairing this build resolves
 * (archunit 1.4.2 — transitively pinned by spring-modulith-core 2.1.1, the
 * measured {@code dependency:tree} resolution) does not support JDK 26 class
 * files (major 70). A skipped test renders GREEN while the module boundaries
 * go entirely unenforced — SYSTEM.md §5 declares that enforcement a required
 * CI gate, so a runtime where the guard cannot run must FAIL the build with
 * an actionable message, not pass quietly.
 *
 * <p>On the current toolchain (JDK 25, pinned by CI and by the
 * {@code --release 25} bytecode contract) this gate passes and the
 * verification tests run. The day the toolchain moves to JDK 26+, this gate
 * turns the build RED until one of the two documented moves happens:
 *
 * <ul>
 *   <li>upgrade Spring Modulith (which re-pins the aligned ArchUnit pair)
 *       once its resolved ArchUnit officially supports JDK 26 class files —
 *       then delete this gate together with both {@code assumeTrue} skips;</li>
 *   <li>or keep the build toolchain at JDK 25.</li>
 * </ul>
 *
 * <p>Deliberately a plain runtime check, not bytecode sniffing: the analyzed
 * application bytecode is always major 69 ({@code --release 25}) regardless
 * of the runtime JDK — the JDK 26 exposure comes from JDK-owned classes the
 * importer touches, which is exactly what the pairing above does not
 * support yet.
 */
class ModulithGuardReadinessTest {

    @Test
    void architecturalGuardIsActiveOnThisRuntime() {
        int feature = Runtime.version().feature();
        assertTrue(feature < 26,
                "The Modulith/ArchUnit architectural guard is SKIPPED on JDK " + feature + ": "
                        + "module-boundary enforcement (a required CI gate) is not running. Either upgrade "
                        + "Spring Modulith so its resolved ArchUnit pair officially supports JDK 26 class "
                        + "files (then remove the assumeTrue skips in ModulithVerificationTest/"
                        + "ModulithDocumentationTest and this readiness gate together), or keep the build "
                        + "toolchain at JDK 25. The skip must never be silent.");
    }
}
