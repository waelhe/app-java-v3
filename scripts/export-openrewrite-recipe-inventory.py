#!/usr/bin/env python3
"""Export discovered recipes with attribution based on the artifact descriptors that declare them."""
from __future__ import annotations
import argparse, csv, re, sys, zipfile
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
CORE_APACHE = {"rewrite-core","rewrite-java","rewrite-java-8","rewrite-java-11","rewrite-java-17","rewrite-java-21","rewrite-java-25","rewrite-maven","rewrite-gradle","rewrite-groovy","rewrite-hcl","rewrite-json","rewrite-kotlin","rewrite-properties","rewrite-protobuf","rewrite-templating","rewrite-toml","rewrite-xml","rewrite-yaml","rewrite-docker"}
RELEVANCE = {
    "rewrite-java-dependencies": "Maven dependency inventory/resolution",
    "rewrite-jackson": "Jackson serialization and data compatibility",
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
    "rewrite-hibernate": "Hibernate/JPA, if present",
    "rewrite-all": "Broad aggregate; source attribution must prefer specific recipe pack",
    "rewrite-third-party": "Third-party migrations; source attribution must prefer specific recipe pack",
}
def descriptor_names(content):
    for line in content.splitlines():
        item = line.strip()
        if not item.startswith('name:'):
            continue
        name = item.partition(':')[2].strip()
        if len(name) >= 2 and name[0] in (chr(34), chr(39)) and name[-1] in (chr(34), chr(39)):
            name = name[1:-1].strip()
        if not name.startswith(('org.openrewrite.', 'io.moderne.', 'com.marketplace.')):
            continue
        if all(ch.isalnum() or ch in '._
    if not line.startswith("[INFO]"): return None
    rest=line[len("[INFO]"):]
    name=rest.strip()
    if not name.startswith(("org.openrewrite.","com.marketplace.")): return None
    if any(ch.isspace() for ch in name) or not all(ch.isalnum() or ch in "._$" for ch in name): return None
    if len(rest)-len(rest.lstrip()) < 9: return None
    return name

def license_of(group, artifact):
    if group == "org.openrewrite.recipe":
        return RECIPE_LICENSES.get(artifact, "UNKNOWN")
    if group == "org.openrewrite":
        if artifact in CORE_SOURCE_AVAILABLE: return "Moderne Source Available"
        if artifact in CORE_APACHE or artifact in {"rewrite-bom","rewrite-gradle-plugin","rewrite-maven-plugin"}: return "Apache-2.0"
        return "UNKNOWN"
    if group == "org.openrewrite.meta":
        return "Apache-2.0" if artifact == "rewrite-analysis" else "UNKNOWN"
    if group == "io.moderne":
        return "Moderne Source Available"
    return "UNKNOWN"

def module_label(group, artifact, version):
    return f"{group}:{artifact}:{version}"

def collect_artifact_recipes(root):
    mapping=defaultdict(set)
    if not root.is_dir():
        raise SystemExit(f"Maven repository does not exist: {root}")
    for jar in root.rglob("*.jar"):
        try:
            rel=jar.relative_to(root)
            if len(rel.parts)<4: continue
            artifact,version=rel.parts[-3],rel.parts[-2]
            group=".".join(rel.parts[:-3])
            if not (group.startswith("org.openrewrite") or group.startswith("io.moderne")): continue
            coord=module_label(group,artifact,version)
            with zipfile.ZipFile(jar) as z:
                for item in z.namelist():
                    if not item.startswith("META-INF/rewrite/") or not item.lower().endswith((".yml",".yaml")): continue
                    try: content=z.read(item).decode("utf-8",errors="replace")
                    except Exception: continue
                    for name in descriptor_names(content):
                        mapping[name].add((group,artifact,version,license_of(group,artifact)))
        except (OSError, zipfile.BadZipFile, ValueError):
            continue
    return mapping

def package_fallback(name):
    known = [
        ("org.openrewrite.docker.", "org.openrewrite","rewrite-docker"),
        ("org.openrewrite.java.dependencies.", "org.openrewrite.recipe","rewrite-java-dependencies"),
        ("org.openrewrite.java.jackson.", "org.openrewrite.recipe","rewrite-jackson"),
        ("org.openrewrite.java.netty.", "org.openrewrite.recipe","rewrite-netty"),
        ("org.openrewrite.openapi.", "org.openrewrite.recipe","rewrite-openapi"),
        ("org.openrewrite.java.spring.", "org.openrewrite.recipe","rewrite-spring"),
        ("org.openrewrite.java.logging.", "org.openrewrite.recipe","rewrite-logging-frameworks"),
        ("org.openrewrite.java.hibernate.", "org.openrewrite.recipe","rewrite-hibernate"),
        ("org.openrewrite.java.joda.", "org.openrewrite.recipe","rewrite-joda"),
        ("org.openrewrite.java.migrate.", "org.openrewrite.recipe","rewrite-migrate-java"),
        ("org.openrewrite.java.testing.", "org.openrewrite.recipe","rewrite-testing-frameworks"),
        ("org.openrewrite.java.micrometer.", "org.openrewrite.recipe","rewrite-micrometer"),
        ("org.openrewrite.java.okhttp.", "org.openrewrite.recipe","rewrite-okhttp"),
        ("org.openrewrite.java.pmd.", "org.openrewrite.recipe","rewrite-pmd"),
        ("org.openrewrite.jenkins.", "org.openrewrite.recipe","rewrite-jenkins"),
        ("org.openrewrite.github.actions.", "org.openrewrite.recipe","rewrite-github-actions"),
        ("org.openrewrite.gitlab.", "org.openrewrite.recipe","rewrite-gitlab"),
        ("org.openrewrite.java.", "org.openrewrite","rewrite-java"),
        ("org.openrewrite.maven.", "org.openrewrite","rewrite-maven"),
        ("org.openrewrite.gradle.", "org.openrewrite","rewrite-gradle"),
    ]
    for prefix,group,artifact in known:
        if name.startswith(prefix):
            return (group,artifact)
    return None

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--input",type=Path,required=True)
    ap.add_argument("--output",type=Path,required=True)
    ap.add_argument("--maven-repo",type=Path,default=Path.home()/".m2"/"repository")
    ap.add_argument("--source",action="store_true")
    ap.add_argument("--strict-attribution",action="store_true")
    args=ap.parse_args()
    if not args.input.is_file(): raise SystemExit(f"recipe discovery log not found: {args.input}")
    discovered={}
    display=""
    for line in args.input.read_text(encoding="utf-8",errors="replace").splitlines():
        recipe=parse_discovered(line)
        if recipe:
            discovered[recipe]=display
            display=""
        elif line.startswith("[INFO]"):
            rest=line[len("[INFO]"):]
            text=rest.strip()
            indent=len(rest)-len(rest.lstrip())
            if indent==5 and text and not text.startswith(("options:","recipeList:","Available Recipes:")):
                display=text
    declared=collect_artifact_recipes(args.maven_repo)
    output=[]
    unresolved=[]
    for recipe in sorted(discovered):
        if recipe.startswith("com.marketplace."):
            output.append([recipe,discovered[recipe],"project source","Internal","Internal project recipe","Platform governance","project YAML"])
            continue
        candidates=declared.get(recipe,set())
        # Aggregate catalog jars can contain descriptor copies; prefer specific recipe-pack owners.
        if len(candidates)>1:
            specific={c for c in candidates if c[1] not in {"rewrite-all","rewrite-third-party"}}
            if specific: candidates=specific
        candidate_text=";".join(sorted(module_label(g,a,v) for g,a,v,_ in candidates))
        licenses={lic for _,_,_,lic in candidates}
        if len(licenses)==1 and "UNKNOWN" not in licenses:
            license_name=next(iter(licenses))
            attribution="artifact descriptor"
        elif candidates and all(lic=="UNKNOWN" for _,_,_,lic in candidates):
            license_name="UNKNOWN"; attribution="artifact found; license table missing"
        elif candidates:
            license_name="MIXED / REVIEW"; attribution="multiple source artifacts with different licenses"
        else:
            license_name="UNKNOWN"; attribution="no matching META-INF/rewrite recipe descriptor"
        coords=sorted({(g,a,v) for g,a,v,_ in candidates})
        likely=", ".join(f"{g}:{a}" for g,a,_ in coords)
        relevance="General Java/build maintenance"
        for g,a,v in coords:
            if a in RELEVANCE:
                relevance=RELEVANCE[a]; break
        if not candidates:
            fallback=package_fallback(recipe)
            if fallback:
                g,a=fallback
                license_name=license_of(g,a)
                likely=f"{g}:{a} (package fallback)"
                attribution="package fallback only; artifact descriptor not found"
                relevance=RELEVANCE.get(a,"Java/build maintenance")
        if license_name in {"UNKNOWN","MIXED / REVIEW"} or "package fallback only" in attribution:
            unresolved.append((recipe,license_name,attribution))
        output.append([recipe,discovered[recipe],likely,license_name,
                       "Source Available" if license_name=="Moderne Source Available" else ("Apache downloadable" if license_name=="Apache-2.0" else "Review"),
                       relevance,attribution])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("w",encoding="utf-8",newline="") as f:
        w=csv.writer(f)
        w.writerow(["recipe_id","display_name","source_artifact_coordinates","license","availability","platform_relevance","attribution_evidence"])
        w.writerows(output)
    print(f"Exported {len(output)} unique discovered recipe IDs; {len(unresolved)} require source/license attribution review.")
    if unresolved:
        print("Attribution gaps (first 40):")
        for recipe,lic,why in unresolved[:40]: print(f"- {recipe}: {lic} ({why})")
    if args.strict_attribution and unresolved:
        raise SystemExit(2)

if __name__=="__main__":
    main()
 for ch in name):
            yield name
