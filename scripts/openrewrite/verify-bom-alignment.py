#!/usr/bin/env python3
"""Fail-closed validation of explicit Maven plugin recipe versions against the published BOM."""

import argparse
import sys
import xml.etree.ElementTree as ET

NS_URI = "http://maven.apache.org/POM/4.0.0"
NS = {"m": NS_URI}

# Maven cannot use a BOM to manage plugin dependencies, so root plugin pins remain explicit.
# This check mechanically compares those pins with the published BOM's effective versions.
EXPECTED = {
    ("org.openrewrite.recipe", "rewrite-java-dependencies"): "openrewrite.java-dependencies.version",
    ("org.openrewrite.recipe", "rewrite-jackson"): "openrewrite.jackson-recipes.version",
    ("org.openrewrite.recipe", "rewrite-netty"): "openrewrite.netty-recipes.version",
    ("org.openrewrite.recipe", "rewrite-openapi"): "openrewrite.openapi-recipes.version",
    ("org.openrewrite.recipe", "rewrite-third-party"): "openrewrite.third-party-recipes.version",
    ("org.openrewrite.recipe", "rewrite-all"): "openrewrite.all-recipes.version",
    ("org.openrewrite.recipe", "rewrite-spring"): "openrewrite.spring-recipes.version",
}


def required_text(element, path, label):
    node = element.find(path, NS)
    value = (node.text or "").strip() if node is not None else ""
    if not value:
        raise ValueError(f"Missing required {label}: {path}")
    return value


def parse(path):
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise ValueError(f"Unable to parse {path}: {exc}") from exc


def property_value(properties, key):
    node = properties.find(f"m:{key}", NS) if properties is not None else None
    return (node.text or "").strip() if node is not None else ""


def dependencies_by_coordinate(project):
    nodes = project.find("m:dependencies", NS)
    if nodes is None:
        return {}
    result = {}
    for dependency in nodes.findall("m:dependency", NS):
        group_id = required_text(dependency, "m:groupId", "dependency groupId")
        artifact_id = required_text(dependency, "m:artifactId", "dependency artifactId")
        result[(group_id, artifact_id)] = dependency
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root-pom", default="pom.xml")
    parser.add_argument("--audit-pom", default=".ci/openrewrite-bom-audit/pom.xml")
    parser.add_argument("--effective-pom", default="target/openrewrite/recipe-bom-effective-pom.xml")
    args = parser.parse_args()

    root = parse(args.root_pom)
    audit = parse(args.audit_pom)
    effective = parse(args.effective_pom)
    root_properties = root.find("m:properties", NS)
    if root_properties is None:
        raise ValueError(f"{args.root_pom} has no project properties")

    expected_bom_version = property_value(root_properties, "openrewrite.recipe-bom.version")
    if not expected_bom_version:
        raise ValueError("Missing root property openrewrite.recipe-bom.version")

    audit_dm = audit.find("m:dependencyManagement/m:dependencies", NS)
    bom_imports = []
    if audit_dm is not None:
        for dep in audit_dm.findall("m:dependency", NS):
            coord = (property_value(dep, "groupId"), property_value(dep, "artifactId"))
            if coord == ("org.openrewrite.recipe", "rewrite-recipe-bom"):
                bom_imports.append(dep)
    if len(bom_imports) != 1:
        raise ValueError("Audit POM must import exactly one org.openrewrite.recipe:rewrite-recipe-bom")
    bom = bom_imports[0]
    if required_text(bom, "m:version", "recipe BOM version") != "${openrewrite.recipe-bom.version}":
        raise ValueError("Audit POM must source the BOM version from ${openrewrite.recipe-bom.version}")
    if required_text(bom, "m:type", "recipe BOM type") != "pom" or required_text(bom, "m:scope", "recipe BOM scope") != "import":
        raise ValueError("Audit POM must import the recipe BOM with type=pom and scope=import")

    audit_deps = dependencies_by_coordinate(audit)
    for coordinate in EXPECTED:
        dependency = audit_deps.get(coordinate)
        if dependency is None:
            raise ValueError(f"Audit POM omits BOM-managed dependency {coordinate[0]}:{coordinate[1]}")
        if dependency.find("m:version", NS) is not None:
            raise ValueError(f"Audit dependency {coordinate[1]} has a hard-coded version; it must be BOM-managed")

    # The workflow passes the root BOM version as a Maven CLI user property.
    # The effective-POM <properties> section can retain the audit POM's literal
    # default, so it is not evidence of which imported BOM managed dependencies.
    # Compare the dependency versions Maven actually resolved from that import below.
    effective_deps = dependencies_by_coordinate(effective)
    failures = []
    results = []

    # Verify each explicit version property is wired to the recipe dependency of the Maven plugin.
    root_plugin_coordinates = {}
    for plugin in root.findall(".//m:build/m:plugins/m:plugin", NS):
        if property_value(plugin, "groupId") != "org.openrewrite.maven" or property_value(plugin, "artifactId") != "rewrite-maven-plugin":
            continue
        plugin_deps = plugin.find("m:dependencies", NS)
        if plugin_deps is None:
            continue
        for dependency in plugin_deps.findall("m:dependency", NS):
            coord = (
                property_value(dependency, "groupId"),
                property_value(dependency, "artifactId"),
            )
            root_plugin_coordinates.setdefault(coord, []).append(property_value(dependency, "version"))

    for coordinate, prop_name in EXPECTED.items():
        pinned = property_value(root_properties, prop_name)
        bom_dependency = effective_deps.get(coordinate)
        bom_version = property_value(bom_dependency, "version") if bom_dependency is not None else ""
        configured_versions = root_plugin_coordinates.get(coordinate, [])
        if not pinned:
            failures.append(f"Missing root version property {prop_name}")
        if not bom_version:
            failures.append(f"Published BOM did not resolve {coordinate[0]}:{coordinate[1]} in effective-pom")
        if not configured_versions:
            failures.append(f"Root POM does not declare {coordinate[0]}:{coordinate[1]} on rewrite-maven-plugin")
        elif "${" + prop_name + "}" not in configured_versions:
            failures.append(
                f"Root plugin dependency {coordinate[1]} does not use ${{{prop_name}}}; found {configured_versions}"
            )
        if pinned and bom_version:
            results.append((coordinate[1], prop_name, pinned, bom_version))
            if pinned != bom_version:
                failures.append(
                    f"Version mismatch for {coordinate[0]}:{coordinate[1]}: "
                    f"root property {prop_name}={pinned}, recipe BOM={bom_version}"
                )

    for artifact, prop_name, pinned, bom_version in results:
        print(f"{'MISMATCH' if pinned != bom_version else 'ALIGNED'} {artifact}: {prop_name}={pinned}; BOM={bom_version}")

    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1

    print(
        f"PASS: all {len(EXPECTED)} explicit rewrite-maven-plugin recipe versions match "
        f"rewrite-recipe-bom:{expected_bom_version}."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
