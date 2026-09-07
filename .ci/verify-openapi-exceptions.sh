#!/usr/bin/env bash
# Verifies that incompatible OpenAPI changes reported by openapi-diff are fully
# covered by documented exceptions in .ci/openapi-compat-allowlist.yml.
#
# Coverage is exact and bidirectional:
#   - every reported breaking (path, status) must be documented (uncovered breaks fail),
#   - every documented exception must match a real reported break (stale exceptions fail).
# Empty exceptions list is an error by design (a breaking change without a documented
# exception must never pass the gate silently).
#
# Changes outside "Return Type:" are fail-closed: the console report renders
# parameter and request-body changes without a response status, so the
# (path, status) allowlist cannot represent them. Grounded in the official
# openapi-diff model semantics (OpenAPITools/openapi-diff, core module):
#   - ChangedParameters.isCoreChanged: missing params -> INCOMPATIBLE (REQUEST_PARAMS_DECREASED)
#   - ChangedParameter.isCoreChanged: required-increase/style/explode/allowEmpty-decrease -> INCOMPATIBLE
#   - ChangedContent.isCoreChanged: deleted media types -> INCOMPATIBLE (REQUEST/RESPONSE_CONTENT_DECREASED)
#   - ChangedMediaType: rendered as "Schema: Broken compatibility" when incompatible
# Therefore "- Delete/- Changed <param>" lines and request-side breaks emit
# [UNREPRESENTABLE] and fail the gate. Response media-type deletion is
# representable (the enclosing status is known) and is treated as a (path, status) break.
# Known residual: "- Add <param>" renders identically for required (incompatible)
# and optional (compatible) additions; a required-add co-occurring with a
# documented response break is not distinguishable from the console report -
# a required-add alone still fails via the uncovered/stale arms because no
# (path, status) break is reported for it.
#
# Usage: verify-openapi-exceptions.sh <openapi-diff-report.txt> <allowlist.yml>
#   exit 0 = accepted   exit 1 = rejected with named errors
set -euo pipefail

REPORT="${1:?verify-openapi-exceptions.sh <report> <allowlist>}"
ALLOWLIST="${2:?verify-openapi-exceptions.sh <report> <allowlist>}"
TODAY="$(date +%F)"

