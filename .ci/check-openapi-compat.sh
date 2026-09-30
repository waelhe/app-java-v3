#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.ci/openapi-compat"
mkdir -p "$WORK_DIR"

LATEST_TAG="${OPENAPI_BASELINE_TAG:-$(git tag --sort=-creatordate | head -n1)}"
if [[ -z "$LATEST_TAG" ]]; then
  echo "No release tag found. Skipping OpenAPI compatibility gate."
  exit 0
fi

echo "Using OpenAPI baseline tag: $LATEST_TAG"

if ! git rev-parse "$LATEST_TAG" >/dev/null 2>&1; then
  echo "Tag '$LATEST_TAG' not found locally; fetch tags before running this script."
  exit 2
fi

CURRENT_SHA="$(git rev-parse HEAD)"

generate_spec() {
  local label="$1"
  local out_file="$2"

  # `clean` is load-bearing (measured 2026-09-29, content/seed-qudsayya-v1):
  # the baseline phase does `git checkout <tag>` and rebuilds WITHOUT
  # touching target/ — Maven's incremental resource copy keeps target/classes
  # entries for files the checkout DELETES (deletion does not refresh source
  # mtimes, so the stale classpath copy looks up-to-date). The baseline jar
  # then ships resources the tag never had. Latent since the tag path was
  # born: the geo seed ghosted in silently (checksum matched => no-op). The
  # placeholder-gated content seed turned it into a hard boot failure
  # (FlywayException: no value provided for placeholder ${seedContent} —
  # the tag's application.yml does not define it). A clean build guarantees
  # each phase's jar contains exactly its own tree.
  ./mvnw -q -pl marketplace-app -am clean package -DskipTests
  java -jar marketplace-app/target/marketplace-app-*.jar >"$WORK_DIR/${label}.log" 2>&1 &
  local app_pid=$!
  trap 'kill $app_pid >/dev/null 2>&1 || true' RETURN

  for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:8080/v3/api-docs" -o "$out_file"; then
      kill $app_pid >/dev/null 2>&1 || true
      wait $app_pid 2>/dev/null || true
      trap - RETURN
      return 0
    fi
    sleep 2
  done

  echo "Failed to fetch /v3/api-docs for $label build"
  kill $app_pid >/dev/null 2>&1 || true
  wait $app_pid 2>/dev/null || true
  exit 1
}

# Baseline tag FIRST, current SECOND (order is load-bearing — measured
# 2026-09-29, content/seed-qudsayya-v1): both phases boot against the SAME
# service-container database. The old order (current, then baseline) made the
# baseline boot against a history the current build had already migrated to
# HEAD — v0.1.0's Flyway then failed validation ("Detected applied migration
# not resolved locally: seed geo qudsaya / seed content qudsayya") on every
# repeatable seed added after the tag. It only ever passed because stale
# target/classes ghosts (fixed above) accidentally shipped current resources
# inside the "baseline" jar. Generating baseline-first on the FRESH database
# lets the tag migrate its own chain cleanly; the current phase afterwards
# migrates FORWARD from the tag's state: V1..V31 are frozen files with
# identical checksums (the never-edit rule + MigrationChecksumGuardTest), and
# the tag-era R__seed_oauth2_client checksum difference is a pending
# repeatable, not a validation error — it re-applies as the retired no-op.
BASELINE_BRANCH="openapi-baseline-$LATEST_TAG"
git checkout -q "$LATEST_TAG"
generate_spec "baseline" "$WORK_DIR/baseline-openapi.json"

cd "$ROOT_DIR"
git checkout -q "$CURRENT_SHA"
generate_spec "current" "$WORK_DIR/current-openapi.json"

# Breaking-change gate: endpoint deletions, schema narrowing, status/media type changes.
openapi_diff_exit=0
docker run --rm -t -v "$WORK_DIR:/spec" openapitools/openapi-diff:latest \
  /spec/baseline-openapi.json /spec/current-openapi.json \
  --fail-on-incompatible > "$WORK_DIR/openapi-diff-report.txt" 2>&1 || openapi_diff_exit=$?

if [[ "$openapi_diff_exit" -eq 0 ]]; then
  echo "OpenAPI backward compatibility gate passed."
  exit 0
fi

# Incompatible changes detected: an empty exceptions list is always a failure;
# a non-empty list must cover every reported breaking (path, status) change.
verify_exit=0
bash "$ROOT_DIR/.ci/verify-openapi-exceptions.sh" \
  "$WORK_DIR/openapi-diff-report.txt" "$ROOT_DIR/.ci/openapi-compat-allowlist.yml" \
  || verify_exit=$?

cat "$WORK_DIR/openapi-diff-report.txt"

if [[ "$verify_exit" -eq 0 ]]; then
  echo "OpenAPI incompatible changes detected; all covered by documented exceptions in .ci/openapi-compat-allowlist.yml."
  exit 0
fi
echo "OpenAPI incompatible changes detected and not fully covered by documented exceptions. Fix .ci/openapi-compat-allowlist.yml before merge."
exit 1
