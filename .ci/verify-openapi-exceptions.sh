#!/usr/bin/env bash
# Verifies that incompatible OpenAPI changes reported by openapi-diff are fully
# covered by documented exceptions in .ci/openapi-compat-allowlist.yml.
#
# Input: the OFFICIAL JSON report of openapitools/openapi-diff (the same tool
# the gate runs, produced with --json). The JSON carries the tool's full
# semantic model — every node ships its own compatible/incompatible verdict
# computed by the official model (ComposedChanged.isCoreChanged) — so this
# verifier classifies from the tool's own verdicts instead of re-deriving
# them from console prose. This closes the measured [UNREPRESENTABLE] gap of
# the console era: the console renderer prints "- Add <param>" for every
# parameter addition and does not expose required-ness, so a COMPATIBLE
# optional addition could not be distinguished from an INCOMPATIBLE required
# one and every add failed closed.
#
# Classification (grounded in the official model; measured on openapi-diff
# 2.1.7 — org.openapitools.openapidiff.core.model.ChangedParameters):
#   ChangedParameters.isCoreChanged():
#     missing (removed params)            -> INCOMPATIBLE (REQUEST_PARAMS_DECREASED)
#     increased with required == true     -> INCOMPATIBLE (REQUEST_PARAMS_REQUIRED_INCREASED)
#     increased with required != true     -> COMPATIBLE  (passes, no exception needed)
# Extracted records and their allowlist representation:
#   missingEndpoints[]                                        -> (METHOD, path, deleted)
#   apiResponses.missing keys (status removed)                -> (METHOD, path, code)
#   apiResponses.changed[code].incompatible == true           -> (METHOD, path, code)
#     (component-schema breaks propagate to the referencing operation's
#      response node — measured: a $ref'd schema type change flags
#      apiResponses.changed["200"].incompatible — so they are representable)
#   parameters.increased[] with required == true              -> (METHOD, path, parameter-add)
# Fail-closed classes (no allowlist representation, by design — the console
# era contract kept literally):
#   parameters.missing[] (REQUEST_PARAMS_DECREASED)           -> [UNREPRESENTABLE]
#   parameters.changed[].incompatible (required/style flip)   -> [UNREPRESENTABLE]
#   requestBody.incompatible (content/schema/required)        -> [UNREPRESENTABLE]
#   changedOperation.incompatible with no classified record   -> [UNREPRESENTABLE]
#   report.incompatible with zero extracted records           -> [UNREPRESENTABLE]
# The umbrellas keep the gate fail-closed under model drift: if a future tool
# version marks anything else incompatible, it surfaces as an unexplained
# operation/report instead of passing silently.
#
# Coverage is exact and bidirectional (the governing contract, unchanged):
#   - every reported breaking change must be documented (uncovered fails),
#   - every documented exception must match a real reported break (stale fails),
#   - expiry must be a real calendar date and not in the past,
#   - breaking changes with an empty exceptions list fail by design,
#   - a report with zero incompatible changes and an empty list passes (the
#     tool itself already judged the diff compatible; the CI flow never calls
#     this verifier in that case — the semantics only matter for local runs).
#
# Allowlist entry schema (flat YAML map; response entries are unchanged and
# backward compatible with the console-era file):
#   - path: METHOD /path
#     status: <NNN | deleted>          # response-class entry (default)
#     ticket: ...
#     reason: ...
#     expiry: YYYY-MM-DD
#   - path: METHOD /path
#     kind: parameter-add              # required-parameter-add entry
#     required: true                   # must be exactly true
#     parameters: "name, name"         # optional documentation
#     ticket: ...
#     reason: ...
#     expiry: YYYY-MM-DD
#
# Usage: verify-openapi-exceptions.sh <openapi-diff-report.json> <allowlist.yml>
#   exit 0 = accepted   exit 1 = rejected with named errors
set -euo pipefail

