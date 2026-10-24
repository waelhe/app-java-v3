#!/usr/bin/env python3
"""Fail-fast consistency checks for the repository's required Spring/Maven guard.

This script intentionally uses only Python's standard library so the required CI
gate has no extra package, service, or commercial-license dependency.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fail(message: str) -> None:
    print(f"::error::Framework guard policy drift: {message}")
    sys.exit(1)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def node_text(parent: ET.Element | None, path: str) -> str | None:
    if parent is None:
        return None
    node = parent.find(path, NS)
    if node is None or node.text is None:
        return None
    return node.text.strip()


def file_text(relative_path: str) -> str:
    path = ROOT / relative_path
    require(path.is_file(), f"required guard file is missing: {relative_path}")
    return path.read_text(encoding="utf-8")


pom_text = file_text("pom.xml")
try:
    pom = ET.fromstring(pom_text)
except ET.ParseError as exc:
    fail(f"pom.xml is not well-formed XML: {exc}")

# Toolchain policy must be explicit and consistent with the CI JDK.
require(node_text(pom, "m:properties/m:java.version") == "25",
        "pom.xml java.version must remain 25 until an approved toolchain migration.")
parent = pom.find("m:parent", NS)
require(node_text(parent, "m:groupId") == "org.springframework.boot"
        and node_text(parent, "m:artifactId") == "spring-boot-starter-parent",
        "the project must continue to inherit the official Spring Boot parent unless policy is deliberately revised.")
boot_version = node_text(parent, "m:version")
require(boot_version is not None and re.fullmatch(r"4\.1\.\d+", boot_version) is not None,
        "Spring Boot parent must stay on the approved 4.1 patch line or this guard must be reviewed with the version policy.")

wrapper = file_text(".mvn/wrapper/maven-wrapper.properties")
match = re.search(r"distributionUrl=.*?/apache-maven/([0-9]+(?:\.[0-9]+)+)/apache-maven-\1-bin\.zip", wrapper)
require(match is not None, "Maven Wrapper must use an explicit Apache Maven binary distribution URL.")
wrapper_version = tuple(int(part) for part in match.group(1).split("."))
require(wrapper_version >= (3, 9, 16),
        f"Maven Wrapper {match.group(1)} is older than the supported baseline 3.9.16.")

# Maven Enforcer is the build-time authority, not just a documented convention.
build = pom.find("m:build", NS)
plugins = build.find("m:plugins", NS) if build is not None else None
enforcer = None
if plugins is not None:
    for plugin in plugins.findall("m:plugin", NS):
        if node_text(plugin, "m:groupId") == "org.apache.maven.plugins" and node_text(plugin, "m:artifactId") == "maven-enforcer-plugin":
            enforcer = plugin
            break
require(enforcer is not None, "root pom.xml must keep the Maven Enforcer plugin.")
rules = None
for execution in enforcer.findall("m:executions/m:execution", NS) if enforcer is not None else []:
    execution_rules = execution.find("m:configuration/m:rules", NS)
    if execution_rules is not None:
        rules = execution_rules
        break
require(rules is not None, "Maven Enforcer must have an explicit ruleset.")
require(node_text(rules, "m:requireJavaVersion/m:version") == "[25,26)",
        "requireJavaVersion must enforce the Java 25 toolchain exactly: [25,26).")
require(node_text(rules, "m:requireMavenVersion/m:version") == "[3.9.16,)",
        "requireMavenVersion must match the supported Maven baseline: [3.9.16,).")
for rule_name in ("dependencyConvergence", "requireUpperBoundDeps", "banDuplicatePomDependencyVersions"):
    require(rules.find(f"m:{rule_name}", NS) is not None,
            f"Maven Enforcer rule {rule_name} was removed; review before weakening dependency governance.")

# Coverage and compiler/test plugins are enforcement, not optional documentation.
require(node_text(pom, "m:properties/m:jacoco.coverage.threshold") == "0.70",
        "JaCoCo instruction coverage threshold must remain at 70% unless the quality policy is revised.")
for artifact in ("jacoco-maven-plugin", "maven-surefire-plugin", "maven-failsafe-plugin"):
    require(artifact in pom_text, f"expected Maven quality plugin is missing: {artifact}")

ci = file_text(".github/workflows/ci.yml")
require(re.search(r"java-version:\s*[\"']?25[\"']?", ci) is not None,
        "required CI must use JDK 25.")
require("Verify Spring/Maven guard policy alignment" in ci
        and "scripts/verify-framework-guard-policy.py" in ci,
        "policy check must run inside the existing CI workflow that feeds the required Build & Test gate.")

# Keep the OpenRewrite plugin, license-allowed recipe packs, and separate modes aligned to official metadata.
profile_start = pom_text.find("<id>openrewrite</id>")
require(profile_start >= 0, "the current OpenRewrite Maven profile is missing.")
profile_end = pom_text.find("</profile>", profile_start)
require(profile_end > profile_start, "the OpenRewrite Maven profile is malformed.")
rewrite_profile = pom_text[profile_start:profile_end]
require("<version>6.50.0</version>" in rewrite_profile,
        "OpenRewrite Maven plugin must match the reviewed official 6.50.0 release.")
require("rewrite-recipe-bom:3.41.0" in rewrite_profile,
        "the manually pinned plugin dependencies must document official rewrite-recipe-bom 3.41.0 as their alignment reference.")
require("https://artifacts.codegenomeproject.org/maven" in rewrite_profile
        and "<id>codegenome</id>" in rewrite_profile
        and "https://repo.maven.apache.org/maven2" in rewrite_profile,
        "OpenRewrite profile must configure authenticated Code Genome and Maven Central repositories.")
require("com.marketplace.governance.OpenRewriteBaseline" in rewrite_profile
        and "${maven.multiModuleProjectDirectory}/rewrite.yml" in rewrite_profile,
        "OpenRewrite profile must run the checked-in root recipe manifest.")
for forbidden in ("org.openrewrite.recipe:rewrite-spring", "rewrite-static-analysis",
                  "rewrite-migrate-java", "rewrite-hibernate", "rewrite-micrometer",
                  "rewrite-logging-frameworks", "rewrite-okhttp", "rewrite-github-actions"):
    require(forbidden not in rewrite_profile,
            f"Source Available/proprietary recipe pack {forbidden} is not approved in the no-commercial-subscription profile.")
# The aggregate packs are Apache-licensed at their top-level coordinate, but their
# transitive closure failed with HTTP 403 on the configured free Code Genome account.
for aggregate in ("rewrite-all", "rewrite-third-party"):
    require(f"<artifactId>{aggregate}</artifactId>" not in rewrite_profile,
            f"{aggregate} must stay excluded until its restricted transitive dependency closure is replaced.")

# These plugin dependency versions are pinned manually because OpenRewrite documents that Maven
# does not apply BOM management to plugin dependencies. Versions were checked against recipe BOM 3.41.0.
allowed_modules = {
    "rewrite-java-dependencies": "1.64.1",
    "rewrite-jackson": "1.31.1",
    "rewrite-netty": "0.13.2",
    "rewrite-openapi": "0.35.0",
    "rewrite-docker": "8.93.11",
}
for artifact, version in allowed_modules.items():
    require(f"<artifactId>{artifact}</artifactId>" in rewrite_profile
            and f"<version>{version}</version>" in rewrite_profile,
            f"OpenRewrite module {artifact}:{version} is missing or not pinned to the reviewed official version.")

rewrite_config = file_text("rewrite.yml")
require("name: com.marketplace.governance.OpenRewriteBaseline" in rewrite_config
        and "name: com.marketplace.governance.OpenRewriteRecommendations" in rewrite_config,
        "both the read-only inventory and manual recommendation recipes must be defined.")
baseline_start = rewrite_config.index("name: com.marketplace.governance.OpenRewriteBaseline")
recommendations_start = rewrite_config.index("name: com.marketplace.governance.OpenRewriteRecommendations")
baseline_recipe = rewrite_config[baseline_start:recommendations_start]
recommendation_recipe = rewrite_config[recommendations_start:]
for recipe_text in ("org.openrewrite.java.dependencies.DependencyList",
                    "scope: TestRuntime", "includeTransitive: true", "validateResolvable: true",
                    "org.openrewrite.java.dependencies.DependencyResolutionDiagnostic"):
    require(recipe_text in baseline_recipe,
            f"read-only inventory recipe is missing required behavior: {recipe_text}")
for recipe_text in ("org.openrewrite.java.format.AutoFormat",
                    "org.openrewrite.java.RemoveUnusedImports",
                    "org.openrewrite.maven.BestPractices"):
    require(recipe_text in recommendation_recipe,
            f"manual recommendation mode is missing recipe: {recipe_text}")
require(all(recipe not in baseline_recipe for recipe in (
        "org.openrewrite.java.format.AutoFormat",
        "org.openrewrite.java.RemoveUnusedImports",
        "org.openrewrite.maven.BestPractices")),
        "source/POM recommendation recipes must not run in the default read-only inventory mode.")

report_script = file_text("scripts/summarize-openrewrite-report.py")
rewrite_workflow = file_text(".github/workflows/openrewrite.yml")
for evidence in ("CODEGENOME_USERNAME", "CODEGENOME_TOKEN", "Install reactor for dependency-resolution validation", "-DskipITs install", "rewrite:discover",
                 "rewrite:dryRun", "actions/upload-artifact@v7",
                 "scripts/summarize-openrewrite-report.py", "recommendations",
                 "GITHUB_STEP_SUMMARY"):
    require(evidence in rewrite_workflow,
            f"OpenRewrite workflow is missing required end-to-end behavior: {evidence}")
require("return 2 if a.mode==\"inventory\" and (not dp or failures) else 0" in report_script,
        "the OpenRewrite inventory must fail closed if dependency resolution is incomplete or has failures.")
require("git diff --quiet" in rewrite_workflow and "failOnInvalidActiveRecipes=true" in rewrite_workflow,
        "OpenRewrite must stay non-mutating and fail on invalid recipes.")
require("CODEGENOME_USERNAME and CODEGENOME_TOKEN are required" in rewrite_workflow
        and "OpenRewrite is skipped" not in rewrite_workflow,
        "OpenRewrite CI must fail visibly when credentials are missing instead of reporting a silent success.")
require('collect(root,"DependencyListReport.csv"' in report_script
        and "openrewrite-summary.md" in rewrite_workflow,
        "the report summarizer must read dependency data tables and the workflow must publish the human-readable summary.")

# Fail closed if architecture verification is deleted or silently disabled.
architecture = file_text("marketplace-app/src/test/java/com/marketplace/ArchitectureRulesTest.java")
require("@ArchTest" in architecture and "modulesMustBeCycleFree" in architecture,
        "ArchUnit architecture rules are missing or the module-cycle rule was removed.")
modulith = file_text("marketplace-app/src/test/java/com/marketplace/ModulithVerificationTest.java")
require("ApplicationModules.of(" in modulith and ".verify()" in modulith,
        "Spring Modulith module verification is missing.")
require("@Disabled" not in modulith,
        "Spring Modulith verification must not be disabled silently.")
readiness = file_text("marketplace-app/src/test/java/com/marketplace/ModulithGuardReadinessTest.java")
require("assertTrue(feature < 26" in readiness,
        "Modulith/ArchUnit JDK compatibility readiness guard is missing.")
require("@Disabled" not in architecture and "@Disabled" not in readiness,
        "required architecture guard tests must not be disabled silently.")

version_policy = file_text("docs/governance/version-policy.md")
require("Java: `25`" in version_policy and f"Spring Boot: `{boot_version}`" in version_policy,
        "docs/governance/version-policy.md is out of sync with the enforced Java/Spring Boot versions.")
require("Maven: `3.9.16+`" in version_policy,
        "version policy must document the Maven Wrapper/enforcer minimum.")

print("Framework/Maven guard policy is aligned:")
print(f"- Java: 25 (Enforcer [25,26), CI JDK 25)")
print(f"- Spring Boot parent: {boot_version}")
print(f"- Maven Wrapper: {match.group(1)}; Enforcer minimum: 3.9.16")
print("- Maven Enforcer dependency rules and 70% JaCoCo threshold present")
print("- ArchUnit, Spring Modulith, and compatibility-readiness tests present and enabled")
