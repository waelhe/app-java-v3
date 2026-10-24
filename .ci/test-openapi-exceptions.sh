#!/usr/bin/env bash
# Local test for verify-openapi-exceptions.sh (no docker required).
# Feeds the verifier OFFICIAL-shape JSON reports (the structure measured from
# openapi-diff 2.1.7's --json output on isolated scenarios) and proves the
# gate's full contract matrix:
#
#   console-era guarantees (kept literally):
#     empty exceptions list + breaks          -> exit 1
#     full coverage                           -> exit 0
#     uncovered break                         -> exit 1
#     stale exception                         -> exit 1
#     expired expiry                          -> exit 1
#     expiry must be a real calendar date     -> exit 1 otherwise (Feb 30 etc.)
#     incompatible parameter / request-body   -> exit 1 (fail closed)
#     deleted endpoints / removed statuses    -> representable, coverable -> 0
#
#   JSON-era classification (new, grounded in ChangedParameters.isCoreChanged):
#     optional parameter additions co-present with covered breaks -> exit 0
#     required parameter addition uncovered                     -> exit 1
#     required parameter addition covered by kind: parameter-add -> exit 0
#     parameter-add entry without required: true                -> exit 1
#     parameter-add entry with status too (ambiguous)           -> exit 1
#     unknown kind value                                       -> exit 1
#     changedOperation incompatible with no classified record   -> exit 1
#     report incompatible with zero extractable records        -> exit 1
#     compatible-only report + empty allowlist                 -> exit 0
#     compatible-only report + non-empty allowlist             -> exit 1 (stale)
#     backward compatibility: the repo's live allowlist file
#       covers its documented (POST disputes, 200) break        -> exit 0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-openapi-exceptions.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------- fixtures
# Structurally faithful to the measured 2.1.7 --json output: node-level
# compatible/incompatible flags, apiResponses.changed keyed by status code,
# parameters.{increased,missing,changed} with required-ness on Parameter.
cat > "$TMP/report-mixed.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [
    {"method": "DELETE", "pathUrl": "/pet/{petId}", "path": "/pet/{petId}", "operation": {}}
  ],
  "newEndpoints": [],
  "deprecatedEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/pet/findByStatus", "httpMethod": "GET",
      "incompatible": true, "compatible": false,
      "parameters": {
        "increased": [
          {"name": "page", "in": "query", "required": false, "schema": {"type": "integer"}},
          {"name": "force", "in": "query", "required": true, "schema": {"type": "boolean"}}
        ],
        "missing": [], "changed": [],
        "incompatible": true, "compatible": false
      },
      "apiResponses": {
        "missing": {}, "increased": {},
        "changed": {"200": {"incompatible": true, "compatible": false}},
        "incompatible": true, "compatible": false
      }
    },
    {
      "pathUrl": "/users", "httpMethod": "GET",
      "incompatible": true, "compatible": false,
      "parameters": {
        "increased": [{"name": "size", "in": "query", "required": false}],
        "missing": [], "changed": [],
        "incompatible": false, "compatible": true
      },
      "apiResponses": {
        "missing": {"204": {"description": "deleted"}},
        "increased": {}, "changed": {},
        "incompatible": true, "compatible": false
      }
    }
  ]
}
JSON

cat > "$TMP/report-param-remove.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/pet/findByStatus", "httpMethod": "GET",
      "incompatible": true, "compatible": false,
      "parameters": {
        "increased": [], "changed": [],
        "missing": [{"name": "q", "in": "query", "required": false}],
        "incompatible": true, "compatible": false
      },
      "apiResponses": {"missing": {}, "increased": {}, "changed": {}, "incompatible": false, "compatible": true}
    }
  ]
}
JSON

cat > "$TMP/report-param-change.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/pet/findByStatus", "httpMethod": "GET",
      "incompatible": true, "compatible": false,
      "parameters": {
        "increased": [], "missing": [],
        "changed": [{"name": "q", "in": "query", "incompatible": true, "compatible": false}],
        "incompatible": true, "compatible": false
      },
      "apiResponses": {"missing": {}, "increased": {}, "changed": {}, "incompatible": false, "compatible": true}
    }
  ]
}
JSON

cat > "$TMP/report-request-body.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/things", "httpMethod": "POST",
      "incompatible": true, "compatible": false,
      "requestBody": {
        "incompatible": true, "compatible": false,
        "content": {"missing": {"application/json": {}}, "increased": {}, "changed": {}}
      },
      "apiResponses": {"missing": {}, "increased": {}, "changed": {}, "incompatible": false, "compatible": true}
    }
  ]
}
JSON

