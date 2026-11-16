-- W3 (the unified end-to-end plan §4, the J10 row's dedup requirement:
-- «منع إنشاء تنبيه عاجل مزدوج عند اجتماع أحداث في مسار واحد») — the
-- duplicate-urgent-alert guard's RACE backstop, built CONCURRENTLY in its
-- own non-transactional migration (the populated-table discipline — the
-- V212/V226/V227/V228 precedent verbatim).
--
-- WHY AN INDEX (and not only the service check): the service's overlap
-- read runs BEFORE the insert, so two concurrent publications of the SAME
-- alert (the double-submit race — one operator, two taps, one retrying
-- publisher) can both pass the read and both insert. This partial unique
-- index is the structural truth: one live row per (source, neighborhood,
-- title, window-start). The service translates the 23505 into the honest
-- 409 (the V73/V103 house translation), so the race never surfaces as a
-- 500.
--
-- WHY THESE COLUMNS: same source + same neighborhood + same title + same
-- validFrom is the EXACT duplicate of one publication intent. A different
-- title, a different neighborhood, a different window start, or a
-- withdrawn predecessor is a LEGITIMATE new alert — none of those collide
-- here. The broader overlapping-window semantics (a re-publication with a
-- shifted start that still overlaps the predecessor's live window) are the
-- SERVICE's own check, deliberately not an index predicate (a
-- time-overlap predicate is not immutably indexable in PostgreSQL).
CREATE UNIQUE INDEX CONCURRENTLY uq_urgent_alerts_same_window
    ON urgent_alerts(source_id, location_id, title, valid_from)
    WHERE is_withdrawn = FALSE AND is_deleted = FALSE;
