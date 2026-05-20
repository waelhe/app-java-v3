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

  ./mvnw -q -pl marketplace-app -am -DskipTests package
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

# current
cd "$ROOT_DIR"
generate_spec "current" "$WORK_DIR/current-openapi.json"

# baseline tag
BASELINE_BRANCH="openapi-baseline-$LATEST_TAG"
git checkout -q "$LATEST_TAG"
generate_spec "baseline" "$WORK_DIR/baseline-openapi.json"
git checkout -q "$CURRENT_SHA"

# Breaking-change gate: endpoint deletions, schema narrowing, status/media type changes.
docker run --rm -t -v "$WORK_DIR:/spec" openapitools/openapi-diff:latest \
  /spec/baseline-openapi.json /spec/current-openapi.json \
  --fail-on-incompatible || {
    if grep -Eq '^exceptions:\s*\[\s*\]\s*$' "$ROOT_DIR/.ci/openapi-compat-allowlist.yml"; then
      echo "OpenAPI incompatible changes detected and no allowlist exceptions are documented."
      exit 1
    fi
    echo "OpenAPI incompatible changes detected. Validate documented exceptions in .ci/openapi-compat-allowlist.yml before merge."
    exit 1
  }

echo "OpenAPI backward compatibility gate passed."
