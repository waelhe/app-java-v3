#!/usr/bin/env python3
"""Generate a human-readable summary from OpenRewrite CSV tables and dry-run patches."""
import argparse, csv, re
from collections import Counter
from pathlib import Path

def table(path, first_header):
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows=list(csv.reader(f))
    i=next((n for n,r in enumerate(rows) if r and r[0]==first_header),None)
    return ([],[]) if i is None else (rows[i],[r for r in rows[i+1:] if r and not r[0].startswith("#")])

def collect(root, suffix, header):
    hs=[]; rows=[]; paths=sorted(p for p in root.rglob("*.csv") if p.name.endswith(suffix))
    for p in paths:
        h,r=table(p,header)
        if h: hs=h; rows.extend(r)
    return hs,rows,paths

def fmt(x): return f"{x:,}"

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--mode",choices=("inventory","recommendations"),required=True)
    ap.add_argument("--commit",required=True)
    ap.add_argument("--report-root",type=Path,required=True)
    ap.add_argument("--output",type=Path,required=True)
    a=ap.parse_args(); root=a.report_root; repo=Path.cwd()
    patches=set(root.rglob("rewrite.patch"))
    for pattern in ("**/target/rewrite/rewrite.patch","**/target/site/rewrite/rewrite.patch"):
        patches.update(p for p in repo.glob(pattern) if p.is_file() and ".git" not in p.parts)
    changed=set(); patch_bytes=0
    for p in sorted(p for p in patches if p.is_file()):
        changed.update(re.findall(r"^diff --git a/(.*?) b/",p.read_text(encoding="utf-8",errors="replace"),re.M)); patch_bytes+=p.stat().st_size
    _,src,_=collect(root,"SourcesFileResults.csv","sourcePath")
    files={r[0] for r in src if r and r[0]}; by_recipe=Counter(r[3] for r in src if len(r)>3 and r[3]); exts=Counter(Path(p).suffix for p in files)
    dh,dr,dp=collect(root,"DependencyListReport.csv","buildTool"); direct=transitive=0; failures=[]
    if dh:
        di=dh.index("direct") if "direct" in dh else -1; fi=dh.index("resolutionFailure") if "resolutionFailure" in dh else -1
        for r in dr:
            if not r or r[0]!="Maven": continue
            if di>=0 and len(r)>di and r[di].lower()=="true": direct+=1
            else: transitive+=1
            if fi>=0 and len(r)>fi and r[fi].strip(): failures.append(r)
    rh,rr,_=collect(root,"RepositoryAccessibilityReport.csv","uri"); rep=[]
    if rh:
        ui=rh.index("uri") if "uri" in rh else 0; ci=rh.index("pingHttpCode") if "pingHttpCode" in rh else -1; ei=rh.index("pingExceptionType") if "pingExceptionType" in rh else -1
        for r in rr:
            if len(r)<=ui: continue
            code=r[ci] if ci>=0 and len(r)>ci else "?"; err=r[ei] if ei>=0 and len(r)>ei else ""
            rep.append(f"| `{r[ui]}` | {code} | {err or '—'} |")
    lines=["# OpenRewrite run report","",f"- **Mode:** `{a.mode}`",f"- **Commit:** `{a.commit}`","- **Safety:** changes are preview only and are never automatically applied.","",
           "Read-only dependency inventory." if a.mode=="inventory" else "Manual Java/Maven recommendations; review all patches before use.","",
           "| Measure | Observed |","|---|---:|",f"| CSV tables | {fmt(len(list(root.rglob('*.csv'))))} |",
           f"| Dependency rows | {fmt(direct+transitive)} |",f"| Direct | {fmt(direct)} |",f"| Transitive | {fmt(transitive)} |",
           f"| Recorded resolution failures | {fmt(len(failures))} |",f"| Unique files with recipe results | {fmt(len(files))} |",
           f"| Java files with results | {fmt(exts.get('.java',0))} |",f"| XML/POM files with results | {fmt(exts.get('.xml',0))} |",
           f"| Files in patch(es) | {fmt(len(changed))} |",f"| Patch bytes | {fmt(patch_bytes)} |",""]
    if by_recipe:
        lines += ["## Recipe results","","| Recipe | Rows |","|---|---:|"]+[f"| `{r}` | {fmt(c)} |" for r,c in by_recipe.most_common()]+[""]
    if rep: lines += ["## Repository accessibility","","| Repository | HTTP code | Ping exception |","|---|---:|---|",*rep,""]
    if failures: lines += ["## Resolution failures","",f"{fmt(len(failures))} dependency rows contain recorded resolution failures.",""]
    else: lines += ["## Interpretation","","Blank resolutionFailure values prove actual dependency checks only when `validateResolvable=true` is enabled. Result rows for recipes may overlap on the same file.",""]
    if a.mode=="inventory" and not dp: lines += ["## Validation error","","**No DependencyListReport.csv was produced; inventory is incomplete.**",""]
    a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"OpenRewrite report: {a.output}")
    return 2 if a.mode=="inventory" and (not dp or failures) else 0

if __name__=="__main__": raise SystemExit(main())