REPORT="${1:?verify-openapi-exceptions.sh <report.json> <allowlist>}"
ALLOWLIST="${2:?verify-openapi-exceptions.sh <report.json> <allowlist>}"
TODAY="$(date +%F)"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required to read the JSON report (fail closed)"; exit 1; }
[[ -s "$REPORT" ]] || { echo "ERROR: diff report is empty/missing: $REPORT"; exit 1; }
[[ -s "$ALLOWLIST" ]] || { echo "ERROR: allowlist is empty/missing: $ALLOWLIST"; exit 1; }
jq empty "$REPORT" >/dev/null 2>&1 || { echo "ERROR: report is not valid JSON: $REPORT"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

### 1. Reported breaking changes, extracted from the official JSON model.
# TSV columns: class \t METHOD \t path \t detail.
jq -r '
  def emit(c; m; p; d): [c, m, p, (d // "-")] | @tsv;
  (.missingEndpoints[]? | emit("deleted"; .method; .pathUrl; "endpoint")),
  (.changedOperations[]? as $op |
    (($op.apiResponses.missing // {}) | keys[]
      | emit("response"; $op.httpMethod; $op.pathUrl; .)),
    (($op.apiResponses.changed // {}) | to_entries[]
      | select(.value.incompatible == true)
      | emit("response"; $op.httpMethod; $op.pathUrl; .key)),
    (($op.parameters.increased // [])[]
      | select(.required == true)
      | emit("param-add"; $op.httpMethod; $op.pathUrl; .name)),
    (($op.parameters.missing // [])[]
      | emit("param-remove"; $op.httpMethod; $op.pathUrl; .name)),
    (($op.parameters.changed // [])[]
      | select(.incompatible == true)
      | emit("param-change"; $op.httpMethod; $op.pathUrl; .name)),
    ($op | select((.requestBody.incompatible // false) == true)
      | emit("request-body"; .httpMethod; .pathUrl; "incompatible request body"))
  )
' "$REPORT" | sort -u > "$TMP/records.tsv"

### 2. Documented exceptions -> (METHOD path KEY) + validity.
# KEY is the status code (or "deleted") for response-class entries and
# "parameter-add" for required-parameter-add entries.
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
    if      (key == "path")       { path = val; split(val, p, " "); if (p[1] ~ /^[A-Z]+$/) { method = p[1]; path = p[2] != "" ? p[2] : "" } }
    else if (key == "status")     status = val
    else if (key == "kind")       kind = val
    else if (key == "required")   required = val
    else if (key == "parameters") parameters = val
    else if (key == "ticket")     ticket = val
    else if (key == "reason")     reason = val
    else if (key == "expiry")     expiry = val
    else err(sprintf("unknown key \047%s\047 (line %d)", key, NR))
  }
  function finalize() {
    if (method == "" || path == "") { err("entry missing \047path: METHOD /path\047"); return }
    if (ticket == "")                { err(sprintf("%s %s missing \047ticket:\047", method, path)); return }
    if (reason == "")                { err(sprintf("%s %s missing \047reason:\047", method, path)); return }
    if (expiry == "")                { err(sprintf("%s %s missing \047expiry:\047", method, path)); return }
    if (expiry !~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) { err(sprintf("%s %s bad expiry \047%s\047 (YYYY-MM-DD)", method, path, expiry)); return }
    if (!valid_date(expiry))         { err(sprintf("%s %s expiry \047%s\047 is not a valid calendar date", method, path, expiry)); return }
    if (kind == "") {
      if (status == "") { err(sprintf("%s %s missing \047status:\047 (response entries need it)", method, path)); return }
      if (required != "" || parameters != "") { err(sprintf("%s %s uses kind-only keys without \047kind:\047", method, path)); return }
      print method "\t" path "\t" status "\t" expiry
    } else if (kind == "parameter-add") {
      if (status != "") { err(sprintf("%s %s has both \047status:\047 and \047kind:\047 - ambiguous", method, path)); return }
      if (required != "true") { err(sprintf("%s %s kind parameter-add requires \047required: true\047", method, path)); return }
      print method "\t" path "\t" "parameter-add" "\t" expiry
    } else {
      err(sprintf("%s %s unknown kind \047%s\047 (only parameter-add exists)", method, path, kind))
    }
    if (expiry < TODAY) err(sprintf("%s %s expired on %s", method, path, expiry))
  }
  /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
  /^exceptions:/ { next }
  /^[[:space:]]+- / {
    if (has_entry) finalize()
    has_entry = 1; method = ""; path = ""; status = ""; kind = ""; required = ""; parameters = ""; ticket = ""; reason = ""; expiry = ""
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

awk -F '\t' '{ print $1 "\t" $2 "\t" $3 }' "$TMP/allow.txt" | sort -u > "$TMP/allow-norm.txt"

### 3. Coverage keys from the extracted records.
awk -F '\t' '
  $1 == "deleted"  { print $2 "\t" $3 "\t" "deleted" }
  $1 == "response" { print $2 "\t" $3 "\t" $4 }
  $1 == "param-add" { print $2 "\t" $3 "\t" "parameter-add" }
' "$TMP/records.tsv" | sort -u > "$TMP/reported.txt"

### 4. Named errors: uncovered, stale, unrepresentable, unexplained, expired.
FAIL=0

# Reported breaking changes with no documented exception.
comm -23 "$TMP/reported.txt" "$TMP/allow-norm.txt" > "$TMP/uncovered.txt"
if [[ -s "$TMP/uncovered.txt" ]]; then
  echo "Uncovered breaking changes (document an exception for each):"
  while IFS=$'\t' read -r m p k; do printf '  [UNCOVERED] %s %s (key %s)\n' "$m" "$p" "$k"; done < "$TMP/uncovered.txt"
  FAIL=1
fi

# Documented exceptions matching no real reported break (stale).
comm -13 "$TMP/reported.txt" "$TMP/allow-norm.txt" > "$TMP/stale.txt"
if [[ -s "$TMP/stale.txt" ]]; then
  echo "Stale exceptions (no longer match any reported breaking change - remove them):"
  while IFS=$'\t' read -r m p k; do printf '  [STALE] %s %s (key %s)\n' "$m" "$p" "$k"; done < "$TMP/stale.txt"
  FAIL=1
fi

# Incompatible parameter/request-body changes the allowlist cannot represent
# (fail closed rather than passing silently — the console-era contract).
awk -F '\t' '$1 == "param-remove" || $1 == "param-change" || $1 == "request-body" { print }' "$TMP/records.tsv" > "$TMP/unrep.txt"
if [[ -s "$TMP/unrep.txt" ]]; then
  echo "Incompatible changes the allowlist cannot represent (resolve or revert them):"
  while IFS=$'\t' read -r c m p d; do printf '  [UNREPRESENTABLE] %s %s (%s: %s)\n' "$m" "$p" "$c" "$d"; done < "$TMP/unrep.txt"
  FAIL=1
fi

# Umbrella: an operation the tool marked incompatible must yield at least one
# classified record — otherwise its incompatibility source is unclassified
# (security requirements, extensions, or model drift) and the gate fails closed.
jq -r '.changedOperations[]? | select(.incompatible == true) | [(.httpMethod // "?"), (.pathUrl // "?")] | @tsv' "$REPORT" \
  | sort -u > "$TMP/incompatible-ops.tsv"
while IFS=$'\t' read -r m p; do
  needle=$'\t'"$m"$'\t'"$p"$'\t'
  if ! grep -qF "$needle" "$TMP/records.tsv"; then
    echo "  [UNREPRESENTABLE] $m $p (operation-incompatible: unclassified incompatible child)"
    FAIL=1
  fi
done < "$TMP/incompatible-ops.tsv"

# Umbrella: the report itself marked incompatible but nothing was extractable
# (e.g. a component-schema break with no referencing operation). Fails closed.
ROOT_INCOMPATIBLE="$(jq -r '.incompatible // false' "$REPORT")"
if [[ "$ROOT_INCOMPATIBLE" == "true" && ! -s "$TMP/reported.txt" && ! -s "$TMP/unrep.txt" ]]; then
  echo "  [UNREPRESENTABLE] report-incompatible: the tool marked the diff incompatible but no classified record was extractable"
  FAIL=1
fi

if [[ "$FAIL" -eq 0 ]]; then
  echo "All incompatible changes are covered by documented exceptions."
  exit 0
fi
exit 1
