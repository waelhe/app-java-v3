#!/usr/bin/env bash
# Build the OpenRewrite Source Available recipe closure from pinned upstream tags.
# All resulting artifacts remain in the ephemeral runner's local Maven repository.
set -euo pipefail

: "${CODEGENOME_USERNAME:?CODEGENOME_USERNAME is required to resolve upstream OpenRewrite build plugins}"
: "${CODEGENOME_TOKEN:?CODEGENOME_TOKEN is required to resolve upstream OpenRewrite build plugins}"

SOURCE_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/openrewrite-source-build"
MANIFEST="${OPENREWRITE_SOURCE_MANIFEST_PATH:-${GITHUB_WORKSPACE:-.}/target/rewrite/source-build-manifest.txt}"
mkdir -p "$SOURCE_ROOT" "$(dirname "$MANIFEST")"
: > "$MANIFEST"

INIT_SCRIPT="$SOURCE_ROOT/maven-local-first.init.gradle"
cat > "$INIT_SCRIPT" <<'GRADLE'
allprojects {
    repositories {
        // Added before each project's build script so local source-built artifacts resolve first.
        mavenLocal()
    }
}
GRADLE

build_and_publish_from() {
    local org="$1"
    local repo="$2"
    local version="$3"
    local ref="v${version}"
    local checkout="${SOURCE_ROOT}/${org}-${repo}"
    echo "::group::Build official source $org/$repo $ref"
    git clone --depth=1 --single-branch --branch "$ref" "https://github.com/$org/$repo.git" "$checkout"
    printf '%s %s %s\n' "$org/$repo" "$ref" "$(git -C "$checkout" rev-parse HEAD)" | tee -a "$MANIFEST"
    (
        cd "$checkout"
        if [[ "$repo" == "rewrite-mainframe" ]]; then
            # This upstream project registers ANTLR generation tasks for its COBOL/JCL/etc grammars.
            # They are not wired as compileJava dependencies in the tagged source, so run them first;
            # otherwise checked-in generated parser classes can be stale relative to the grammar.
            ./gradlew --no-daemon --no-configuration-cache --max-workers=4 \
                -Preleasing -I "$INIT_SCRIPT" -x test -x check \
                generateAntlrSourcesCobol generateAntlrSourcesJcl generateAntlrSourcesBms \
                generateAntlrSourcesIms generateAntlrSourcesDb2 generateAntlrSourcesBind \
                generateAntlrSourcesLinkEdit generateAntlrSourcesSort generateAntlrSourcesIdcams \
                generateAntlrSourcesUtility generateAntlrSourcesControlM
        fi
        ./gradlew --no-daemon --no-configuration-cache --max-workers=4 \
            -Preleasing -I "$INIT_SCRIPT" -x test -x check publishToMavenLocal
    )
    echo "Published $org/$repo $ref to the runner's local Maven repository"
    echo "::endgroup::"
}
build_and_publish() { build_and_publish_from openrewrite "$1" "$2"; }

# The official core build includes Java 8, 11, 17, 21, and 25 parser modules.
build_and_publish rewrite 8.93.0
build_and_publish_from moderneinc rewrite-mainframe 3.2.1

# Source Available recipe packs, in dependency order; versions align with recipe BOM 3.41.0.
build_and_publish rewrite-static-analysis 2.45.0
build_and_publish rewrite-feature-flags 1.24.2
build_and_publish rewrite-joda 0.12.0
build_and_publish rewrite-jenkins 0.38.2
build_and_publish rewrite-github-actions 3.33.0
build_and_publish rewrite-migrate-java 3.46.0
build_and_publish rewrite-logging-frameworks 3.34.1
build_and_publish rewrite-apache 2.34.0
build_and_publish rewrite-hibernate 2.27.0
build_and_publish rewrite-micrometer 0.31.2
build_and_publish rewrite-cucumber-jvm 2.20.0
build_and_publish rewrite-testing-frameworks 3.49.0
build_and_publish rewrite-okhttp 0.25.2
build_and_publish rewrite-spring 6.41.0

# Apache-licensed aggregates are attempted only after their restricted closures were source-built.
build_and_publish rewrite-third-party 0.49.0
build_and_publish rewrite-all 1.30.2

echo "Source build manifest: $MANIFEST"
