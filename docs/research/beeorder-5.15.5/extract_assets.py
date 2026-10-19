import zipfile, os, json, hashlib

APK = r"D:\rea-beeorder\clean\com.beeorder.customer.apk"
DEST = r"D:\rea-beeorder\ui\reconstruction"
PREFIX = "assets/flutter_assets/"
WANT = ("assets/images/", "assets/animations/", "assets/fonts/")

z = zipfile.ZipFile(APK)
manifest = []
total = 0
for n in sorted(z.namelist()):
    if not n.startswith(PREFIX):
        continue
    rel = n[len(PREFIX):]
    if not rel.startswith(WANT):
        continue
    data = z.read(n)
    out = os.path.join(DEST, rel.replace("/", os.sep))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(data)
    total += len(data)
    manifest.append({
        "rel": rel,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "kind": "animation" if rel.startswith("assets/animations/") else
                "font" if rel.startswith("assets/fonts/") else "image",
    })

with open(os.path.join(DEST, "asset_manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=1)

kinds = {}
for m in manifest:
    kinds[m["kind"]] = kinds.get(m["kind"], 0) + 1
print(json.dumps({"files": len(manifest), "bytes": total, "kinds": kinds, "dest": DEST}, indent=1))
