import os, re, collections, json

root = r"D:\rea-beeorder\clean\jadx\sources"
urls = collections.Counter()
chans = collections.Counter()
pkgs = collections.Counter()
urlre = re.compile(r"https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{6,}")
chanre = re.compile(r'MethodChannel\(\s*"([^"]+)"')

for dp, dn, fn in os.walk(root):
    for f in fn:
        if not f.endswith(".java"):
            continue
        try:
            s = open(os.path.join(dp, f), encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        for m in urlre.findall(s):
            urls[m] += 1
        for m in chanre.findall(s):
            chans[m] += 1
        rel = os.path.relpath(os.path.join(dp, f), root).replace(os.sep, ".")
        pkgs[".".join(rel.split(".")[:3])] += 1

beepkgs = {k: v for k, v in pkgs.items() if k.startswith("com.beeorder")}
appurls = {k: v for k, v in urls.items()
           if not any(d in k for d in ("w3.org", "schemas.android", "apache.org", "xmlpull",
                                       "json-schema", "google.com/gms", "goo.gl", "example.com",
                                       "github.com", "kotlinlang", "jetbrains", "android.com",
                                       "opensource.org", "gnu.org", "unicode.org", "ietf.org",
                                       "rfc-editor", "slf4j", "square.github", "bumptech"))}

out = {
    "channels": chans.most_common(60),
    "beeorder_packages": sorted(beepkgs.items(), key=lambda x: -x[1]),
    "app_urls": sorted(appurls.items(), key=lambda x: -x[1])[:120],
}
p = r"D:\rea-beeorder\clean\jadx_summary.json"
json.dump(out, open(p, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print("channels:", len(chans), "beeorder_pkgs:", len(beepkgs), "app_urls:", len(appurls))
print("\n== CHANNELS ==")
for k, v in out["channels"]:
    print(f"{v:4d} {k}")
print("\n== BEEORDER PKGS ==")
for k, v in out["beeorder_packages"][:80]:
    print(f"{v:4d} {k}")
print("\n== APP URLS ==")
for k, v in out["app_urls"][:60]:
    print(f"{v:4d} {k}")
