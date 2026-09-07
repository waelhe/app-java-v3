#!/usr/bin/env bash
# Local test for verify-openapi-exceptions.sh (no docker required).
# Proves the plan's acceptance criterion for C2 plus the CodeRabbit round-3 fixes:
#   empty exceptions list -> exit 1
#   non-empty covered list -> exit 0
#   non-empty uncovered / stale / expired -> exit 1
#   expiry must be a real calendar date (month lengths, leap years) -> exit 1 otherwise
#   incompatible parameter / request-body changes fail closed even when a
#   response break is covered -> exit 1
#   response media-type deletion is representable and may be covered -> exit 0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-openapi-exceptions.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/report.txt" <<'REPORT'
==========================================================================
==                            API CHANGE LOG                            ==
==========================================================================
--------------------------------------------------------------------------
--                            What's Deleted                            --
--------------------------------------------------------------------------
- GET    /pet/{petId}

--------------------------------------------------------------------------
--                            What's Changed                            --
--------------------------------------------------------------------------
- GET    /pet/findByStatus
  Return Type:
    - Changed 200 OK
      Media types:
        - Changed application/json
          Schema: Broken compatibility
- DELETE /pet/{petId}
  Return Type:
    - Deleted 204 No Content
--------------------------------------------------------------------------
--                                Result                                --
--------------------------------------------------------------------------
                 API changes broke backward compatibility
--------------------------------------------------------------------------
REPORT

# Case 1: empty exceptions -> exit 1
cat > "$TMP/allow-empty.yml" <<'YML'
# Documented temporary exceptions for backward-incompatible OpenAPI changes.
exceptions: []
YML
if "$VERIFY" "$TMP/report.txt" "$TMP/allow-empty.yml" >/dev/null 2>&1; then
  echo "FAIL case 1: empty exceptions must exit 1"; exit 1
fi
echo "OK case 1: empty exceptions -> exit 1"

# Case 2: non-empty list covering every reported break -> exit 0
cat > "$TMP/allow-covered.yml" <<'YML'
exceptions:
  - path: GET /pet/{petId}
    status: deleted
    ticket: TCK-101
    reason: endpoint retirement scheduled
    expiry: 2999-01-01
  - path: GET /pet/findByStatus
    status: 200
    ticket: TCK-102
    reason: schema narrowing migration
    expiry: 2028-02-29
  - path: DELETE /pet/{petId}
    status: 204
    ticket: TCK-103
    reason: success response removal
    expiry: 2999-01-01
YML
# (TCK-102 expiry is a valid leap-year day: 2028 is a leap year.)
if ! "$VERIFY" "$TMP/report.txt" "$TMP/allow-covered.yml" >/dev/null 2>&1; then
  echo "FAIL case 2: covered exceptions must exit 0"; exit 1
fi
echo "OK case 2: covered exceptions (incl. valid leap-day expiry) -> exit 0"

# Case 3: non-empty list with an uncovered real break -> exit 1
cat > "$TMP/allow-partial.yml" <<'YML'
exceptions:
  - path: GET /pet/{petId}
    status: deleted
    ticket: TCK-101
    reason: endpoint retirement scheduled
    expiry: 2999-01-01
  - path: GET /pet/findByStatus
    status: 99
    ticket: TCK-102
    reason: wrong status documented
    expiry: 2999-01-01
YML
if "$VERIFY" "$TMP/report.txt" "$TMP/allow-partial.yml" >/dev/null 2>&1; then
  echo "FAIL case 3: uncovered break must exit 1"; exit 1
fi
echo "OK case 3: uncovered break -> exit 1"

# Case 4: stale exception matching no real break -> exit 1
cat > "$TMP/allow-stale.yml" <<'YML'
exceptions:
  - path: POST /pet/uploadImage
    status: 200
    ticket: TCK-104
    reason: no longer changing
    expiry: 2999-01-01
YML
if "$VERIFY" "$TMP/report.txt" "$TMP/allow-stale.yml" >/dev/null 2>&1; then
  echo "FAIL case 4: stale exception must exit 1"; exit 1
fi
echo "OK case 4: stale exception -> exit 1"

# Case 5: expired entry -> exit 1
cat > "$TMP/allow-expired.yml" <<'YML'
exceptions:
  - path: GET /pet/{petId}
    status: deleted
    ticket: TCK-101
    reason: long expired
    expiry: 2000-01-01
YML
if "$VERIFY" "$TMP/report.txt" "$TMP/allow-expired.yml" >/dev/null 2>&1; then
  echo "FAIL case 5: expired entry must exit 1"; exit 1
fi
echo "OK case 5: expired entry -> exit 1"

# Case 6: malformed entry (missing fields) -> exit 1
cat > "$TMP/allow-malformed.yml" <<'YML'
exceptions:
  - path: GET /pet/{petId}
    expiry: 2999-01-01
YML
if "$VERIFY" "$TMP/report.txt" "$TMP/allow-malformed.yml" >/dev/null 2>&1; then
  echo "FAIL case 6: malformed entry must exit 1"; exit 1
fi
echo "OK case 6: malformed entry -> exit 1"

