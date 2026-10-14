-- V214 — the console's Day-1 operational inventory (the console-operations
-- wave's seed): the planning record's FIRST revision and the feature-flag
-- rows for the EXISTING capabilities the operator owns the levers for.
--
-- Why a seed at all (the honest gap this closes):
--   * console_planning_revisions has lived EMPTY since V201 — the public
--     read GET /api/v1/console/planning answers 404 on every fresh
--     environment, and the operator's publish surface had no Day-1
--     baseline to roll back to. Revision 1 below is the §1.4 vocabulary's
--     own default: all six SUPPORTED_SECTIONS visible in the discovery
--     order — exactly the shape PlanningPayloadValidator accepts (closed
--     shape, known codes, no categoryRefs: the catalog's dictionary is
--     environment data, deliberately not pinned here).
--   * feature_flags: every key below names a capability that EXISTS in
--     code today (the V157 table's own ceiling: «a flag row names an
--     EXISTING capability; it never creates one»). All five rows ship
--     enabled=TRUE — the current behavior of those capabilities — so the
--     flip lever is armed WITHOUT changing any runtime behavior. The
--     services' request-time gate adoption (FeatureGatePort, the
--     GeoLookupPort precedent) rides the follow-up waves, one capability
--     per PR; until adoption, flipping a row changes the board's state
--     and the audit trail — the documented semantics of an armed lever.
--
-- Attribution (the house seed convention): created_by/updated_by carry
-- the literal 'system-seed' — the rows are V214's own children, and the
-- change-history read shows them as such (SQL seeds bypass JPA, so no
-- Envers revision is written — the migration file itself is the trail).
--
-- Numbering: V214 — the console module's own sequential claim after
-- V213 (the actuator audit events table).

-- The planning record's Day-1 revision: the six documented sections,
-- all visible, in the discovery order the §1.4 vocabulary defines.
INSERT INTO console_planning_revisions (
    id, revision_no, payload, note, is_deleted, version,
    created_by, created_at, updated_by, updated_at)
VALUES (
    gen_random_uuid(), 1,
    '{"sections":[{"code":"URGENT","visible":true,"position":1},{"code":"FOLLOWED_SOURCES","visible":true,"position":2},{"code":"LOST_FOUND","visible":true,"position":3},{"code":"RECOMMENDATIONS","visible":true,"position":4},{"code":"EVENTS","visible":true,"position":5},{"code":"FOR_YOU","visible":true,"position":6}]}',
    'بذرة اليوم-واحد: الأقسام الستة الموثّقة كما يعرفها محرّك العميل',
    FALSE, 0, 'system-seed', now(), 'system-seed', now());

-- The Day-1 flag inventory: one row per EXISTING capability (the service
-- class that owns it today), enabled=TRUE = the current behavior.
INSERT INTO feature_flags (
    id, key_name, description, enabled, is_deleted, version,
    created_by, created_at, updated_by, updated_at)
VALUES
    (gen_random_uuid(), 'community.events.enabled',
     'فعاليات الحي — NeighborhoodEventService (V83/V174): مفتاح إيقاف سطح الفعاليات عند الحادث',
     TRUE, FALSE, 0, 'system-seed', now(), 'system-seed', now()),
    (gen_random_uuid(), 'community.market.enabled',
     'سوق الحي والحراج — NeighborhoodMarketItemService (V90): مفتاح إيقاف السطح عند الحادث',
     TRUE, FALSE, 0, 'system-seed', now(), 'system-seed', now()),
    (gen_random_uuid(), 'community.polls.enabled',
     'استطلاعات الحي — NeighborhoodPollService (V100): مفتاح إيقاف الاستطلاعات عند الحادث',
     TRUE, FALSE, 0, 'system-seed', now(), 'system-seed', now()),
    (gen_random_uuid(), 'community.lost-found.enabled',
     'المفقودات واللقطات — حالة المفقودات (V171): مفتاح إيقاف السطح عند الحادث',
     TRUE, FALSE, 0, 'system-seed', now(), 'system-seed', now()),
    (gen_random_uuid(), 'institutions.urgent.enabled',
     'المصادر العاجلة المفوَّضة — المصادر والتنبيهات (V178): مفتاح إيقاف قناة العاجل عند الحادث',
     TRUE, FALSE, 0, 'system-seed', now(), 'system-seed', now());
