#!/usr/bin/env python3
"""Export recipe identifiers from rewrite:discover with conservative module/license attribution."""
from __future__ import annotations
import argparse,csv,re
from pathlib import Path
RECIPE=re.compile(r"^\\[INFO\\]\\s+((?:org\\.openrewrite|com\\.marketplace)\\.[A-Za-z0-9_.$]+)\\s*$")
DISPLAY=re.compile(r"^\\[INFO\\]\\s{4}(.+?)\\s*$")
def attr(n):
    if n.startswith("com.marketplace."): return ("marketplace","Internal recipe","project composite","Platform governance")
    rules=[
      ("org.openrewrite.docker.","rewrite-docker","Apache-2.0","Precompiled Apache module","Containers/Docker"),
      ("org.openrewrite.java.dependencies.","rewrite-java-dependencies","Apache-2.0","Precompiled Apache module","Maven dependency inventory"),
      ("org.openrewrite.java.jackson.","rewrite-jackson","Apache-2.0","Precompiled Apache module","Jackson/serialization"),
      ("org.openrewrite.java.netty.","rewrite-netty","Apache-2.0","Precompiled Apache module","Netty/network stack"),
      ("org.openrewrite.openapi.","rewrite-openapi","Apache-2.0","Precompiled Apache module","OpenAPI contracts"),
      ("org.openrewrite.maven.","rewrite-maven","Apache-2.0","Core Apache module","Maven/build governance"),
      ("org.openrewrite.gradle.","rewrite-gradle","Apache-2.0","Core Apache module","Gradle/build governance"),
    ]
    for prefix,module,lic,avail,rel in rules:
        if n.startswith(prefix): return module,lic,avail,rel
    sa=[
      ("org.openrewrite.java.spring.","rewrite-spring","Spring Framework/Boot"),
      ("org.openrewrite.java.logging.","rewrite-logging-frameworks","Logging"),
      ("org.openrewrite.java.hibernate.","rewrite-hibernate","Hibernate/JPA"),
      ("org.openrewrite.java.joda.","rewrite-joda","Joda-Time"),
      ("org.openrewrite.java.migrate.","rewrite-migrate-java","Java migration"),
      ("org.openrewrite.java.testing.","rewrite-testing-frameworks","Testing libraries"),
      ("org.openrewrite.java.micrometer.","rewrite-micrometer","Metrics/observability"),
      ("org.openrewrite.java.okhttp.","rewrite-okhttp","HTTP client"),
      ("org.openrewrite.java.pmd.","rewrite-pmd","Static analysis"),
      ("org.openrewrite.java.prethink.","rewrite-prethink","Static analysis"),
      ("org.openrewrite.java.cucumber.","rewrite-cucumber-jvm","Testing/Cucumber"),
      ("org.openrewrite.jenkins.","rewrite-jenkins","Jenkins CI"),
      ("org.openrewrite.github.actions.","rewrite-github-actions","GitHub Actions"),
      ("org.openrewrite.gitlab.","rewrite-gitlab","GitLab CI"),
    ]
    for prefix,module,rel in sa:
        if n.startswith(prefix): return module,"Moderne Source Available","Source build requires separate verification",rel
    if n.startswith("org.openrewrite.java."): return "rewrite-java","Apache-2.0","Core Apache module","Java source modernization"
    if n.startswith(("org.openrewrite.xml.","org.openrewrite.yaml.","org.openrewrite.json.","org.openrewrite.properties.","org.openrewrite.kotlin.","org.openrewrite.groovy.","org.openrewrite.hcl.")):
        return "OpenRewrite language module","Review official module license","Attribution needs verification","Build/configuration maintenance"
    return "OpenRewrite core/other","Review official module license","Attribution needs verification","General/uncategorized; review relevance"
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--input",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--source",action="store_true")
    a=p.parse_args()
    if not a.input.is_file(): raise SystemExit(f"recipe discovery log not found: {a.input}")
    rows={}; display=""
    for line in a.input.read_text(encoding="utf-8",errors="replace").splitlines():
        m=RECIPE.match(line)
        if m:
            recipe=m.group(1); module,lic,availability,relevance=attr(recipe)
            rows[recipe]=[recipe,display,module,lic,availability,relevance,"package/module heuristic; verify official recipe source before license policy"]
            display=""
            continue
        dm=DISPLAY.match(line)
        if dm and dm.group(1).strip() not in {"Available Recipes:"} and not dm.group(1).lstrip().startswith(("options:","recipeList:")):
            display=dm.group(1).strip()
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with a.output.open("w",encoding="utf-8",newline="") as f:
        w=csv.writer(f); w.writerow(["recipe_id","display_name","likely_source_module","license_attribution","availability","platform_relevance","attribution_basis"])
        for name in sorted(rows): w.writerow(rows[name])
    uncertain=sum("Review official" in r[3] for r in rows.values())
    print(f"Exported {len(rows)} unique recipe identifiers; {uncertain} need official source/license attribution review")
if __name__=="__main__": main()
