#!/usr/bin/env python3
"""Export discovered recipes with source-artifact and license attribution."""
from __future__ import annotations

import argparse
import csv
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

RECIPE_LICENSES = {
    "rewrite-all": "Apache-2.0",
    "rewrite-third-party": "Apache-2.0",
    "rewrite-java-dependencies": "Apache-2.0",
    "rewrite-jackson": "Apache-2.0",
    "rewrite-liberty": "Apache-2.0",
    "rewrite-micronaut": "Apache-2.0",
    "rewrite-netty": "Apache-2.0",
    "rewrite-openapi": "Apache-2.0",
    "rewrite-quarkus": "Apache-2.0",
    "rewrite-apache": "Moderne Source Available",
    "rewrite-cucumber-jvm": "Moderne Source Available",
    "rewrite-feature-flags": "Moderne Source Available",
    "rewrite-github-actions": "Moderne Source Available",
    "rewrite-gitlab": "Moderne Source Available",
    "rewrite-hibernate": "Moderne Source Available",
    "rewrite-jenkins": "Moderne Source Available",
    "rewrite-joda": "Moderne Source Available",
    "rewrite-logging-frameworks": "Moderne Source Available",
    "rewrite-micrometer": "Moderne Source Available",
    "rewrite-migrate-java": "Moderne Source Available",
    "rewrite-okhttp": "Moderne Source Available",
    "rewrite-pmd": "Moderne Source Available",
    "rewrite-prethink": "Moderne Source Available",
    "rewrite-rewrite": "Moderne Source Available",
    "rewrite-spring": "Moderne Source Available",
    "rewrite-spring-to-quarkus": "Moderne Source Available",
    "rewrite-static-analysis": "Moderne Source Available",
    "rewrite-testing-frameworks": "Moderne Source Available",
}
CORE_SOURCE_AVAILABLE = {"rewrite-android", "rewrite-python", "rewrite-ruby", "rewrite-scala", "rewrite-mainframe"}
CORE_APACHE = {
    "rewrite-core", "rewrite-java", "rewrite-java-8", "rewrite-java-11", "rewrite-java-17",
    "rewrite-java-21", "rewrite-java-25", "rewrite-maven", "rewrite-gradle", "rewrite-groovy",
    "rewrite-hcl", "rewrite-json", "rewrite-kotlin", "rewrite-properties", "rewrite-protobuf",
    "rewrite-templating", "rewrite-toml", "rewrite-xml", "rewrite-yaml", "rewrite-docker",
}
RELEVANCE = {
    "rewrite-java-dependencies": "Maven dependency inventory/resolution",
    "rewrite-jackson": "Jackson serialization/data compatibility",
    "rewrite-netty": "Netty networking",
    "rewrite-openapi": "OpenAPI contract maintenance",
    "rewrite-docker": "Container/Docker maintenance",
    "rewrite-spring": "Spring Framework/Boot migrations",
    "rewrite-static-analysis": "Java static analysis",
    "rewrite-testing-frameworks": "JUnit/Mockito/testing",
    "rewrite-migrate-java": "Java migration and language APIs",
    "rewrite-logging-frameworks": "Logging",
    "rewrite-micrometer": "Metrics/observability",
    "rewrite-github-actions": "GitHub Actions workflows",
    "rewrite-hibernate": "Hibernate/JPA if present",
    "rewrite-all": "Broad aggregate",
    "rewrite-third-party": "Third-party library migrations",
}

def parse_discovered(line: str) -> str | None:
    if not line.startswith("[INFO]"):
        return None
    rest = line[len("[INFO]"):]
    name = rest.strip()
    if not name.startswith(("org.openrewrite.", "io.moderne.", "com.marketplace.")):
        return None
    if any(ch.isspace() for ch in name) or not all(ch.isalnum() or ch in "._$" for ch in name):
        return None
    if len(rest) - len(rest.lstrip()) < 9:
        return None
    return name

def display_line(line: str) -> str | None:
    if not line.startswith("[INFO]"):
        return None
    rest = line[len("[INFO]"):]
    shown = rest.strip()
    if len(rest) - len(rest.lstrip()) != 5 or not shown:
        return None
    if shown.startswith(("options:", "recipeList:", "Available Recipes:")):
        return None
    return shown