def parse_discovered(line):
    if not line.startswith("[INFO]"): return None
    rest=line[len("[INFO]"):]
    name=rest.strip()
    if not name.startswith(("org.openrewrite.","com.marketplace.")): return None
    if any(ch.isspace() for ch in name) or not all(ch.isalnum() or ch in "._$" for ch in name): return None
    if len(rest)-len(rest.lstrip()) < 9: return None
    return name

def license_of(group, artifact):
    if group == "org.openrewrite.recipe":
        return RECIPE_LICENSES.get(artifact, "UNKNOWN")
    if group == "org.openrewrite":
        if artifact in CORE_SOURCE_AVAILABLE: return "Moderne Source Available"
        if artifact in CORE_APACHE or artifact in {"rewrite-bom","rewrite-gradle-plugin","rewrite-maven-plugin"}: return "Apache-2.0"
        return "UNKNOWN"
    if group == "org.openrewrite.meta":
        return "Apache-2.0" if artifact == "rewrite-analysis" else "UNKNOWN"
    if group == "io.moderne":
        return "Moderne Source Available"
    return "UNKNOWN"

def module_label(group, artifact, version):
    return f"{group}:{artifact}:{version}"

def collect_artifact_recipes(root):
    mapping=defaultdict(set)
    if not root.is_dir():
        raise SystemExit(f"Maven repository does not exist: {root}")
    for jar in root.rglob("*.jar"):
        try:
            rel=jar.relative_to(root)
            if len(rel.parts)<4: continue
            artifact,version=rel.parts[-3],rel.parts[-2]
            group=".".join(rel.parts[:-3])
            if not (group.startswith("org.openrewrite") or group.startswith("io.moderne")): continue
            coord=module_label(group,artifact,version)
            with zipfile.ZipFile(jar) as z:
                for item in z.namelist():
                    if not item.startswith("META-INF/rewrite/") or not item.lower().endswith((".yml",".yaml")): continue
                    try: content=z.read(item).decode("utf-8",errors="replace")
                    except Exception: continue
                    for name in descriptor_names(content):
                        mapping[name].add((group,artifact,version,license_of(group,artifact)))
        except (OSError, zipfile.BadZipFile, ValueError):
            continue
    return mapping

