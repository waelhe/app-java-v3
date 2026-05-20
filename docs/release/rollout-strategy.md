# Release Rollout Strategy

This document defines the operational protocol for releasing Marketplace backend changes safely.

## 1) Release gates (Go / No-Go)

A release is **Go** only when all conditions below are met:

- CI pipeline is green on target branch.
- Required tests for impacted modules pass.
- OpenAPI generation and validation checks pass.
- Database migration review is complete (if schema changed).
- Security review checklist is complete for authn/authz or sensitive data changes.
- Rollback procedure is prepared and owner is assigned.

A release is **No-Go** if any gate fails.

## 2) Environments and sequence

1. `dev` validation (smoke + targeted integration tests).
2. `staging` validation (full regression for impacted domains).
3. Production canary rollout.
4. Full production rollout after canary acceptance.

## 3) Canary policy

Start production rollout with a controlled subset of traffic:

- Initial canary slice: 5% traffic.
- Observation window: 15–30 minutes minimum.
- If stable, increase in steps: 5% -> 25% -> 50% -> 100%.

## 4) Canary acceptance criteria

Canary is accepted only if all are true:

- Error rates do not exceed baseline thresholds.
- No sustained increase in 5xx or 429 anomalies.
- Key business flows remain healthy (booking, payments, identity, catalog).
- No critical alert is open.

## 5) Rollback triggers

Rollback immediately when any of the following occurs:

- Sustained 5xx error increase above agreed threshold.
- Critical domain flow failure (payment capture, booking creation, auth failures).
- Data integrity issue detected.
- Security incident or severe access-control regression.

## 6) Rollback procedure

1. Stop rollout progression.
2. Route traffic back to previous stable version.
3. Confirm service recovery via health, metrics, and smoke checks.
4. Announce rollback in incident/release channel.
5. Create a post-rollback incident note with root-cause hypothesis.

## 7) Communication protocol

For each production release:

- Assign release owner and incident commander.
- Publish release start message (scope, risk, rollback owner).
- Publish canary checkpoints with current metrics.
- Publish final state: successful rollout or rollback.

## 8) Feature flags

For high-risk features:

- Prefer shipping behind feature flags.
- Enable progressively per tenant/segment where possible.
- Ensure emergency disable path is documented before rollout.

## 9) Post-release verification

After full rollout:

- Run smoke suite for critical APIs.
- Verify OpenAPI docs endpoint availability.
- Validate log/trace correlation for new code paths.
- Capture release notes including known follow-ups.