# Case 7: expiry matches YYYY-MM-DD but is not a calendar date -> exit 1
cat > "$TMP/allow-calendar.yml" <<'YML'
exceptions:
  - path: GET /pet/{petId}
    status: deleted
    ticket: TCK-101
    reason: endpoint retirement scheduled
    expiry: 2999-02-31
  - path: GET /pet/findByStatus
    status: 200
    ticket: TCK-102
    reason: schema narrowing migration
    expiry: 2023-02-29
  - path: DELETE /pet/{petId}
    status: 204
    ticket: TCK-103
    reason: success response removal
    expiry: 2999-01-01
YML
# 2999-02-31: February never has 31 days; 2023-02-29: 2023 is not a leap year.
if "$VERIFY" "$TMP/report.txt" "$TMP/allow-calendar.yml" >/dev/null 2>&1; then
  echo "FAIL case 7: impossible calendar dates must exit 1"; exit 1
fi
echo "OK case 7: impossible calendar dates (2999-02-31, 2023-02-29) -> exit 1"

# Report with a request-body break and a covered response break (CodeRabbit
# round 3: the parser must not clear its block state on Parameter:/Request:).
cat > "$TMP/report-request.txt" <<'REPORT'
==========================================================================
==                            API CHANGE LOG                            ==
==========================================================================
--------------------------------------------------------------------------
--                            What's Changed                            --
--------------------------------------------------------------------------
- POST   /pet
  Parameter:
    - Add name in query
  Request:
    - Changed application/json
      Schema: Broken compatibility
  Return Type:
    - Changed 200 OK
      Media types:
        - Changed application/json
          Schema: Broken compatibility
--------------------------------------------------------------------------
--                                Result                                --
--------------------------------------------------------------------------
                 API changes broke backward compatibility
--------------------------------------------------------------------------
REPORT

# Case 8: response break covered, request-body break not representable -> exit 1
cat > "$TMP/allow-request.yml" <<'YML'
exceptions:
  - path: POST /pet
    status: 200
    ticket: TCK-201
    reason: schema narrowing migration
    expiry: 2999-01-01
YML
# The optional parameter add must NOT trip the gate; the broken request body must.
if "$VERIFY" "$TMP/report-request.txt" "$TMP/allow-request.yml" >/dev/null 2>&1; then
  echo "FAIL case 8: request-body break alongside a covered response break must exit 1"; exit 1
fi
echo "OK case 8: covered response + incompatible request body (optional param add passes) -> exit 1"

# Report with parameter changes and a covered response break.
cat > "$TMP/report-param.txt" <<'REPORT'
==========================================================================
==                            API CHANGE LOG                            ==
==========================================================================
--------------------------------------------------------------------------
--                            What's Changed                            --
--------------------------------------------------------------------------
- GET    /pet/findByStatus
  Parameter:
    - Delete tag in query
    - Changed limit in query
  Return Type:
    - Changed 200 OK
      Media types:
        - Changed application/json
          Schema: Broken compatibility
--------------------------------------------------------------------------
--                                Result                                --
--------------------------------------------------------------------------
                 API changes broke backward compatibility
--------------------------------------------------------------------------
REPORT

# Case 9: response break covered, parameter deletion/change not representable -> exit 1
cat > "$TMP/allow-param.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    status: 200
    ticket: TCK-102
    reason: schema narrowing migration
    expiry: 2999-01-01
YML
if "$VERIFY" "$TMP/report-param.txt" "$TMP/allow-param.yml" >/dev/null 2>&1; then
  echo "FAIL case 9: parameter changes alongside a covered response break must exit 1"; exit 1
fi
echo "OK case 9: covered response + parameter delete/change -> exit 1"

# Report with a response media-type deletion (RESPONSE_CONTENT_DECREASED).
cat > "$TMP/report-repcontent.txt" <<'REPORT'
==========================================================================
==                            API CHANGE LOG                            ==
==========================================================================
--------------------------------------------------------------------------
--                            What's Changed                            --
--------------------------------------------------------------------------
- GET    /pet/findByStatus
  Return Type:
    - Changed 200 OK
      Media types:
        - Deleted application/xml
        - Changed application/json
          Schema: Backward compatible
--------------------------------------------------------------------------
--                                Result                                --
--------------------------------------------------------------------------
                 API changes broke backward compatibility
--------------------------------------------------------------------------
REPORT

# Case 10: response media-type deletion is representable (status known) -> covered -> exit 0
cat > "$TMP/allow-repcontent.yml" <<'YML'
exceptions:
  - path: GET /pet/findByStatus
    status: 200
    ticket: TCK-301
    reason: application/xml retirement
    expiry: 2999-01-01
YML
if ! "$VERIFY" "$TMP/report-repcontent.txt" "$TMP/allow-repcontent.yml" >/dev/null 2>&1; then
  echo "FAIL case 10: representable response content deletion must exit 0 when covered"; exit 1
fi
echo "OK case 10: response media-type deletion covered by (path, status) exception -> exit 0"

echo "ALL openapi-exceptions allowlist tests passed."