def descriptor_names(content: str):
    for line in content.splitlines():
        item = line.strip()
        if not item.startswith("name:"):
            continue
        name = item.partition(":")[2].strip()
        if len(name) >= 2 and ((name[0] == '"' and name[-1] == '"') or (name[0] == "'" and name[-1] == "'")):
            name = name[1:-1].strip()
        if name.startswith(("org.openrewrite.", "io.moderne.", "com.marketplace.")) and all(ch.isalnum() or ch in "._$" for ch in name):
            yield name

def license_of(group: str, artifact: str) -> str:
    if group == "org.openrewrite.recipe":
        return RECIPE_LICENSES.get(artifact, "UNKNOWN")
    if group == "org.openrewrite":
        if artifact in CORE_SOURCE_AVAILABLE:
            return "Moderne Source Available"
        if artifact in CORE_APACHE or artifact in {"rewrite-bom", "rewrite-gradle-plugin", "rewrite-maven-plugin"}:
            return "Apache-2.0"
        return "UNKNOWN"
    if group == "org.openrewrite.meta":
        return "Apache-2.0" if artifact == "rewrite-analysis" else "UNKNOWN"
    if group == "io.moderne":
        return "Moderne Source Available"
    return "UNKNOWN"

def coordinate(group: str, artifact: str, version: str) -> str:
    return f"{group}:{artifact}:{version}"

def collect_artifact_recipes(maven_repo: Path):
    by_recipe = defaultdict(set)
    if not maven_repo.is_dir():
        raise SystemExit(f"Maven repository does not exist: {maven_repo}")
    for jar in maven_repo.rglob("*.jar"):
        try:
            rel = jar.relative_to(maven_repo)
            if len(rel.parts) < 4:
                continue
            artifact, version = rel.parts[-3], rel.parts[-2]
            group = ".".join(rel.parts[:-3])
            if not (group.startswith("org.openrewrite") or group.startswith("io.moderne")):
                continue
            with zipfile.ZipFile(jar) as archive:
                for item in archive.namelist():
                    if not item.startswith("META-INF/rewrite/") or not item.lower().endswith((".yml", ".yaml")):
                        continue
                    try:
                        content = archive.read(item).decode("utf-8", errors="replace")
                    except Exception:
                        continue
                    for recipe in descriptor_names(content):
                        by_recipe[recipe].add((group, artifact, version, license_of(group, artifact)))
        except (OSError, zipfile.BadZipFile, ValueError):
            continue
    return by_recipe

