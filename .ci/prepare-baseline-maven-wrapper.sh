#!/usr/bin/env bash
# Repoints historical Maven 3 wrapper distributions to Apache's official
# archive when the baseline tag still references Maven Central's distribution
# path. Some older Maven binary archives are published only from Apache's
# archive; the Maven version itself is not changed.
prepare_baseline_maven_wrapper() {
  local properties_file="${1:?maven-wrapper.properties path is required}"
  [[ -f "$properties_file" ]] || return 0

  local distribution_url
  distribution_url="$(sed -n 's/^distributionUrl=//p' "$properties_file" | head -n 1)"
  [[ -n "$distribution_url" ]] || return 0

  if [[ "$distribution_url" =~ ^https://repo\.maven\.apache\.org/maven2/org/apache/maven/apache-maven/([0-9]+\.[0-9]+\.[0-9]+)/ ]]; then
    local version="${BASH_REMATCH[1]}"
    local central_url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${version}/apache-maven-${version}-bin.zip"
    if [[ "$distribution_url" == "$central_url" ]]; then
      local archive_url="https://archive.apache.org/dist/maven/maven-3/${version}/binaries/apache-maven-${version}-bin.zip"
      sed -i "s|^distributionUrl=.*$|distributionUrl=${archive_url}|" "$properties_file"
      echo "Using Apache's official Maven ${version} archive for baseline wrapper bootstrap."
    fi
  fi
}
