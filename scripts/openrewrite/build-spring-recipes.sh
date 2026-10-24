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

# Determine the actual Maven coordinate produced by this source tag. The upstream
# build can publish a development version (for example 6.42.0-SNAPSHOT) rather than
# the release number embedded in the git tag, so do not print a guessed coordinate.
built_version="$(
  cd "${source_dir}"
  ./gradlew --no-daemon -q properties | sed -n 's/^version: //p' | tail -n 1
)"
if [[ -z "${built_version}" ]]; then
  echo "Unable to determine the Maven version produced by the pinned Spring source." >&2
  exit 4
fi

(
  cd "${source_dir}"
  ./gradlew --no-daemon --stacktrace publishToMavenLocal
)

artifact_pom="${HOME}/.m2/repository/org/openrewrite/recipe/rewrite-spring/${built_version}/rewrite-spring-${built_version}.pom"
if [[ ! -s "${artifact_pom}" ]]; then
  echo "Expected locally published POM was not found: ${artifact_pom}" >&2
  exit 5
fi

if [[ "${built_version}" != "${version}" ]]; then
  echo "::warning::Source tag ${tag} is publishing rewrite-spring:${built_version}, not the expected release coordinate ${version}. Keep the source-built profile disabled until version alignment and transitive artifact access are resolved."
fi

echo "Published source-built org.openrewrite.recipe:rewrite-spring:${built_version} from tag ${tag} (expected release coordinate: ${version})."
