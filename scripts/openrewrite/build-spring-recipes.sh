#!/usr/bin/env bash
set -Eeuo pipefail

# Build the source-available Spring recipes locally instead of requesting the
# subscription-gated precompiled rewrite-spring artifact.
readonly version="6.41.0"
readonly tag="v${version}"
readonly expected_commit="bb5d5af152d1f92c3cc49f8a55ddbaf2b35247fc"
readonly source_url="https://github.com/openrewrite/rewrite-spring.git"
readonly work_root="${TMPDIR:-/tmp}/openrewrite-source-build"
readonly source_dir="${work_root}/rewrite-spring-${version}"

command -v git >/dev/null || { echo "git is required" >&2; exit 2; }
command -v java >/dev/null || { echo "Java is required" >&2; exit 2; }

mkdir -p "${work_root}"
rm -rf "${source_dir}"
git clone --depth 1 --branch "${tag}" "${source_url}" "${source_dir}"

actual_commit="$(git -C "${source_dir}" rev-parse HEAD)"
if [[ "${actual_commit}" != "${expected_commit}" ]]; then
  echo "Refusing unexpected upstream source: expected ${expected_commit}, got ${actual_commit}" >&2
  exit 3
fi

# Upstream's settings.gradle.kts reads these optional environment variables.
# They authenticate repository resolution only; do not put credentials in source.
if [[ -n "${CODEGENOME_USERNAME:-}" && -n "${CODEGENOME_TOKEN:-}" ]]; then
  export CODEGENOME_USERNAME CODEGENOME_TOKEN
fi

(
  cd "${source_dir}"
  ./gradlew --no-daemon --stacktrace publishToMavenLocal
)

echo "Installed source-built org.openrewrite.recipe:rewrite-spring:${version} into the local Maven repository."