cat > "$TMP/report-op-umbrella.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/secure", "httpMethod": "GET",
      "incompatible": true, "compatible": false,
      "apiResponses": {"missing": {}, "increased": {}, "changed": {}, "incompatible": false, "compatible": true}
    }
  ]
}
JSON

cat > "$TMP/report-schema-only.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedSchemas": [
    {"schema": {"type": "object"}, "incompatible": true, "compatible": false}
  ],
  "changedOperations": []
}
JSON

cat > "$TMP/report-compatible.json" <<'JSON'
{
  "compatible": true, "incompatible": false, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/pet/findByStatus", "httpMethod": "GET",
      "incompatible": false, "compatible": true,
      "parameters": {
        "increased": [{"name": "page", "in": "query", "required": false}],
        "missing": [], "changed": [],
        "incompatible": false, "compatible": true
      },
      "apiResponses": {"missing": {}, "increased": {}, "changed": {}, "incompatible": false, "compatible": true}
    }
  ]
}
JSON

# The repo's LIVE allowlist entry (POST disputes 200) + the matching record.
cat > "$TMP/report-disputes.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/api/v1/bookings/{bookingId}/disputes", "httpMethod": "POST",
      "incompatible": true, "compatible": false,
      "apiResponses": {
        "missing": {"200": {"description": "ok"}},
        "increased": {}, "changed": {},
        "incompatible": true, "compatible": false
      }
    }
  ]
}
JSON

# ---------------------------------------------------------------- allowlists
cat > "$TMP/allow-empty.yml" <<'YML'
# Documented temporary exceptions for backward-incompatible OpenAPI changes.
exceptions: []
YML

cat > "$TMP/allow-full.yml" <<'YML'
# Documented temporary exceptions for backward-incompatible OpenAPI changes.
exceptions:
  - path: DELETE /pet/{petId}
    status: deleted
    ticket: T-1
    reason: endpoint retired
    expiry: 2099-12-31
  - path: GET /pet/findByStatus
    status: "200"
    ticket: T-2
    reason: response schema documented delta
    expiry: 2099-12-31
  - path: GET /pet/findByStatus
    kind: parameter-add
    required: true
    parameters: "force"
    ticket: T-3
    reason: required filter added
    expiry: 2099-12-31
  - path: GET /users
    status: "204"
    ticket: T-4
    reason: status retired
    expiry: 2099-12-31
YML

cat > "$TMP/allow-partial.yml" <<'YML'
exceptions:
  - path: DELETE /pet/{petId}
    status: deleted
    ticket: T-1
    reason: endpoint retired
    expiry: 2099-12-31
YML

cat > "$TMP/allow-stale.yml" <<'YML'
exceptions:
  - path: GET /nowhere
    status: "418"
    ticket: T-9
    reason: never matches
    expiry: 2099-12-31
YML

cat > "$TMP/allow-expired.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    status: "200"
    ticket: T-2
    reason: response schema documented delta
    expiry: 2020-01-01
YML

cat > "$TMP/allow-bad-date.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    status: "200"
    ticket: T-2
    reason: response schema documented delta
    expiry: 2099-02-30
YML

cat > "$TMP/allow-param-no-required.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    kind: parameter-add
    ticket: T-3
    reason: required filter added
    expiry: 2099-12-31
YML

cat > "$TMP/allow-param-ambiguous.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    kind: parameter-add
    required: true
    status: "200"
    ticket: T-3
    reason: ambiguous entry
    expiry: 2099-12-31
YML

cat > "$TMP/allow-unknown-kind.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    kind: header-add
    required: true
    ticket: T-3
    reason: invented kind
    expiry: 2099-12-31
YML

# A minimal single-break report: exactly one GET /pet/findByStatus 200
# response break. Field-validation cases (expiry / calendar / kind /
# required / ambiguity) pair it with a single defective entry so the ONLY
# failure reason is the validation branch under test (CodeRabbit review on
# #716: a case must keep failing for its intended reason when every other
# check is hypothetically removed).
cat > "$TMP/report-single.json" <<'JSON'
{
  "compatible": false, "incompatible": true, "different": true, "unchanged": false,
  "missingEndpoints": [],
  "changedOperations": [
    {
      "pathUrl": "/pet/findByStatus", "httpMethod": "GET",
      "incompatible": true, "compatible": false,
      "parameters": {"increased": [], "missing": [], "changed": [], "incompatible": false, "compatible": true},
      "apiResponses": {
        "missing": {}, "increased": {},
        "changed": {"200": {"incompatible": true, "compatible": false}},
        "incompatible": true, "compatible": false
      }
    }
  ]
}
JSON