def package_fallback(name: str):
    known = [
        ("org.openrewrite.docker.", "org.openrewrite", "rewrite-docker"),
        ("org.openrewrite.java.dependencies.", "org.openrewrite.recipe", "rewrite-java-dependencies"),
        ("org.openrewrite.java.jackson.", "org.openrewrite.recipe", "rewrite-jackson"),
        ("org.openrewrite.java.netty.", "org.openrewrite.recipe", "rewrite-netty"),
        ("org.openrewrite.openapi.", "org.openrewrite.recipe", "rewrite-openapi"),
        ("org.openrewrite.java.spring.", "org.openrewrite.recipe", "rewrite-spring"),
        ("org.openrewrite.java.logging.", "org.openrewrite.recipe", "rewrite-logging-frameworks"),
        ("org.openrewrite.java.hibernate.", "org.openrewrite.recipe", "rewrite-hibernate"),
        ("org.openrewrite.java.joda.", "org.openrewrite.recipe", "rewrite-joda"),
        ("org.openrewrite.java.migrate.", "org.openrewrite.recipe", "rewrite-migrate-java"),
        ("org.openrewrite.java.testing.", "org.openrewrite.recipe", "rewrite-testing-frameworks"),
        ("org.openrewrite.java.micrometer.", "org.openrewrite.recipe", "rewrite-micrometer"),
        ("org.openrewrite.java.okhttp.", "org.openrewrite.recipe", "rewrite-okhttp"),
        ("org.openrewrite.java.pmd.", "org.openrewrite.recipe", "rewrite-pmd"),
        ("org.openrewrite.jenkins.", "org.openrewrite.recipe", "rewrite-jenkins"),
        ("org.openrewrite.github.actions.", "org.openrewrite.recipe", "rewrite-github-actions"),
        ("org.openrewrite.gitlab.", "org.openrewrite.recipe", "rewrite-gitlab"),
        ("org.openrewrite.java.", "org.openrewrite", "rewrite-java"),
        ("org.openrewrite.maven.", "org.openrewrite", "rewrite-maven"),
        ("org.openrewrite.gradle.", "org.openrewrite", "rewrite-gradle"),
    ]
    for prefix, group, artifact in known:
        if name.startswith(prefix):
            return group, artifact
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--maven-repo", type=Path, default=Path.home() / ".m2" / "repository")
    ap.add_argument("--source", action="store_true")
    ap.add_argument("--strict-attribution", action="store_true")
    args = ap.parse_args()
    if not args.input.is_file():
        raise SystemExit(f"recipe discovery log not found: {args.input}")
    discovered = {}
    display = ""
    for line in args.input.read_text(encoding="utf-8", errors="replace").splitlines():
        recipe = parse_discovered(line)
        if recipe:
            discovered[recipe] = display
            display = ""
        else:
            shown = display_line(line)
            if shown:
                display = shown

    declared = collect_artifact_recipes(args.maven_repo)
    output, unresolved = [], []
    for recipe in sorted(discovered):
        if recipe.startswith("com.marketplace."):
            output.append([recipe, discovered[recipe], "project source", "Internal", "Project recipe", "Platform governance", "project YAML"])
            continue
        candidates = declared.get(recipe, set())
        if len(candidates) > 1:
            specific = {c for c in candidates if c[1] not in {"rewrite-all", "rewrite-third-party"}}
            if specific:
                candidates = specific
        licenses = {lic for _, _, _, lic in candidates}
        if len(licenses) == 1 and "UNKNOWN" not in licenses:
            license_name = next(iter(licenses))
            attribution = "artifact descriptor"
        elif candidates and all(lic == "UNKNOWN" for _, _, _, lic in candidates):
            license_name = "UNKNOWN"
            attribution = "artifact found; license table missing"
        elif candidates:
            license_name = "MIXED / REVIEW"
            attribution = "multiple source artifacts with different licenses"
        else:
            license_name = "UNKNOWN"
            attribution = "no matching META-INF/rewrite descriptor"
        coords = sorted({(g, a, v) for g, a, v, _ in candidates})
        source_coords = ";".join(coordinate(g, a, v) for g, a, v in coords)
        relevance = "General Java/build maintenance"
        for _, artifact, _, _ in candidates:
            if artifact in RELEVANCE:
                relevance = RELEVANCE[artifact]
                break
        if not candidates:
            fallback = package_fallback(recipe)
            if fallback:
                group, artifact = fallback
                license_name = license_of(group, artifact)
                source_coords = f"{group}:{artifact} (package fallback)"
                attribution = "package fallback only; source descriptor not found"
                relevance = RELEVANCE.get(artifact, "Java/build maintenance")
        if license_name in {"UNKNOWN", "MIXED / REVIEW"} or "package fallback only" in attribution:
            unresolved.append((recipe, license_name, attribution))
        availability = ("Source Available" if license_name == "Moderne Source Available"
                        else "Apache downloadable" if license_name == "Apache-2.0"
                        else "Review")
        output.append([recipe, discovered[recipe], source_coords, license_name, availability, relevance, attribution])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["recipe_id", "display_name", "source_artifact_coordinates", "license", "availability", "platform_relevance", "attribution_evidence"])
        writer.writerows(output)
    print(f"Exported {len(output)} unique discovered recipe IDs; {len(unresolved)} require source/license attribution review.")
    if unresolved:
        print("Attribution gaps (first 40):")
        for recipe, license_name, why in unresolved[:40]:
            print(f"- {recipe}: {license_name} ({why})")
    if args.strict_attribution and unresolved:
        raise SystemExit(2)

if __name__ == "__main__":
    main()
