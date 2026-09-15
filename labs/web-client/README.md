# labs/web-client — P2 Web BFF Live Verification Station (isolated)

> **Purpose:** the P2 station record — how the Next.js + BFF web client was scaffolded beside the backend repo on latest stable versions, wired to the local backend via Better Auth Generic OAuth (stateless, official email-less bridge), and proven live end-to-end (10-step login/read flow against `:3000`).
> **Owners:** branch-attached session record (this branch); `PROJECT_MAP.md` keeps only a pointer to this family.
> **Load order:** `p2-web-bff-runbook.md` (this family's single runbook — §0-§7 reproducible procedure + §8 session record 2026-09-15 with measured values: Node `v26.8.2`/Next `16.3.5`/React `19.3.0`/Better Auth `1.7.5`, framework callback redirect, `.invalid` placeholder bridge, proxy `/me` 200).
> **Sources:** `docs/security/client-hosting-strategy-plan.md` (governing doc, pattern 1 + gate B), `labs/platform/03-web.md` + `labs/platform/08-build-roadmap.md` (P2), `labs/oauth2-verification/` (prior station), live reads from `oauth2_registered_client`.
> **Isolation:** `labs/` only; the web project itself lives outside this repo (`backend java\web-marketplace\`); never touches `docs/governance/`.