[[ -s "$REPORT" ]] || { echo "ERROR: diff report is empty/missing: $REPORT"; exit 1; }
[[ -s "$ALLOWLIST" ]] || { echo "ERROR: allowlist is empty/missing: $ALLOWLIST"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

### 1. Reported breaking (METHOD path STATUS) from the openapi-diff console report.
# Console format (per ConsoleRender.java): endpoint items are "- METHOD path";
# response changes are "- Changed <code> OK" under "Return Type:", each followed by
# "Schema: Broken compatibility" for incompatible changes; "What's Deleted" lists
# removed endpoints; "- Deleted <code> OK" under "Return Type:" marks removed responses.
# Per-operation block order (ol_changed): [Operation ID:] Parameter: Request: Return Type:.
# POSIX-awk compatible (no gawk-only match(s,re,arr)).
awk -v UNREP="$TMP/unrep.txt" '
  function is_del(l) { return l ~ /What.s Deleted/ }
  function is_chg(l) { return l ~ /What.s Changed/ }
  /^[[:space:]]*$/ { next }
  is_del($0) { section = "del"; next }
  is_chg($0) { section = "chg"; next }
  /What.s (New|Deprecated)/ { section = ""; next }
  $0 ~ /^-[[:space:]]+[A-Z]+[[:space:]]+\// {
    if (section == "del" && NF >= 3) { print $2 "\t" $3 "\t" "deleted"; next }
    if (section == "chg" && NF >= 3) { method = $2; path = $3; sub(/\r/, "", path); block = ""; broken_code = ""; next }
    next
  }
  section != "chg" || method == "" { next }
  $0 ~ /Return Type:/ { block = "return"; next }
  $0 ~ /Parameter:/ { block = "param"; next }
  $0 ~ /Request:/ { block = "request"; next }

  # Fail closed: parameter deletions/changes (REQUEST_PARAMS_DECREASED,
  # REQUEST_PARAMS_REQUIRED_INCREASED and friends in the official model).
  # "- Add" params are not failed: the console report cannot distinguish a
  # compatible optional add from an incompatible required add (see header).
  block == "param" && $0 ~ /-[[:space:]]+(Delete|Changed)[[:space:]]+/ {
    print method "\t" path "\t" "parameter-change" > UNREP
    next
  }

  # Fail closed: request-body media type deleted (REQUEST_CONTENT_DECREASED).
  block == "request" && $0 ~ /-[[:space:]]+Deleted[[:space:]]+/ {
    print method "\t" path "\t" "request-content-deleted" > UNREP
    next
  }
  # Fail closed: incompatible request schema (ChangedMediaType broken).
  block == "request" && /Broken compatibility/ {
    print method "\t" path "\t" "request-schema-incompatible" > UNREP
    next
  }

  # Return Type: response-level breaks map to (path, status) - allowlist-representable.
  block == "return" && /-[[:space:]]+(Changed|Deleted)[[:space:]]+[0-9]{3}/ {
    if ($2 == "Deleted") print method "\t" path "\t" $3
    else broken_code = $3
    next
  }
  block == "return" && broken_code != "" && /Broken compatibility/ {
    print method "\t" path "\t" broken_code
    broken_code = ""
    next
  }
  # Response media type removed while the status stays (RESPONSE_CONTENT_DECREASED):
  # representable because the enclosing status is known.
  block == "return" && broken_code != "" && $0 ~ /-[[:space:]]+Deleted[[:space:]]+/ && $0 !~ /[0-9]{3}/ {
    print method "\t" path "\t" broken_code
    broken_code = ""
    next
  }
' <(tr -d '\r' < "$REPORT") | sort -u > "$TMP/reported.txt"

### 2. Documented exceptions -> (METHOD path STATUS) + validity.
# Allowlist entry schema (flat YAML map):
#   - path: METHOD /path
#     status: <NNN | deleted>
#     ticket: ...
#     reason: ...
#     expiry: YYYY-MM-DD (a real calendar date; month lengths and leap years
#     are validated, not only the YYYY-MM-DD shape)
awk '
  function err(msg) { printf "  [ERROR] %s\n", msg > "/dev/stderr"; bad = 1 }
  function valid_date(s,  y, m, d, leap, md) {
    if (s !~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) return 0
    y = substr(s, 1, 4) + 0
    m = substr(s, 6, 2) + 0
    d = substr(s, 9, 2) + 0
    if (m < 1 || m > 12) return 0
    if (m == 2) {
      leap = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)
      md = leap ? 29 : 28
    } else if (m == 4 || m == 6 || m == 9 || m == 11) md = 30
    else md = 31
    return (d >= 1 && d <= md)
  }
  function dispatch(l,   key, val, p) {
    key = l; gsub(/^[[:space:]]+|[[:space:]]+$/, "", key); sub(/[[:space:]]*:.*/, "", key)
    val = l; sub(/^[^:]*:[[:space:]]*/, "", val); sub(/[[:space:]]*$/, "", val)
    gsub(/^"|"$/, "", val); gsub(/\r/, "", val)
    if      (key == "path")   { path = val; split(val, p, " "); if (p[1] ~ /^[A-Z]+$/) { method = p[1]; path = p[2] != "" ? p[2] : "" } }
    else if (key == "status") status = val
    else if (key == "ticket") ticket = val
    else if (key == "reason") reason = val
    else if (key == "expiry") expiry = val
    else err(sprintf("unknown key \047%s\047 (line %d)", key, NR))
  }
  function finalize() {
    if (method == "" || path == "") { err("entry missing \047path: METHOD /path\047"); return }
    if (status == "")                { err("entry missing \047status:\047"); return }
    if (ticket == "")                { err(sprintf("%s %s missing \047ticket:\047", method, path)); return }
    if (reason == "")                { err(sprintf("%s %s missing \047reason:\047", method, path)); return }
    if (expiry == "")                { err(sprintf("%s %s missing \047expiry:\047", method, path)); return }
    if (expiry !~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) { err(sprintf("%s %s bad expiry \047%s\047 (YYYY-MM-DD)", method, path, expiry)); return }
    if (!valid_date(expiry))         { err(sprintf("%s %s expiry \047%s\047 is not a valid calendar date", method, path, expiry)); return }
    print method "\t" path "\t" status "\t" expiry
    if (expiry < TODAY) err(sprintf("%s %s expired on %s", method, path, expiry))
  }
  /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
  /^exceptions:/ { next }
  /^[[:space:]]+- / {
    if (has_entry) finalize()
    has_entry = 1; method = ""; path = ""; status = ""; ticket = ""; reason = ""; expiry = ""
    line = $0; sub(/^[[:space:]]*-[[:space:]]*/, "", line); dispatch(line)
    next
  }
  /^[[:space:]]+[a-z]?[_a-z]+:/ {
    if (!has_entry) { err(sprintf("entry field outside an exception (line %d)", NR)); next }
    dispatch($0)
    next
  }
  { err(sprintf("unparseable line %d: %s", NR, $0)) }
  END { if (has_entry) finalize(); if (bad) exit 1 }
