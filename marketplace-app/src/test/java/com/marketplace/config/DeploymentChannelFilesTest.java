package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the deployment-channel layer (Layer 15): it pins the two
 * halves of the automated delivery channel — the retired fork-sync half
 * (fork-sync.yml; disabled_manually 2026-09-19 with the old account's
 * decommission — the file stays as the retirement record and its pins keep
 * its documented shape honest in case it is ever re-enabled) and the live
 * watchdog half guarding the direct channel's freshness and its alerting
 * home (watchdog.yml). Same class of latent "config that lies" defect as
 * {@code ProductionWatchdogFilesTest} and {@code PlatformGovernanceFilesTest}:
 * nothing at runtime rejects a silently weakened channel — a dropped
 * repository guard, a widened lag limit, or a lost write permission just
 * means the last manual step of the delivery lifecycle quietly returns
 * (or production quietly freezes on stale code) — only a pinned test keeps
 * the channel honest.
 *
 * <p>What each pin guards:
 * <ul>
 *   <li><b>the official sync mechanism</b> — POST
 *       {@code /repos/{owner}/{repo}/merge-upstream} with the fork's own
 *       GITHUB_TOKEN. Official evidence (cached: scripts/l15-docs/
 *       branches-api.html): the endpoint "works with … GitHub App
 *       installation access tokens" (the GITHUB_TOKEN is one) and requires
 *       exactly '"Contents" repository permissions (write)' — which is
 *       precisely the workflow's declared permission. Any other mechanism
 *       (a PAT secret, a cross-repo push) would reintroduce a user-owned
 *       credential — the exact gate this layer exists to avoid.</li>
 *   <li><b>where the sync runs</b> — only in the deployment fork
 *       (waelhe88-coder/app-java-v3): upstream main is the source of truth
 *       and must never be fast-forwarded from anything. The file is
 *       mirrored into both repositories; each runs only its own half.</li>
 *   <li><b>success contract</b> — HTTP 200 is the only documented success
 *       code ("The branch has been successfully synced with the upstream
 *       repository"); 409/422/anything else must fail loudly (a red
 *       scheduled run on the fork is the failure signal — the fork has
 *       issues disabled, measured).</li>
 *   <li><b>no recursion side-effect</b> — the official events doc
 *       (scripts/l15-docs/events.html): "With the exception of
 *       workflow_dispatch and repository_dispatch, other GITHUB_TOKEN-
 *       triggered events do not create workflow runs at all" — the sync's
 *       push must not re-run the fork's CI; the pin keeps the workflow free
 *       of any PR/push trigger that would change that contract.</li>
 *   <li><b>alerting-home guard</b> — measured live 2026-09-06: the fork has
 *       issues disabled (has_issues=false) and every healthy scheduled
 *       watchdog run there failed in "Close resolved incident" ("the
 *       repository has disabled issues"). Incident management must stay
 *       confined to upstream (waelhe/app-java-v3) — dropping the guard
 *       reintroduces a permanently red deployment channel.</li>
 *   <li><b>the workflow-file platform gate</b> — measured live 2026-09-06
 *       (failed fork-sync run 34038982156): merge-upstream answers HTTP 422
 *       "refusing to allow a GitHub App to create or update workflow
 *       ... without `workflows` permission" whenever the upstream diff
 *       touches a workflow file (first case: #242's maven-publish.yml
 *       CI gate). The GITHUB_TOKEN cannot be granted that permission: the
 *       workflow {@code permissions:} key's official scope list
 *       (workflow-syntax#permissions; the standard workflow schema agrees)
 *       has no {@code workflows} value. Generalized channel rule: all
 *       non-workflow diffs flow autonomously; a workflow-file diff is the
 *       bootstrap class — its only delivery path is a one-time manual
 *       Sync fork by the fork owner, keeping the channel itself
 *       credential-free. The blocked run must fail loudly WITH that
 *       explanation (a silent swallow or a red mystery both cost the
 *       same manual debugging this repo is being cleaned of).</li>
 *   <li><b>channel freshness alarm</b> (redesigned 2026-09-19 for the v3
 *       account migration): production deploys directly from main via
 *       Railway's GitHub App, which posts a commit status on the exact
 *       commit it builds (context "&lt;project&gt; - &lt;service&gt;", measured:
 *       success on bb6da39, failure on 7371afbb/16117d8 "Deployment
 *       failed"). A dead trigger or a failed build leaves production
 *       serving stale code with every HTTP probe green — so the watchdog
 *       reads that status on main HEAD: failure = immediate incident (a
 *       failed build never self-heals); pending/missing is bounded by the
 *       commit's age with the measured threshold (240m continuity limit —
 *       a push-triggered channel reports within minutes of the push).</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class DeploymentChannelFilesTest {

    @Test
    void syncWorkflowUsesTheOfficialMergeUpstreamChannelWithLeastPrivilege() throws IOException {
        String yml = read(".github/workflows/fork-sync.yml");
        assertThat(yml).as("the workflow must declare the Deployment Channel Sync name")
                .contains("name: Deployment Channel Sync");
        assertThat(yml).as("least privilege, exactly what merge-upstream requires "
                        + "(official: 'The fine-grained token must have the following "
                        + "permission set: \"Contents\" repository permissions (write)')")
                .contains("permissions:");
        assertThat(yml).as("the write scope the official endpoint requires")
                .contains("contents: write");
        assertThat(yml).as("the sync is driven by schedule (the PAT-less channel) "
                        + "and stays manually dispatchable for the bootstrap proof")
                .contains("schedule:")
                .contains("workflow_dispatch:");
        assertThat(yml).as("30-minute cadence at displaced minutes (official: 'High "
                        + "load times include the start of every hour') - an aligned "
                        + "cron re-enters the worst delay window")
                .contains("cron: \"11,41 * * * *\"");
        assertThat(yml).as("the official endpoint, not an invented mechanism "
                        + "(POST /repos/{owner}/{repo}/merge-upstream)")
                .contains("merge-upstream")
                .contains("-X POST");
        assertThat(yml).as("the fork's own token carries the call - no PAT, no "
                        + "secret, no user asset anywhere in the channel")
                .contains("GH_TOKEN: ${{ github.token }}");
        assertThat(yml).as("the synced branch is main (the branch Railway builds)")
                .contains("-d '{\"branch\":\"main\"}'");
        assertThat(yml).as("overlapping syncs are pointless - the concurrency group "
                        + "serializes them")
                .contains("group: deployment-channel-sync");
        assertThat(yml).as("a non-200 outcome is a failure (200 is the only "
                        + "documented success code) - a silent swallow would hide a "
                        + "dead channel behind green runs")
                .contains("if [ \"$http_code\" != \"200\" ]; then");
        assertThat(yml).as("curl must be bounded so a hung API call cannot wedge "
                        + "the job")
                .contains("curl -sS");
    }

    @Test
    void syncRunsOnlyInTheDeploymentForkAndNeverTriggersCascades() throws IOException {
        String yml = read(".github/workflows/fork-sync.yml");
        assertThat(yml).as("the sync only makes sense in the deployment fork - "
                        + "upstream main is the source of truth")
                .contains("if: github.repository == 'waelhe88-coder/app-java-v3'");
        assertThat(yml).as("the guard names the exact deployment channel repo "
                        + "(SYSTEM.md §15), the same class of pinned fact as the "
                        + "watchdog's production BASE_URL")
                .contains("waelhe88-coder/app-java-v3");
        assertThat(yml).as("the workflow must NOT declare push or pull_request "
                        + "triggers: per the official events doc, GITHUB_TOKEN-"
                        + "triggered events 'do not create workflow runs at all' - "
                        + "the sync's push must not re-run the fork's CI")
                .doesNotContain("pull_request:")
                .doesNotContain("\n  push:");
    }

    @Test
    void syncExplainsTheWorkflowFilePlatformGateWhenItBlocksTheChannel() throws IOException {
        String yml = read(".github/workflows/fork-sync.yml");
        assertThat(yml).as("the measured platform gate (2026-09-06, failed run "
                        + "34038982156): merge-upstream answers 422 'without "
                        + "`workflows` permission' on any workflow-file diff — "
                        + "the detection branch must stay")
                .contains("if [ \"$http_code\" = \"422\" ] && echo \"$message\" | grep -q 'without .workflows. permission'; then");
        assertThat(yml).as("the blocked run must state the delivery path, not "
                        + "fail as a red mystery: a one-time manual Sync fork "
                        + "by the fork owner carries workflow-file diffs (the "
                        + "bootstrap class); everything else keeps flowing "
                        + "autonomously")
                .contains("a one-time manual Sync fork by the fork owner")
                .contains("every other diff keeps flowing autonomously");
        assertThat(yml).as("the gate explanation must stay grounded in the "
                        + "official scope list — the workflow permissions key "
                        + "has no workflows value (workflow-syntax#permissions)")
                .contains("the permissions key has no workflows scope");
        assertThat(yml).as("the run must keep failing (red) while blocked: the "
                        + "channel is genuinely stuck until the manual sync "
                        + "lands — a success would hide a stale production "
                        + "behind green runs")
                .contains("This run stays red by design until the manual sync lands");
        assertThat(yml).as("the header must state the generalized channel rule "
                        + "(workflow-file diffs are the recurring bootstrap "
                        + "class) — the old 'stays current forever' claim was "
                        + "falsified by the measured gate")
                .contains("Workflow-file diffs are the recurring")
                .contains("bootstrap class");
    }

    @Test
    void watchdogConfinesIncidentManagementToTheAlertingHome() throws IOException {
        String yml = read(".github/workflows/watchdog.yml");
        assertThat(yml).as("measured 2026-09-06: the fork has issues disabled - "
                        + "incident steps must never run there (every healthy fork "
                        + "run failed with 'the repository has disabled issues')")
                .contains("if: github.repository == 'waelhe/app-java-v3'");
        assertThat(yml).as("the freshness probe reads main's branch state and "
                        + "its commit statuses - the read scope is explicit "
                        + "least privilege")
                .contains("contents: read");
        assertThat(yml).as("the watchdog still owns the alerting action")
                .contains("issues: write");
    }

    @Test
    void watchdogAlarmsWhenTheDeploymentChannelGoesStale() throws IOException {
        String yml = read(".github/workflows/watchdog.yml");
        assertThat(yml).as("the freshness probe step must exist between the HTTP "
                        + "probes and the incident steps")
                .contains("- name: Probe deployment channel freshness");
        assertThat(yml).as("the probe reads the governing repo's main")
                .contains("UPSTREAM: waelhe/app-java-v3");
        // 2026-09-19 channel redesign (PR #347): production deploys directly
        // from main via Railway's GitHub App; the retired fork compare would
        // measure a frozen repository forever (the fork-sync workflow is
        // disabled and the fork frozen at its last fast-forward). The live
        // channel's own telemetry is the commit status the App posts on the
        // exact commit it builds — context "<project> - <service>",
        // measured: success on bb6da39 ("Success - app-java-v3-production...")
        // and failure on 7371afbb/16117d8 ("Deployment failed").
        assertThat(yml).as("the probe reads Railway's own commit status on main "
                        + "HEAD - the live deployment channel's telemetry "
                        + "(measured context)")
                .contains("RAILWAY_STATUS_CONTEXT: \"app-java-v3 - app-java-v3\"");
        assertThat(yml).as("the retired fork compare must be gone - the fork "
                        + "channel was decommissioned with the old account")
                .doesNotContain("DEPLOY_FORK");
        assertThat(yml).as("the measured threshold stays bounded: a push-triggered "
                        + "channel reports within minutes, so an old HEAD without "
                        + "a success means the trigger is gone - a lower value "
                        + "false-alarms during a legitimate build, a higher value "
                        + "delays detection of a dead channel")
                .contains("MAX_LAG_MINUTES: 240");
        assertThat(yml).as("a FAILED deployment is an immediate incident - a "
                        + "failed build never self-heals, so production would "
                        + "keep serving stale code with every HTTP probe green")
                .contains("\"$rw_state\" = \"failure\"");
        assertThat(yml).as("the incident body must state the broadened contract: "
                        + "endpoints OR deployment channel")
                .contains("## Production unhealthy (endpoints or deployment channel)");
        assertThat(yml).as("a stale channel FAIL line must land in probe-results.txt "
                        + "- the incident body is built from that file")
                .contains("FAIL  deployment channel stale:");
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
