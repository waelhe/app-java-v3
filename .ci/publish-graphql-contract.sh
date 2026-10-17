#!/usr/bin/env bash
set -euo pipefail

# Publishes the ACCEPTED runtime SDL to the `graphql-contract` distribution
# branch — Phase 5 item 3 (docs/api/graphql-target-architecture.md
# §11-Phase-5). The SDL argument comes from a CI run's graphql-schema-gate
# dump (graphql-java's SchemaPrinter over the booted GraphQlSource) — the
# same artifact that gate compared against the release-tag baseline — so
# what is published is exactly what CI booted and gated: never the
# production server, never a hand-copied file, never a freshly imagined
# schema.
#
# The branch is a distribution channel in the gh-pages spirit: written only
# by this script (invoked from .github/workflows/graphql-contract.yml),
# outside the PR flow, and carrying exactly three files:
#   schema.graphqls — the accepted SDL (version N)
#   manifest.json   — { version, publishedAt, sourceCommit, sourceRun,
#                      schemaSha256 }; the version advances ONLY when the
#                      SDL actually changes
#   README.md       — what this branch is and how to consume it
# The branch's git history is the contract's versioned changelog: every
# version is a commit, reproducible from CI artifacts (the run's
# graphql-runtime-schema artifact) and from the recorded source commit.
#
# Idempotent: when the incoming SDL is byte-identical to the published one,
# the script exits 0 without pushing — a no-op publication is a success,
# not an error (every green CI run on main ends here quietly).
#
# Environment:
#   SOURCE_SHA — the main commit the SDL was dumped from (required)
#   SOURCE_RUN — the CI run URL that produced the dump (required)
#
# The push uses the checkout-persisted credential header (see
# .github/workflows/graphql-contract.yml): no token ever appears in a URL,
# and the git-plumbing commit (blob -> tree -> commit -> ref) never checks
# out or mutates any worktree.

SDL_FILE="${1:?usage: publish-graphql-contract.sh <runtime-schema.graphqls>}"
: "${SOURCE_SHA:?SOURCE_SHA is required}"
: "${SOURCE_RUN:?SOURCE_RUN is required}"

BRANCH="graphql-contract"
WORK_DIR=".ci/graphql-contract-publish"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

if [[ ! -f "$SDL_FILE" ]]; then
  echo "FAIL: runtime schema dump not found at $SDL_FILE"
  exit 1
fi

# The commit identity for the machine-written distribution history.
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

# The published contract, if the channel exists yet (bootstrap: first ever
# run creates the branch as an orphan root).
PUBLISHED_SCHEMA="$WORK_DIR/published-schema.graphqls"
PUBLISHED_MANIFEST="$WORK_DIR/published-manifest.json"
BRANCH_EXISTS=0
if git fetch origin "$BRANCH" --depth=1 2>/dev/null; then
  if git rev-parse --verify "origin/$BRANCH" >/dev/null 2>&1; then
    BRANCH_EXISTS=1
    git show "origin/$BRANCH:schema.graphqls" > "$PUBLISHED_SCHEMA"
    git show "origin/$BRANCH:manifest.json" > "$PUBLISHED_MANIFEST"
  fi
fi

if [[ "$BRANCH_EXISTS" -eq 1 ]] && cmp -s "$SDL_FILE" "$PUBLISHED_SCHEMA"; then
  PUBLISHED_VERSION=$(python3 -c "import json; print(json.load(open('$PUBLISHED_MANIFEST'))['version'])")
  echo "GraphQL contract unchanged — published version $PUBLISHED_VERSION already matches this run's runtime SDL. Nothing to publish."
  exit 0
fi

if [[ "$BRANCH_EXISTS" -eq 1 ]]; then
  NEXT_VERSION=$(( $(python3 -c "import json; print(json.load(open('$PUBLISHED_MANIFEST'))['version'])") + 1 ))
else
  NEXT_VERSION=1
fi

SCHEMA_SHA256=$(sha256sum "$SDL_FILE" | cut -d' ' -f1)
PUBLISHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

cp "$SDL_FILE" "$WORK_DIR/schema.graphqls"
cat > "$WORK_DIR/manifest.json" <<EOF
{
  "version": $NEXT_VERSION,
  "publishedAt": "$PUBLISHED_AT",
  "sourceCommit": "$SOURCE_SHA",
  "sourceRun": "$SOURCE_RUN",
  "schemaSha256": "$SCHEMA_SHA256"
}
EOF
cat > "$WORK_DIR/README.md" <<'EOF'
# GraphQL contract distribution branch

This branch is the published GraphQL contract channel (Phase 5 of
docs/api/graphql-target-architecture.md). It is written ONLY by
.ci/publish-graphql-contract.sh, invoked by
.github/workflows/graphql-contract.yml, which publishes the runtime SDL
dumped and gated by a successful CI run on main — never the production
server, never a hand-copied file. Do not open pull requests against this
branch and do not hand-edit it; the next publication would overwrite the
hand edit and the schema-compatibility gate owns the contract anyway.

Files:
- schema.graphqls — the accepted SDL (the version this manifest records).
- manifest.json   — version, publishedAt, sourceCommit, sourceRun,
  schemaSha256. The version advances only when the SDL actually changes.
- The git history of this branch is the contract's versioned changelog:
  every version is a commit whose parent is the previous version.

Consumers:
- Android fetches the SDL at
  https://raw.githubusercontent.com/waelhe/app-java-v3/graphql-contract/schema.graphqls
  (see android/README.md — GraphQL contract consumption). The file is
  staged by CI before Gradle runs; it is gitignored in the Android tree.
- CI's android-graphql job validates the Apollo Kotlin operations against
  the run's own runtime-schema dump (the same publication this branch
  advances), so an incompatible schema change or a stale operation fails
  the required "Build & Test (JDK 25)" check before release.
EOF

# git plumbing (blobs -> tree -> commit -> ref): no worktree checkout of
# the distribution branch, no index side effects, no merge machinery.
SCHEMA_BLOB=$(git hash-object -w "$WORK_DIR/schema.graphqls")
MANIFEST_BLOB=$(git hash-object -w "$WORK_DIR/manifest.json")
README_BLOB=$(git hash-object -w "$WORK_DIR/README.md")
TREE=$(printf '100644 blob %s\tREADME.md\n100644 blob %s\tmanifest.json\n100644 blob %s\tschema.graphqls\n' \
  "$README_BLOB" "$MANIFEST_BLOB" "$SCHEMA_BLOB" | git mktree)

PARENT_ARGS=()
if [[ "$BRANCH_EXISTS" -eq 1 ]]; then
  PARENT_ARGS=(-p "$(git rev-parse "origin/$BRANCH")")
fi
COMMIT=$(git commit-tree "$TREE" "${PARENT_ARGS[@]}" -m "GraphQL contract v$NEXT_VERSION from $SOURCE_SHA")

git push origin "$COMMIT:refs/heads/$BRANCH"
echo "Published GraphQL contract v$NEXT_VERSION (commit $COMMIT) from $SOURCE_SHA."
