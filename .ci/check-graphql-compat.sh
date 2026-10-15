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
# the same CI-provided service containers, booting each tree's OWN
# default profile (the twin gate's java -jar boot semantics) — the printed
# SDL comes from graphql-java's graphql.schema.idl.SchemaPrinter over the
# booted GraphQlSource in each tree, so generated types (the Connection
# family), applied type-definition configurers and runtime wiring are all
# part of the compared artifact. The dump test never enters either
# tree's history.
#
# Bootstrap semantics (honest, same spirit as the OpenAPI gate's no-tag
# skip — but decided from the tag's OWN tree, never from a run failure):
# the gate skips ONLY when the baseline tag carries no GraphQL surface at
# all — no src/main/resources/graphql/*.graphqls file anywhere in the
# tag's tree, so there is no schema to compare against yet. A tag that
# HAS a GraphQL surface must produce its dump: a baseline-side dump
# failure (compile error, boot failure, dependency download failure under
# -U, an unavailable database) FAILS the gate — a missing-baseline skip
# must never mask a broken baseline. The current side MUST always dump;
# a current-side failure is a gate failure.
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

# The tag's own surface inventory, measured from the tag object itself
# before any checkout: a tree without src/main/resources/graphql/*.graphqls
# has no GraphQL schema to baseline against — the one true bootstrap case.
# Everything else (a surface-carrying tag whose dump fails) is a broken
# baseline and fails the gate below.
TAG_TREE_FILES="$(git ls-tree -r --name-only "$LATEST_TAG")"
if [[ "$TAG_TREE_FILES" != *"src/main/resources/graphql/"*".graphqls"* ]]; then
  echo "NOTICE: baseline tag $LATEST_TAG carries no GraphQL schema (no src/main/resources/graphql/*.graphqls in its tree) — no baseline to compare against yet. Gate passes; see .ci/check-graphql-compat.sh header for the bootstrap semantics."
  exit 0
fi

# The self-contained dump test — compiled into whichever tree is checked
# out. It uses only framework APIs that exist across every version in
# play (the GraphQlSource bean since spring-graphql 1.0; the SDL printer
# of record is graphql.schema.idl.SchemaPrinter — present in graphql-java
# across the whole resolved range, and verified in the 25.0 artifact the
# Boot 4.1.1 BOM resolves; note graphql.schema.GraphQLSchemaPrinter does
# NOT exist in graphql-java — an injected test that imports it cannot
# compile, and a non-compiling baseline used to pass the gate through the
# old run-failure skip). The test boots the application's OWN default
# profile — the exact boot semantics of the OpenAPI twin's java -jar leg,
# against the same CI-provided service containers via the step's env
# vars — and writes the deterministic SDL print. The TEST profile is
# deliberately NOT used (measured on v0.1.0): the test profile is a
# tree-specific moving target — the tag's context fails to satisfy a
# SecurityConfig dependency (AuthorizationServerSettings) that later
# trees' test arrangements provide, while the tag's own default-profile
# boot is exactly what runs in production and in the twin gate, and is
# proven green on the same tag. The boot is FULL (a live web server on a
# random port, runners and all) — the twin's java -jar semantics exactly,
# not a stripped-down context.
write_dump_test() {
  mkdir -p "$DUMP_CLASS_DIR"
  cat > "$DUMP_CLASS_DIR/$DUMP_CLASS" <<'EOF'
package com.marketplace.app.graphql;

import java.nio.file.Files;
import java.nio.file.Path;
import graphql.schema.idl.SchemaPrinter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.GraphQlSource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphQlSchemaDumpGate {

    @Autowired
    private GraphQlSource graphQlSource;

    @Test
    void dumpRuntimeSchema() throws Exception {
        String sdl = new SchemaPrinter().print(graphQlSource.schema());
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
  echo "FAIL: baseline tag $LATEST_TAG carries a GraphQL schema but its dump run failed — the baseline is broken, not absent. See .ci/graphql-compat/baseline-maven.log (uploaded as a workflow artifact on failure)."
  exit 1
fi
if [[ ! -f "$DUMP_FILE" ]]; then
  remove_dump_test
  git checkout -q "$CURRENT_SHA"
  echo "FAIL: baseline tag $LATEST_TAG produced no schema dump despite carrying a GraphQL schema. See .ci/graphql-compat/baseline-maven.log."
  exit 1
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
