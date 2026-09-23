# Feature Flags Convention (env-based, no vendor)

**Status:** adopted. **Basis:** Spring Boot 4.1.1 Reference, "Property
Conditions" (`@ConditionalOnProperty`: "lets configuration be included
based on a Spring Environment property... By default, any property that
exists and is not equal to `false` is matched"; `havingValue` /
`matchIfMissing` for advanced checks) + `@ConditionalOnBooleanProperty`
for boolean properties.

## Naming

- Property: `marketplace.feature.<name>.enabled`
- Env (Railway/CI/local): `MARKETPLACE_FEATURE_<NAME>_ENABLED=true|false`
- One flag per independently releasable behavior; flag names are
  kebab-case capabilities (`notifications-v2`), never ticket numbers.

## Semantics (binding)

- **Default OFF.** Consumers use
  `@ConditionalOnProperty(prefix = "marketplace.feature.<name>",
  name = "enabled", havingValue = "true", matchIfMissing = false)` (or the
  boolean variant). Nothing new runs unless an environment opts in —
  gradual rollout and kill-switch both fall out of this default.
- Flags gate BEHAVIOR (beans, listeners, schedulers, routes), never
  schema: no Flyway migration may be conditional on a flag.
- A flag without a consumer is dead code: introduce the flag in the same
  PR that consumes it. Retire the flag (and its dead branches) in the
  cleanup PR once rollout reaches 100% — record retirement in the PR body.

## What this is NOT

- No percentage rollouts, no A/B bucketing, no remote targeting — those
  need a flag SERVICE (Unleash and friends), an explicit later decision
  with cost/ops analysis. This file must not grow a vendor SDK.
