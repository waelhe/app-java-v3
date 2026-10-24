#!/usr/bin/env python3
"""Verify OpenRewrite plugin-scoped recipe coordinates against imported BOM versions."""
from __future__ import annotations
import argparse, sys, xml.etree.ElementTree as ET
from pathlib import Path
NS={"m":"http://maven.apache.org/POM/4.0.0"}
EXTERNAL_UNMANAGED={("org.openrewrite","rewrite-mainframe")}
def fail(message):
    print("::error::OpenRewrite BOM alignment failure: "+message)
    raise SystemExit(1)
def parse(path):
    if not path.is_file(): fail(f"required POM does not exist: {path}")
    try: return ET.parse(path).getroot()
    except ET.ParseError as exc: fail(f"invalid XML in {path}: {exc}")
def value(node, name):
    child=node.find("m:"+name,NS)
    return (child.text or "").strip() if child is not None else ""
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--project-pom",type=Path,required=True)
    p.add_argument("--effective-pom",type=Path,required=True)
    args=p.parse_args()
    project=parse(args.project_pom); effective=parse(args.effective_pom)
    dm=effective.find("m:dependencyManagement/m:dependencies",NS)
    if dm is None: fail("effective POM has no dependencyManagement; BOM imports did not take effect")
    managed={}
    for d in dm.findall("m:dependency",NS):
        g,a,v=value(d,"groupId"),value(d,"artifactId"),value(d,"version")
        if g and a and v: managed[(g,a)]=v
    checked=[]
    for profile in project.findall("m:profiles/m:profile",NS):
        profile_id=value(profile,"id")
        if profile_id not in {"openrewrite","openrewrite-source-available"}: continue
        deps=profile.findall("m:build/m:plugins/m:plugin[m:groupId='org.openrewrite.maven' and m:artifactId='rewrite-maven-plugin']/m:dependencies/m:dependency",NS)
        for d in deps:
            g,a,pinned=value(d,"groupId"),value(d,"artifactId"),value(d,"version")
            key=(g,a)
            if key in EXTERNAL_UNMANAGED:
                if not pinned: fail(f"{profile_id}: external {g}:{a} must be pinned to an official source tag")
                continue
            expected=managed.get(key)
            if expected is None: fail(f"{profile_id}: {g}:{a}:{pinned} absent from imported BOM management")
            if pinned!=expected: fail(f"{profile_id}: {g}:{a} pinned {pinned}, BOM manages {expected}")
            checked.append((profile_id,g,a,pinned))
    if not checked: fail("no plugin coordinates checked; check active profiles and effective POM")
    print(f"OpenRewrite BOM alignment verified: {len(checked)} plugin-scoped coordinates")
    for profile_id,g,a,v in checked: print(f"- {profile_id}: {g}:{a}:{v}")
    return 0
if __name__=="__main__": sys.exit(main())