' TODAY="$TODAY" "$ALLOWLIST" > "$TMP/allow.txt" 2> "$TMP/allow-errors.txt" \
  || { echo "Allowlist validation failed:"; cat "$TMP/allow-errors.txt"; exit 1; }

if [[ ! -s "$TMP/allow.txt" ]]; then
  echo "OpenAPI incompatible changes detected and no allowlist exceptions are documented."
  exit 1
fi
awk -F '\t' '{ print $1 "\t" $2 "\t" $3 }' "$TMP/allow.txt" | sort -u > "$TMP/allow-norm.txt"

### 3. Named errors: uncovered breaks, stale exceptions, unrepresentable changes.
FAIL=0

# Reported breaking changes with no documented exception.
comm -23 "$TMP/reported.txt" "$TMP/allow-norm.txt" > "$TMP/uncovered.txt"
if [[ -s "$TMP/uncovered.txt" ]]; then
  echo "Uncovered breaking changes (document an exception for each):"
  while IFS=$'\t' read -r m p s; do printf '  [UNCOVERED] %s %s (status %s)\n' "$m" "$p" "$s"; done < "$TMP/uncovered.txt"
  FAIL=1
fi

# Documented exceptions matching no real reported break (stale).
comm -13 "$TMP/reported.txt" "$TMP/allow-norm.txt" > "$TMP/stale.txt"
if [[ -s "$TMP/stale.txt" ]]; then
  echo "Stale exceptions (no longer match any reported breaking change - remove them):"
  while IFS=$'\t' read -r m p s; do printf '  [STALE] %s %s (status %s)\n' "$m" "$p" "$s"; done < "$TMP/stale.txt"
  FAIL=1
fi

# Incompatible parameter/request-body changes the (path, status) allowlist
# cannot represent: fail closed rather than passing silently.
if [[ -s "$TMP/unrep.txt" ]]; then
  echo "Incompatible changes the (path,status) allowlist cannot represent (resolve or revert them):"
  sort -u "$TMP/unrep.txt" | while IFS=$'\t' read -r m p kind; do printf '  [UNREPRESENTABLE] %s %s (%s)\n' "$m" "$p" "$kind"; done
  FAIL=1
fi

if [[ "$FAIL" -eq 0 ]]; then
  echo "All incompatible changes are covered by documented exceptions."
  exit 0
fi
exit 1
