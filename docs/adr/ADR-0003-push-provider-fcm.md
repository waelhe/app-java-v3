# ADR-0003: the push provider — FCM HTTP v1 through Google's own Admin SDK (Plan D-10)

- **Status:** Accepted (owner-ordered execution of the unified plan, stage 7 — the reserved decision opened per the owner's standing order, decided on official documentation and the officially-supported client)
- **Decision owners:** Owner + ops (the plan's D-10 row); the mechanism decided here, the credentials owned by the environment
- **Governing references (official documentation exclusively):**
  - Firebase Cloud Messaging — HTTP v1 API and the Admin SDK send APIs (`sendEachForMulticast`, the official batching call)
  - Firebase Admin SDK setup — service-account credentials, the `firebase-admin` artifact
  - Spring Framework — `@Component` + the `Condition` SPI (the `StripeChannelConfiguredCondition` house shape) for the credential-gated bean
  - The parent POM's dependency ledger — `firebase-admin` 9.11.0 already managed («Exception #17: … Google's own SDK for the ecosystem — the official client; no Spring-native equivalent exists»): the house recorded this provider decision at the dependency layer before this ADR; stage 7 consumes it

## Decisions

1. **D-10 (the provider):** **FCM HTTP v1 via the official `firebase-admin` Admin SDK** — the Android/Chrome standard channel and the single provider surface the house already manages. No second provider abstraction is invented: the `PushNotificationPort` module-internal seam exists because the fail-safe fallback consumer exists, not as a speculative multi-provider framework.
2. **The channel joins the existing vocabulary:** `NotificationChannel.PUSH` — the sparse-override semantics untouched (no row = enabled default, so every existing user's behavior is unchanged until they opt explicitly). The send gate is the EMAIL semantics verbatim: the stored preference is consulted before every send.
3. **The addressing record:** `push_tokens` (V173) — one row per device token, UNIQUE on the token (the register call is the upsert; the reinstall rebinds user + platform), the `/me/push-tokens` lifecycle (identity from the authentication itself). Tokens are credentials, not content: the audit mirrors the lifecycle (Envers), the API never lists one user's tokens to another.
4. **Fail-safe (the plan's own rule):** with the credentials absent the channel is an HONEST no-op (`NoopPushAdapter` via the negating condition pair) — every send reports failure-without-pruning, nothing else in the notification flow breaks, and no invented data is returned. With the credentials present, provider verdicts drive the registry: `UNREGISTERED`/`INVALID_ARGUMENT` prune the dead token (the self-healing registry), everything else is a logged failure the channel's own retry owns.
5. **Secrets:** the service-account JSON rides the environment (`marketplace.push.fcm.credentials-json`) — keys outside the code and outside the logs (the plan's mandatory AI/security rule applied to the push credential).

## Consequences

- WebSocket remains NOT push (the plan's §8.1 distinction preserved): the two channels dispatch independently, each behind its own preference.
- The routing contract stays event-shaped: every `sendWebSocket(...)` dispatch site gained its `sendPush(...)` twin — no notification-type enum explosion, no per-channel mega-routing table.
- Multi-provider (APNs direct, etc.) remains out of scope until a measured need exists — FCM is the official path to iOS delivery via the standard client configuration.
