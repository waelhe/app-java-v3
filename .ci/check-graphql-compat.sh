#!/usr/bin/env bash
set -euo pipefail

# GraphQL schema compatibility gate — the GraphQL twin of
# .ci/check-openapi-compat.sh (the same design: baseline-first on a fresh
# database, the release tag's OWN runtime schema vs the current tree's
# runtime schema, breaking changes fail unless documented in the allowlist).
#
# SDL generation is SYMMETRIC and owned by this script alone: the same
# injected, self-contained JUnit dump test (below, DUMP_TEST_HEREDOC) is
# written into BOTH trees and executed with the same Maven invocation and
# the same CI-provided service containers — the printed SDL comes from
# graphql-java's GraphQLSchemaPrinter over the booted GraphQlSource in
# each tree, so generated types (the Connection family), applied
# type-definition configurers and runtime wiring are all part of the
# compared artifact. The dump test never enters either tree's history.
#
# Bootstrap semantics (honest, same spirit as the OpenAPI gate's no-tag
# skip): if the baseline tag's tree cannot run the dump (no GraphQL
# surface / does not compile / does not boot), there is no baseline to
# compare against yet — the gate passes with a NOTICE, and the first
# release tag whose tree carries this gate's expectations becomes the
# real baseline. The current side MUST always dump; a current-side
# failure is a gate failure.
#
# Environment (mirrors the OpenAPI gate job):
#   SPRING_DATASOURCE_URL / USERNAME / PASSWORD, SPRING_DATA_REDIS_* —
#   the CI service containers.
#   GRAPHQL_BASELINE_TAG — optional tag override (default: newest tag).

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK_DIR="$ROOT_DIR/.ci/graphql-compat"
DUMP_FILE="marketplace-app/target/graphql-schema.graphqls"
DUMP_CLASS_DIR="marketplace-app/src/test/java/com/marketplace/app/graphql"
DUMP_CLASS="GraphQlSchemaDumpGate.java"
mkdir -p "$WORK_DIR"

LATEST_TAG="${GRAPHQL_BASELINE_TAG:-$(git tag --sort=-creatordate | head -n1)}"
CURRENT_SHA="$(git rev-parse HEAD)"

if [[ -z "$LATEST_TAG" ]]; then
  echo "No release tag found. Skipping GraphQL schema compatibility gate."
  exit 0
fi
echo "Using GraphQL schema baseline tag: $LATEST_TAG"

# The self-contained dump test — compiled into whichever tree is checked
# out. It uses only APIs present since spring-graphql 1.0 (GraphQlSource
# bean + GraphQLSchemaPrinter), boots the plain test profile against the
# CI-provided services, and writes the deterministic SDL print.
write_dump_test() {
  mkdir -p "$DUMP_CLASS_DIR"
  cat > "$DUMP_CLASS_DIR/$DUMP_CLASS" <<'EOF'
package com.marketplace.app.graphql;

import java.nio.file.Files;
import java.nio.file.Path;
import graphql.schema.GraphQLSchemaPrinter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class GraphQlSchemaDumpGate {

    @Autowired
    private GraphQlSource graphQlSource;

    @Test
    void dumpRuntimeSchema() throws Exception {
        String sdl = new GraphQLSchemaPrinter().print(graphQlSource.schema());
        Path out = Path.of("target", "graphql-schema.graphqls");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sdl);
    }
}
EOF
}

remove_dump_test() {
  rm -f "$DUMP_CLASS_DIR/$DUMP_CLASS"
}

# ------------------------------------------------------------- baseline phase
git checkout -q "$LATEST_TAG"
write_dump_test
# `clean` is load-bearing (the OpenAPI gate's measured lesson): the two
# phases build different trees through the same target/ — a stale
# incremental copy would ship the previous phase's classes.
if ! ./mvnw -U -q -pl marketplace-app -am clean test -Dtest=GraphQlSchemaDumpGate \
      -Dsurefire.failIfNoSpecifiedTests=false -DskipITs \
      > "$WORK_DIR/baseline-maven.log" 2>&1; then
  remove_dump_test
  git checkout -q "$CURRENT_SHA"
  echo "NOTICE: baseline tag $LATEST_TAG cannot run the schema dump (no GraphQL surface or boot failure) — no baseline to compare against yet. Gate passes; see .ci/check-graphql-compat.sh header for the bootstrap semantics."
  exit 0
fi
if [[ ! -f "$DUMP_FILE" ]]; then
  remove_dump_test
  git checkout -q "$CURRENT_SHA"
  echo "NOTICE: baseline tag $LATEST_TAG produced no schema dump — no baseline yet. Gate passes (bootstrap semantics)."
  exit 0
fi
cp "$DUMP_FILE" "$WORK_DIR/baseline-schema.graphqls"
remove_dump_test

# ------------------------------------------------------------- current phase
git checkout -q "$CURRENT_SHA"
write_dump_test
./mvnw -U -q -pl marketplace-app -am clean test -Dtest=GraphQlSchemaDumpGate \
  -Dsurefire.failIfNoSpecifiedTests=false -DskipITs \
  > "$WORK_DIR/current-maven.log" 2>&1 || {
    remove_dump_test
    echo "Current tree failed to produce the schema dump — see $WORK_DIR/current-maven.log"
    exit 1
}
cp "$DUMP_FILE" "$WORK_DIR/current-schema.graphqls"
remove_dump_test

# ------------------------------------------------------------- classification
exit_code=0
python3 "$ROOT_DIR/.ci/graphql-compat-classifier.py" \
  "$WORK_DIR/baseline-schema.graphqls" \
  "$WORK_DIR/current-schema.graphqls" \
  "$ROOT_DIR/.ci/graphql-compat-allowlist.yml" || exit_code=$?

if [[ "$exit_code" -ne 0 ]]; then
  echo
  echo "=== baseline SDL ==="; cat "$WORK_DIR/baseline-schema.graphqls"
  echo "=== current SDL ==="; cat "$WORK_DIR/current-schema.graphqls"
fi
exit "$exit_code"
