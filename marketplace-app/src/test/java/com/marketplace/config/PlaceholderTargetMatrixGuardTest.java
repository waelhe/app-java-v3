package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D10 — placeholder-vs-target matrix guard (closes gap G2; E-02's class).
 *
 * <p>Defect: E-02 — {@code ${OAUTH_POST_LOGOUT_REDIRECT_URI}} in
 * application-prod.yml resolved in CI but not on the deployment target:
 * "Could not resolve placeholder" → boot collapse → crash loop → 502 on
 * every path (merge #415, reverted by #419). The prevention stayed
 * procedural (rule B6's matrix note in the PR) because the target's
 * variable set had no versioned, testable form.
 *
 * <p>Invariant: every env-var-shaped {@code ${...}} placeholder WITHOUT a
 * default in the deployed composition (base + prod profile, both services)
 * is declared in the versioned deployment-target record —
 * {@code .railway/railway.ts}, whose env block is the repo's declared record
 * of the live service set ({@code preserve()} semantics: values stay in
 * Railway's secret store, never in Git).
 *
 * <p>Honestly deterministic, honestly narrow: the guard verifies repo-side
 * consistency — it cannot see the live service (the provider API is out of
 * CI scope); the record's own fidelity rides the documented IaC discipline
 * plus B6's PR-time matrix note. Placeholders WITH a default resolve
 * unconditionally and are exempt (fail-open by design). Property references
 * (dotted or non-UPPER names, e.g. {@code spring.application.name}) resolve
 * from the property sources, not the environment, and are excluded.
 * Comment lines are skipped — documentation may quote placeholder syntax
 * freely. A no-default line that a profile overrides with a defaulted
 * binding is over-required (never under-required): the dead line is its
 * own smell, and the failure message names the remedy. The staging service
 * (app-java-v3-staging) has no versioned record in the partial export —
 * its matrix stays procedural, a documented limitation, not a concealed
 * gap.
 *
 * <p>First run (2026-10-28) measured and closed three record gaps —
 * ADMIN_SEED_PASSWORD (live since PR #389's pre-merge operational step),
 * OAUTH_POST_LOGOUT_REDIRECT_URI (live since PR #420's pre-merge set; v5
 * domain per the deployment reference's §0.8), MAIL_FROM (live since PR
 * #685's single {@code variableUpsert} with {@code skipDeploys=true} —
 * measured 67 variables resolved) — declared in the same commit.
 */
class PlaceholderTargetMatrixGuardTest {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
    private static final Pattern ENV_VAR_NAME =
            Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern DECLARED_VAR =
            Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*:\\s*(?:preserve\\(\\)|\"[^\"]*\")");

    private static final String IAC = ".railway/railway.ts";

    @Test
    void appDeployedCompositionPlaceholdersAreDeclaredOnTheVersionedTarget() throws IOException {
        Set<String> required = new LinkedHashSet<>();
        collectNoDefaultEnvPlaceholders("marketplace-app/src/main/resources/application.yml", required);
        collectNoDefaultEnvPlaceholders("marketplace-app/src/main/resources/application-prod.yml", required);
        // The opt-in typesafe profile is declared and reachable: its no-default
        // key must be declared too (provisioning stays the owner's documented
        // action per the IaC file's own comment).
        collectNoDefaultEnvPlaceholders("marketplace-app/src/main/resources/application-typesafe.yml", required);

        Set<String> declared = declaredEnvNames("app-java-v3");

        assertThat(declared)
                .as("G2 / D10: every no-default env placeholder in the deployed composition must be "
                        + "declared in .railway/railway.ts (the versioned target record). An undeclared "
                        + "name is E-02 armed: CI stays green while the deployment target lacks the "
                        + "value, and the next boot collapses on 'Could not resolve placeholder'. "
                        + "Declare the name in the service's env block with preserve() — preserve() "
                        + "keeps an existing value and never creates one, so set the variable's "
                        + "value on the Railway service BEFORE the next deploy — or give the "
                        + "placeholder an explicit default when fail-fast is not the intent.")
                .containsAll(required);
    }

    @Test
    void edgeDeployedCompositionPlaceholdersAreDeclaredOnTheVersionedTarget() throws IOException {
        Set<String> required = new LinkedHashSet<>();
        collectNoDefaultEnvPlaceholders("marketplace-edge/src/main/resources/application.yml", required);
        collectNoDefaultEnvPlaceholders("marketplace-edge/src/main/resources/application-prod.yml", required);

        Set<String> declared = declaredEnvNames("app-java-v3-edge");

        assertThat(declared)
                .as("G2 / D10: the edge service matrix — the same invariant as the app service.")
                .containsAll(required);
    }

    // ---------------------------------------------------------------------

    private static void collectNoDefaultEnvPlaceholders(String resourcePath, Set<String> into)
            throws IOException {
        List<String> lines = Files.readAllLines(repoRoot().resolve(resourcePath));
        for (String line : lines) {
            if (line.strip().startsWith("#")) {
                continue; // documentation may quote placeholder syntax freely
            }
            Matcher m = PLACEHOLDER.matcher(line);
            while (m.find()) {
                String name = m.group(1).strip();
                if (m.group(2) == null && ENV_VAR_NAME.matcher(name).matches()) {
                    into.add(name);
                }
            }
        }
    }

    private static Set<String> declaredEnvNames(String serviceName) throws IOException {
        String iac = Files.readString(repoRoot().resolve(IAC));
        String marker = "service(\"" + serviceName + "\",";
        int start = iac.indexOf(marker);
        assertThat(start)
                .as("service %s must be declared in %s", serviceName, IAC)
                .isGreaterThanOrEqualTo(0);
        int nextService = iac.indexOf("service(", start + marker.length());
        int project = iac.indexOf("project(", start + marker.length());
        int end;
        if (nextService < 0 && project < 0) {
            end = iac.length();
        } else if (nextService < 0) {
            end = project;
        } else if (project < 0) {
            end = nextService;
        } else {
            end = Math.min(nextService, project);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String line : iac.substring(start, end).split("\n", -1)) {
            Matcher m = DECLARED_VAR.matcher(line);
            if (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.resolve("..");
        return Files.exists(root.resolve(".github")) ? root : cwd;
    }
}
