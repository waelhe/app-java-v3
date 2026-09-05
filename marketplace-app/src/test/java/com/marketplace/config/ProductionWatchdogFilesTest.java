package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the production-watchdog layer (Layer 7): it pins the
 * synthetic-uptime-monitoring workflow - the only alerting channel the
 * system has until the OTEL/MAIL vendor gate is decided by the user
 * (SYSTEM.md §14.2). Same class of latent "config that lies" defect as
 * {@code PlatformGovernanceFilesTest} and {@code ObservationCoverageFilesTest}:
 * nothing at runtime rejects a silently weakened watchdog - a widened cron,
 * a dropped 401-boundary probe, or a lost issue permission just means the
 * next real outage pages nobody - only a pinned test keeps the channel
 * honest.
 *
 * <p>What each pin guards:
 * <ul>
 *   <li><b>schedule cadence</b> - {@code "7,22,37,52 * * * *"}: 15-minute
 *       probes at a phase offset. The official events doc (cached:
 *       {@code scripts/doc-verify/actions-schedule-events.html}) says
 *       "High load times include the start of every hour. To decrease the
 *       chance of delay, schedule your workflow to run at a different time
 *       of the hour" - which is exactly why the cron never lands on an
 *       hour or quarter boundary. A regression to a plain aligned 15-minute
 *       cron (0/15) would silently re-align every probe with the worst
 *       delay window.</li>
 *   <li><b>probe contract</b> - the five measured public endpoints with
 *       their expected codes (liveness/readiness/jwks/OIDC discovery =
 *       200; the modulith actuator endpoint = 401). The 401 pin is the one
 *       that keeps the security boundary observable: 404 means the
 *       exposure list drifted, 200 means the boundary is gone - both are
 *       incidents the watchdog must catch. The full health endpoint
 *       probe is pinned <b>absent</b>: it 503s on the documented MAIL_*
 *       provider placeholder (SYSTEM.md section 15, debt item 3 - a
 *       user-owned gate), so probing it would be a permanent false alarm.</li>
 *   <li><b>anti-flap retries</b> - three attempts with backoff before
 *       declaring an incident, so a zero-downtime redeploy blip does not
 *       page anyone.</li>
 *   <li><b>least privilege</b> - the workflow needs exactly
 *       {@code issues: write} (official syntax doc: "issues: write permits
 *       an action to add a comment to an issue") and nothing more: no
 *       checkout, no contents, no pull-requests scope.</li>
 *   <li><b>incident lifecycle</b> - deduplicated by the
 *       {@code uptime-watchdog} label (one open incident at a time),
 *       assigned to the repository owner (that is the notification: an
 *       assigned issue), and auto-closed with a recovery comment by the
 *       next healthy run.</li>
 *   <li><b>self-test channel</b> - the {@code force_incident} dispatch
 *       input proves the alerting path end-to-end without waiting for a
 *       real outage; the next healthy run closes the self-test issue.</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class ProductionWatchdogFilesTest {

    @Test
    void watchdogScheduleFollowsTheOfficialDelayAvoidanceContract() throws IOException {
        String yml = read(".github/workflows/watchdog.yml");
        // Scheduled workflows only run on the default branch (official doc),
        // so this file on main IS the live monitor.
        assertThat(yml).as("the workflow must declare the Production Watchdog name")
                .contains("name: Production Watchdog");
        assertThat(yml).as("15-minute cadence, phase-offset off the hour/quarter "
                        + "boundaries (official: 'High load times include the start of "
                        + "every hour') - a plain aligned cron re-aligns every probe "
                        + "with the worst delay window")
                .contains("cron: \"7,22,37,52 * * * *\"");
        assertThat(yml).as("manual dispatch keeps the monitor runnable on demand")
                .contains("workflow_dispatch:");
        assertThat(yml).as("the self-test input is what proves the alerting channel "
                        + "without a real outage")
                .contains("force_incident");
    }

    @Test
    void watchdogProbesExactlyTheFiveVerifiedPublicEndpoints() throws IOException {
        String yml = read(".github/workflows/watchdog.yml");
        assertThat(yml).as("BASE_URL must pin the live production channel "
                        + "(SYSTEM.md §15)")
                .contains("BASE_URL: https://app-java-v3-production-d020.up.railway.app");
        assertThat(yml).as("liveness probe").contains("[\"/actuator/health/liveness\"]=\"200\"");
        assertThat(yml).as("readiness probe").contains("[\"/actuator/health/readiness\"]=\"200\"");
        assertThat(yml).as("auth keys probe").contains("[\"/oauth2/jwks\"]=\"200\"");
        assertThat(yml).as("OIDC discovery probe")
                .contains("[\"/.well-known/openid-configuration\"]=\"200\"");
        assertThat(yml).as("the 401 boundary probe keeps the security perimeter "
                        + "observable: 404 = exposure drifted, 200 = boundary gone")
                .contains("[\"/actuator/modulith\"]=\"401\"");
        assertThat(yml).as("full health is deliberately NOT a probe: it 503s on the "
                        + "documented MAIL_* provider gate - probing it would be a "
                        + "permanent false alarm")
                .doesNotContain("[\"/actuator/health\"]=\"");
        assertThat(yml).as("bounded probe: curl must always carry --max-time so a "
                        + "hung endpoint cannot wedge the job")
                .contains("--max-time 30");
        assertThat(yml).as("anti-flap: three attempts before declaring an incident "
                        + "(a zero-downtime redeploy blip must not page anyone)")
                .contains("for attempt in 1 2 3");
    }

    @Test
    void watchdogIncidentLifecycleIsDeduplicatedAndLeastPrivilege() throws IOException {
        String yml = read(".github/workflows/watchdog.yml");
        assertThat(yml).as("least privilege: issue creation/commenting is the only "
                        + "permission the workflow needs (official syntax doc: "
                        + "'issues: write permits an action to add a comment to an "
                        + "issue')")
                .contains("issues: write");
        assertThat(yml).as("a permissions block must exist at all - without it the "
                        + "token defaults would be whatever the repo settings say")
                .contains("permissions:");
        assertThat(yml).as("overlapping runs are pointless for a periodic prober - "
                        + "the concurrency group serializes them")
                .contains("group: production-watchdog");
        assertThat(yml).as("the dedup label - one open incident at a time")
                .contains("uptime-watchdog");
        assertThat(yml).as("dedup check: skip creation when an open incident exists")
                .contains("skipping issue creation (dedup)");
        assertThat(yml).as("assignment to the repository owner is the notification "
                        + "channel - an assigned issue reaches the user")
                .contains("--assignee \"$OWNER\"");
        assertThat(yml).as("recovery closes the incident with an audit comment")
                .contains("gh issue close");
        assertThat(yml).as("the recovery step must not fire on the force_incident "
                        + "self-test run itself, or it would close what it just opened")
                .contains("if: success() && inputs.force_incident != true");
    }

    private static String read(String... segments) throws IOException {
        Path cwd = Paths.get("").toAbsolutePath();
        Path repoRoot = cwd.resolve("..");
        if (!Files.exists(repoRoot.resolve(".github"))) {
            repoRoot = cwd; // fallback: tests launched from the repo root
        }
        Path file = repoRoot.resolve(String.join("/", segments));
        assertThat(file).as("%s must exist", file).exists();
        return Files.readString(file);
    }
}
