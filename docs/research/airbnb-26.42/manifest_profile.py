import sys, xml.etree.ElementTree as ET
from collections import Counter
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
A = "{http://schemas.android.com/apk/res/android}"
p = r"C:\Users\w-co\AppData\Local\Temp\opencode\airbnb\base_decoded\AndroidManifest.xml"
root = ET.parse(p).getroot()

def a(el, k):
    return el.get(A + k)

print("== ملخص الحزمة ==")
print("package       :", root.get("package"))
print("versionName/Code:", a(root, "versionName"), "/", a(root, "versionCode"))
print("minSdk/target :", a(root, "minSdkVersion"), "/", a(root, "targetSdkVersion"))
print("compileSdk    :", a(root, "compileSdkVersion"))
print("splitTypes    :", a(root, "splitTypes"), "| requiredSplitTypes:", a(root, "requiredSplitTypes"))

perms = [a(e, "name") for e in root.findall("uses-permission")]
permsM = [a(e, "name") for e in root.findall("uses-permission-sdk-23")]
print("\n== uses-permission (%d) والمواد الخطرة ===" % len(perms))
DANGER = ("LOCATION", "CAMERA", "RECORD_AUDIO", "CONTACTS", "SMS", "CALL", "PHONE_STATE",
          "STORAGE", "MEDIA", "BLUETOOTH_CONNECT", "QUERY_ALL_PACKAGES", "READ_GSERVICES",
          "AD_ID", "POST_NOTIFICATIONS", "PACKAGE_USAGE", "SYSTEM_ALERT", "WRITE_SETTINGS",
          "BODY_SENSORS", "ACTIVITY_RECOGNITION", "NEARBY", "BLUETOOTH_SCAN", "ACCESS_FINE")
for x in sorted(set(perms)):
    mark = " **" if any(d in x for d in DANGER) else "   "
    print("  %s %s" % (mark, x))
if permsM:
    print("uses-permission-sdk-23:", sorted(set(permsM)))

print("\n== الأذونات المعرَّفة (permission) ==")
for e in root.findall("permission"):
    print("  %-60s protectionLevel=%s" % (a(e, "name"), a(e, "protectionLevel")))

app = root.find("application")
print("\n== سمات application ==")
for k in ("debuggable", "allowBackup", "usesCleartextTraffic", "networkSecurityConfig",
          "extractNativeLibs", "requestLegacyExternalStorage", "useEmbeddedDex",
          "name", "label", "theme", "largeHeap", "testOnly"):
    v = a(app, k)
    if v is not None:
        print("  %-26s %s" % (k, v))

def comps(tag):
    out = []
    for e in app.findall(tag):
        filters = []
        for f in e.findall("intent-filter"):
            for ac in f.findall("action"):
                filters.append(a(ac, "name"))
        out.append((a(e, "name"), a(e, "exported"), a(e, "authorities"), a(e, "permission"), filters))
    return out

print("\n== المكوّنات ==")
for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
    cs = comps(tag)
    exp = [c for c in cs if c[1] == "true"]
    print("  %-15s total=%-4d exported=%d" % (tag, len(cs), len(exp)))

print("\n== المكوّنات المُصدَّرة exported=true (مع الأذونات والـintent) ==")
for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
    for name, exported, auth, perm, filters in comps(tag):
        if exported == "true":
            extra = ""
            if auth:
                extra += " authorities=%s" % auth
            if perm:
                extra += " permission=%s" % perm
            acts = sorted(set(x for x in filters if x and not x.startswith("android.intent.action.MAIN")))
            info = (" intents=%s" % acts) if acts else ""
            print("  [%s] %s%s%s" % (tag, name, extra, info))
