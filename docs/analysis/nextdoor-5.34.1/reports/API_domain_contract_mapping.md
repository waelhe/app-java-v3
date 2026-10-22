# Nextdoor 5.34.1 → our platform: API/domain contract map (Wave B)

Governing reference: `docs/analysis/nextdoor-5.34.1/reports/Nextdoor_5.34.1_UI_UX_study.md` (PR #590, Wave A truth)
Extraction evidence (this wave): `docs/analysis/nextdoor-5.34.1/evidence/{graphql_apollo_type_enums.txt, network_class_names.txt, api_hosts_graphql.txt, manifest.utf8.txt, screens.txt}`
Artifact identity: com.nextdoor 5.34.1 (1254037), sha256 `7cb7ae84815cf399c0…deb12`, decompiled by apktool 3.0.3 + REA/JADX 0.7.1.

This is a **mapping of extracted concepts, not a DB schema**. Every claim has a
proof level: OBSERVED (string present in DEX/manifest) → INFERRED (semantics
deduced from a named class) → UNKNOWN (proof impossible without a server
contract). No inferred or unknown claim is voiced as fact.

## 1. Transport layer (what they run)

| Item | Nextdoor 5.34.1 evidence | Proof |
|---|---|---|
| GraphQL client | Apollo Kotlin runtime (obfuscated); normalized persistence wired | OBSERVED — ApolloInitializer + error strings |
| Normalized offline cache | Apollo normalized cache (`com.apollographql.apollo.cache.normalized.sql.ApolloInitializer`) | OBSERVED |
| REST/auth host | `flask.us.nextdoor.com` (prod) + `auth.nextdoor.com` (client enum constants) | OBSERVED |
| Chat transport | Stream (getstream.io) SDK (`io.getstream.*` socket/query classes) | OBSERVED |
| Tracking | `com.nextdoor.events.tracking.*` Protobuf-Wire payloads (com.nextdoor.events.*) | OBSERVED |
| Service naming | `flask.` prefix + Django job posting → Python service fleet | INFERRED (weak) |
| GraphQL ops inventory | complete ops list — NOT RECOVERED: op classes R8-renamed; APK-only extraction impossible | UNKNOWN (documented) |

**Our equivalent**: `marketplace-edge` BFF (verified in Wave A) + Modulith event
bus + springdoc OpenAPI contracts. Different shape: they fetch through ONE
GraphQL surface with an offline normalized cache; our contract is REST/OpenAPI
+ REST-gap carrying events as Modulith listeners.

## 2. Concept → module mapping

Verified module set of this platform (from root pom.xml, 2026-10-22):
`marketplace-shared, platform-infra, identity, catalog, booking, payments,
pricing, reviews, messaging, search, provider, availability, notifications,
ledger, disputes, media, geo, realestate, community, institutions, jobs,
knowledge, console, ai, discovery, orders, delivery, lending, app, edge`
(30 modules).

| Concept from DEX (proof) | Our owner module | Coverage |
|---|---|---|
| Profile + verification surfaces (`com.nextdoor.profile*`, `verification`) [OBSERVED package] | `marketplace-identity` | covered |
| Neighborhood membership + community surface [OBSERVED pkg] | `marketplace-community` | partial (WARD IN of enum-space ignored) |
| Feed metablocks FSM (`metablocks, feedmodel, feedordering`) [OBSERVED pkg] | `marketplace-community` | absent (no FSM; Wave C) |
| Reactions/Comments (`reactions, metablocks ActionType link`) [OBSERVED] | `marketplace-community` | partial |
| Classifieds statuses (`ClassifiedStatus, PlotOccupancyStatus, classifieds 22+ enum variants`) [OBSERVED] | `marketplace-catalog` | partial (no car-based classifieds) |
| Business pages (entitypages, OrgJsonObject, favesdirectory) [OBSERVED] | `marketplace-provider` (:provider= businesses) + `marketplace-catalog` | partial |
| Business reviews (rating surfaces) | `marketplace-reviews` | covered |
| Events & RSVP (`localevents, events`) [OBSERVED pkg] | none | absent (no module; Wave C gate) |
| Polls & votes | none | absent |
| Neighborhood polygons / geofencing (`GeoMentionSource, Maps, searchui`) [OBSERVED] | `marketplace-geo` (tree only — no PostGIS now) | absent; Wave C gate |
| Notifications (3 pipelines) [OBSERVED] | `marketplace-notifications` | covered |
| Messaging [OBSERVED via io.getstream] | `marketplace-messaging` | covered (different SDK) |
| Moderation & appeals (`moderation, report, unverifiedFeed`) | none owner | absent; Wave C |
| Mutes and report states (`ProfileMuteStatus, PostMuteStatus, ReportUserStatus`) [OBSERVED constants] | `marketplace-community` + `marketplace-identity` | partial |
| Search surfaces [OBSERVED] | `marketplace-search` | covered |
| Real-time updates (rtm) | none push-style | partial (Modulith bus only) |

## 3. What is NOT recoverable from an APK alone

- Server-side DB schema, tenancy, authorization rules.
- GraphQL operation documents (APQ transport found, no op bodies).
- Server population thresholds / ordering of trust score.

## 4. Verdict vs Wave A assumptions

- Wave A claimed "26 modules/ — corrected: **30 modules** (verified from pom.xml today).
- Wave A "no binary analysis" — OBSOLETE: PR #590 + apktool 3.0.3 + REA/JADX 0.7.1 extraction performed on 5.34.1 (1254037).
- Owner's primary thesis held: no DB can be read from APK; concept mapping with
  proof levels is the ceiling, leaving shapes not proven to equipment we don't
  possess today.

## 5. Where is this NOT a copy instruction

Do NOT adopt Nextdoor's client stack (Apollo/Django/Stream). Transfer remains
PATTERN-level: metablocks FSM, module boundaries, offline normalized-cache
topology, bus-pull over TRPC-like enqueue.
