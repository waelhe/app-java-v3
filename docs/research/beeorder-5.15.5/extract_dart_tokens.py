import re, sys, json

LIB = r"D:\rea-beeorder\clean\libs\lib\armeabi-v7a\libapp.so"
OUT = r"D:\rea-beeorder\ui\dart_tokens.txt"

SECTIONS = [
    ("VmSnapshotData", 640, 26536),
    ("IsolateSnapshotData", 27200, 5270096),
]

run = re.compile(rb"[A-Za-z_$][A-Za-z0-9_$]{1,}")
tokens = set()
per = {}

with open(LIB, "rb") as f:
    data = f.read()

for name, off, size in SECTIONS:
    blob = data[off:off + size]
    found = set()
    for m in run.finditer(blob):
        found.add(m.group().decode("ascii", "replace"))
    per[name] = len(found)
    tokens |= found

with open(OUT, "w", encoding="utf-8") as f:
    for t in sorted(tokens):
        f.write(t + "\n")

print(json.dumps({"sections": per, "total_unique": len(tokens), "out": OUT}))
