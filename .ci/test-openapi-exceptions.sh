#!/usr/bin/env bash
# Local test for verify-openapi-exceptions.sh (no docker required).
# Proves the plan's acceptance criterion for C2:
#   empty exceptions list -> exit 1
#   non-empty covered list -> exit 0
#   non-empty uncovered / stale / expired -> exit 1
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$SCRIPT_DIR/verify-openapi-exceptions.sh"
case "$(uname -s)" in
  *MINGW*|*MSYS*) AWK_GOOD=awk ;; # git-bash ships gawk
  *) AWK_GOOD=awk ;;
esac

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
    expiry: 2999-01-01
  - path: DELETE /pet/{petId}
    status: 204
    ticket: TCK-103
    reason: success response removal
    expiry: 2999-01-01
YML
if ! "$VERIFY" "$TMP/report.txt" "$TMP/allow-covered.yml" >/dev/null 2>&1; then
  echo "FAIL case 2: covered exceptions must exit 0"; exit 1
fi
echo "OK case 2: covered exceptions -> exit 0"

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

echo "ALL openapi-exceptions allowlist tests passed."