def package_fallback(name):
    known = [
        ("org.openrewrite.docker.", "org.openrewrite","rewrite-docker"),
        ("org.openrewrite.java.dependencies.", "org.openrewrite.recipe","rewrite-java-dependencies"),
        ("org.openrewrite.java.jackson.", "org.openrewrite.recipe","rewrite-jackson"),
        ("org.openrewrite.java.netty.", "org.openrewrite.recipe","rewrite-netty"),
        ("org.openrewrite.openapi.", "org.openrewrite.recipe","rewrite-openapi"),
        ("org.openrewrite.java.spring.", "org.openrewrite.recipe","rewrite-spring"),
        ("org.openrewrite.java.logging.", "org.openrewrite.recipe","rewrite-logging-frameworks"),
        ("org.openrewrite.java.hibernate.", "org.openrewrite.recipe","rewrite-hibernate"),
        ("org.openrewrite.java.joda.", "org.openrewrite.recipe","rewrite-joda"),
        ("org.openrewrite.java.migrate.", "org.openrewrite.recipe","rewrite-migrate-java"),
        ("org.openrewrite.java.testing.", "org.openrewrite.recipe","rewrite-testing-frameworks"),
        ("org.openrewrite.java.micrometer.", "org.openrewrite.recipe","rewrite-micrometer"),
        ("org.openrewrite.java.okhttp.", "org.openrewrite.recipe","rewrite-okhttp"),
        ("org.openrewrite.java.pmd.", "org.openrewrite.recipe","rewrite-pmd"),
        ("org.openrewrite.jenkins.", "org.openrewrite.recipe","rewrite-jenkins"),
        ("org.openrewrite.github.actions.", "org.openrewrite.recipe","rewrite-github-actions"),
        ("org.openrewrite.gitlab.", "org.openrewrite.recipe","rewrite-gitlab"),
        ("org.openrewrite.java.", "org.openrewrite","rewrite-java"),
        ("org.openrewrite.maven.", "org.openrewrite","rewrite-maven"),
        ("org.openrewrite.gradle.", "org.openrewrite","rewrite-gradle"),
    ]
    for prefix,group,artifact in known:
        if name.startswith(prefix):
            return (group,artifact)
    return None

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--input",type=Path,required=True)
    ap.add_argument("--output",type=Path,required=True)
    ap.add_argument("--maven-repo",type=Path,default=Path.home()/".m2"/"repository")
    ap.add_argument("--source",action="store_true")
    ap.add_argument("--strict-attribution",action="store_true")
    args=ap.parse_args()
    if not args.input.is_file(): raise SystemExit(f"recipe discovery log not found: {args.input}")
    discovered={}
    display=""
    for line in args.input.read_text(encoding="utf-8",errors="replace").splitlines():
        recipe=parse_discovered(line)
        if recipe:
            discovered[recipe]=display
            display=""
        elif line.startswith("[INFO]"):
            rest=line[len("[INFO]"):]
            text=rest.strip()
            indent=len(rest)-len(rest.lstrip())
            if indent==5 and text and not text.startswith(("options:","recipeList:","Available Recipes:")):
                display=text
    declared=collect_artifact_recipes(args.maven_repo)
    output=[]
    unresolved=[]
    for recipe in sorted(discovered):
        if recipe.startswith("com.marketplace."):
            output.append([recipe,discovered[recipe],"project source","Internal","Internal project recipe","Platform governance","project YAML"])
            continue
        candidates=declared.get(recipe,set())
        # Aggregate catalog jars can contain descriptor copies; prefer specific recipe-pack owners.
        if len(candidates)>1:
            specific={c for c in candidates if c[1] not in {"rewrite-all","rewrite-third-party"}}
            if specific: candidates=specific
        candidate_text=";".join(sorted(module_label(g,a,v) for g,a,v,_ in candidates))
        licenses={lic for _,_,_,lic in candidates}
        if len(licenses)==1 and "UNKNOWN" not in licenses:
            license_name=next(iter(licenses))
            attribution="artifact descriptor"
        elif candidates and all(lic=="UNKNOWN" for _,_,_,lic in candidates):
            license_name="UNKNOWN"; attribution="artifact found; license table missing"
        elif candidates:
            license_name="MIXED / REVIEW"; attribution="multiple source artifacts with different licenses"
        else:
            license_name="UNKNOWN"; attribution="no matching META-INF/rewrite recipe descriptor"
        coords=sorted({(g,a,v) for g,a,v,_ in candidates})
        likely=", ".join(f"{g}:{a}" for g,a,_ in coords)
        relevance="General Java/build maintenance"
        for g,a,v in coords:
            if a in RELEVANCE:
                relevance=RELEVANCE[a]; break
        if not candidates:
            fallback=package_fallback(recipe)
            if fallback:
                g,a=fallback
                license_name=license_of(g,a)
                likely=f"{g}:{a} (package fallback)"
                attribution="package fallback only; artifact descriptor not found"
                relevance=RELEVANCE.get(a,"Java/build maintenance")
        if license_name in {"UNKNOWN","MIXED / REVIEW"} or "package fallback only" in attribution:
            unresolved.append((recipe,license_name,attribution))
        output.append([recipe,discovered[recipe],likely,license_name,
                       "Source Available" if license_name=="Moderne Source Available" else ("Apache downloadable" if license_name=="Apache-2.0" else "Review"),
                       relevance,attribution])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("w",encoding="utf-8",newline="") as f:
        w=csv.writer(f)
        w.writerow(["recipe_id","display_name","source_artifact_coordinates","license","availability","platform_relevance","attribution_evidence"])
        w.writerows(output)
    print(f"Exported {len(output)} unique discovered recipe IDs; {len(unresolved)} require source/license attribution review.")
    if unresolved:
        print("Attribution gaps (first 40):")
        for recipe,lic,why in unresolved[:40]: print(f"- {recipe}: {lic} ({why})")
    if args.strict_attribution and unresolved:
        raise SystemExit(2)

if __name__=="__main__":
    main()
