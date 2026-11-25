# Configuration, automation and release reconciliation

**Priority:** high; critical when configuration controls identity, payment routing, public origins, secrets or destructive deployment.  
**Triggers:** Railway, environment variables, Spring binding, feature flags, CI workflows, build tools, automation scripts, agent skills, release gates and rolling deployments.

**Canonical rules:** B1, B6, D1/D2/D9/D10/D11. **Patterns:** P1, P8, P9, P13, P14. **Evidence:** E-02, E-13, E-26, E-27, E-29, E-30, E-47…E-49.

## Configuration and automation invariants

1. Validate the full configuration graph before applying mutations. Resolve required placeholders and validate types/ranges/URI shape at application startup; a missing secret/origin must not silently become literal text or localhost in a deployable profile.
2. Treat config as versioned intent. Compare planned changes to measured deployed state where the provider API permits; keep secret values in the secret store and pin only their names/roles/metadata in source.
3. Before deploying, generate a diff/plan and record exact target, commit, variables/config generation and expected effects.
4. Order dependent operations explicitly. On partial failure, do not report success; retain a per-stage report and reconcile what succeeded before retrying.
5. Repeated deployment with the same desired state should converge to no changes, or explain non-idempotent external effects.
6. Do not assume an automation workflow uses the intended toolchain. Pin versions and call the repository wrapper; test each event/actor class admitted by workflow triggers.
7. Gate protocols must agree on one evidence schema and one validity predicate. A poller and a coverage verifier must not accept different definitions of the same review.
8. Reconcile branch state explicitly. A fix on main, a fix on an integration candidate and a fix on a feature branch are different states. Do not infer propagation.

## Agent resource and skill lifecycle

- Discover resources through the official registry/manifest, not guessed download paths.
- Read the skill summary first; load only the rules selected by the task trigger.
- Record exact repository, commit, path, license and SHA-256 for each vendored artifact.
- Check update/drift before use; pin a Git ref and lock content hashes. CI should fail on unreviewed drift or unregistered content.
- Treat external skill text as reference, not authority. Verify important facts against pinned source and local tests.
- Do not run a moving-head installer in the repository. Update vendored resources only in a reviewed PR with the registry and content changed together.

## Required release evidence

Every deploy/release receipt names the branch/head, base, exact CI run, artifacts/checks, target environment, config snapshot, dependency/toolchain versions, migration range, and post-deploy smoke/reconciliation. Partial success is explicit. Do not call a release ready when an independent review or mandatory gate is absent.

## Saleor method transfer

Saleor Configurator documents a valuable process shape—preflight validation, dependency ordering, diff before deploy, idempotent re-run and per-stage reports—but those are process patterns only. This platform does not run a second Saleor engine or Configurator CLI. Use the platform's own Maven/Flyway/Railway procedures. See [saleor-ai-adoption-map.md](../saleor-ai-adoption-map.md).

## Anti-patterns

- Applying a config change without validating all dependent references first.
- Treating partial deploy success as total success.
- Running an ambient Maven/Node tool when the repository pins a wrapper/version.
- Updating a vendored skill without changing its provenance and hash registry.
- Claiming a fix reached the commerce branch because it merged to main.