check() {
  # check <desc> <want-exit> <message-pattern> <report> <allowlist>
  # The pattern (may be empty for expected-success cases) must appear in
  # the verifier output: it pins each failure case to its INTENDED branch,
  # so deleting any other check cannot keep the case green unnoticed.
  local desc="$1" want="$2" pattern="$3"; shift 3
  local out
  if out="$("$VERIFY" "$@" 2>&1)"; then got=0; else got=1; fi
  if [[ "$got" != "$want" ]]; then
    echo "FAIL: $desc (want exit $want, got $got)"; exit 1
  fi
  if [[ -n "$pattern" ]] && ! grep -q -- "$pattern" <<<"$out"; then
    echo "FAIL: $desc (exit ok but output lacks /$pattern/)"; exit 1
  fi
  echo "OK: $desc"
}

# -------------------------------------------------- console-era guarantees
check "breaks + empty allowlist -> 1"                  1 "UNCOVERED"            "$TMP/report-mixed.json" "$TMP/allow-empty.yml"
check "partial coverage -> 1 (uncovered)"             1 "UNCOVERED"            "$TMP/report-mixed.json" "$TMP/allow-partial.yml"
check "stale exception -> 1"                           1 "STALE"                "$TMP/report-compatible.json" "$TMP/allow-stale.yml"
check "expired expiry -> 1"                            1 "expired"              "$TMP/report-single.json" "$TMP/allow-expired.yml"
check "invalid calendar expiry (Feb 30) -> 1"          1 "not a valid calendar date" "$TMP/report-single.json" "$TMP/allow-bad-date.yml"
check "parameter removal -> 1 (unrepresentable)"       1 "UNREPRESENTABLE.*param-remove"   "$TMP/report-param-remove.json" "$TMP/allow-empty.yml"
check "parameter required flip -> 1 (unrepresentable)" 1 "UNREPRESENTABLE.*param-change"  "$TMP/report-param-change.json" "$TMP/allow-empty.yml"
check "request body incompatible -> 1 (unrepresentable)" 1 "UNREPRESENTABLE.*request-body" "$TMP/report-request-body.json" "$TMP/allow-empty.yml"

# -------------------------------------------------- JSON-era classification
# The springdoc shape: optional additions co-present with covered breaks
# (this also proves full coverage of the mixed report -> 0).
check "optional adds co-present + full coverage -> 0"  0 ""                     "$TMP/report-mixed.json" "$TMP/allow-full.yml"
check "required param add uncovered -> 1"              1 "UNCOVERED.*parameter-add"       "$TMP/report-mixed.json" "$TMP/allow-partial.yml"
check "param-add entry without required:true -> 1"     1 "required: true"                "$TMP/report-single.json" "$TMP/allow-param-no-required.yml"
check "param-add entry with status too -> 1"           1 "ambiguous"                     "$TMP/report-single.json" "$TMP/allow-param-ambiguous.yml"
check "unknown kind -> 1"                               1 "unknown kind"                  "$TMP/report-single.json" "$TMP/allow-unknown-kind.yml"
check "unclassified incompatible operation -> 1"       1 "operation-incompatible"        "$TMP/report-op-umbrella.json" "$TMP/allow-empty.yml"
check "schema-only incompatible report -> 1"           1 "report-incompatible"           "$TMP/report-schema-only.json" "$TMP/allow-empty.yml"
check "compatible-only report + empty allowlist -> 0"  0 ""                             "$TMP/report-compatible.json" "$TMP/allow-empty.yml"
check "compatible-only report + non-empty allowlist -> 1 (stale)" 1 "STALE"       "$TMP/report-compatible.json" "$TMP/allow-stale.yml"

# -------------------------------------------------- backward compatibility
# The repository's live allowlist file must keep working unchanged against
# its documented break (the disputes 200->201 status move).
check "live repo allowlist covers its break -> 0"     0 ""                     "$TMP/report-disputes.json" "$SCRIPT_DIR/openapi-compat-allowlist.yml"
# And it must be flagged stale against a report that does not contain the break.
check "live repo allowlist stale without break -> 1"  1 "STALE"                "$TMP/report-compatible.json" "$SCRIPT_DIR/openapi-compat-allowlist.yml"

echo "All verify-openapi-exceptions.sh contract tests passed."
