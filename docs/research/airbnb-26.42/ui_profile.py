import os, re, sys, glob
from collections import Counter
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
BASE = r"D:\rea-airbnb\base_decoded"
JADX = r"D:\rea-airbnb\jadx_out\sources"
RES = os.path.join(BASE, "res")

def rd(p):
    try:
        return open(p, encoding="utf-8", errors="replace").read()
    except Exception:
        return ""

print("== 1) توزيع مجلدات الموارد ==")
for d in sorted(os.listdir(RES)):
    fp = os.path.join(RES, d)
    if os.path.isdir(fp):
        n = len(os.listdir(fp))
        if d.startswith(("layout", "navigation", "menu", "anim", "xml", "values", "drawable", "color")):
            print("   %-28s %5d ملف" % (d, n))
print("   layout (إجمالي):", len(glob.glob(os.path.join(RES, "layout*", "*.xml"))))
print("   navigation:", len(glob.glob(os.path.join(RES, "navigation", "*.xml"))))
print("   menu:", len(glob.glob(os.path.join(RES, "menu", "*.xml"))))

print("\n== 2) لوحة الألوان (colors.xml) ==")
c = rd(os.path.join(RES, "values", "colors.xml"))
cols = re.findall(r'<color name="([^"]+)">(#?[^<]+)</color>', c)
print("   عدد الألوان =", len(cols))
for n, v in cols[:25]:
    print("   %-38s %s" % (n, v))

print("\n== 3) رموز نظام التصميم DLS (design language system) ==")
dls_colors = [n for n, _ in cols if n.lower().startswith("dls")]
print("   ألوان dls =", len(dls_colors))
for n in dls_colors[:20]:
    print("     ", n)
dimens = rd(os.path.join(RES, "values", "dimens.xml"))
dls_dim = re.findall(r'<dimen name="([^"]+)">', dimens)
print("   dimens كلي =", len(dls_dim), "| منها dls =", len([x for x in dls_dim if x.startswith("dls")]))
styles = rd(os.path.join(RES, "values", "styles.xml"))
st = re.findall(r'<style name="([^"]+)"', styles)
print("   أنماط styles =", len(st))
dls_st = [x for x in st if "Dls" in x or x.startswith("dls")]
print("   أنماط DLS =", len(dls_st))
for x in dls_st[:15]:
    print("     ", x)

print("\n== 4) الطباعة والسمات ==")
print("   ملفات themes:", [os.path.basename(x) for x in glob.glob(os.path.join(RES, "values*", "*theme*.xml"))][:12])
tt = re.findall(r'<item name="android:textSize">([^<]+)</item>', styles)
print("   عيّنات textSize:", tt[:10])
fonts = glob.glob(os.path.join(RES, "font*", "*"))
print("   ملفات الخطوط:", [os.path.basename(x) for x in fonts][:12])

print("\n== 5) إمكانية الوصول (a11y) والتوطين ==")
s = rd(os.path.join(RES, "values", "strings.xml"))
print("   نصوص a11y_* =", len(re.findall(r'name="a11y_', s)))
print("   مجموعات RTL/plurals:", "strings.xml صفوف =", s.count("<string "), "| plurals =", len(re.findall(r'<plurals ', s)))

print("\n== 6) Jetpack Compose (من الكود المُفكَّك) ==")
comp = 0
comp_files = 0
screens = 0
pkg = Counter()
for dp, dn, fn in os.walk(JADX):
    for f in fn:
        if not f.endswith(".java"):
            continue
        p = os.path.join(dp, f)
        t = rd(p)
        if "@Composable" in t:
            comp_files += 1
            comp += t.count("@Composable")
            rel = os.path.relpath(dp, JADX)
            pkg[".".join(rel.split(os.sep)[:3])] += t.count("@Composable")
        if re.search(r'class \w*Screen', t):
            screens += 1
print("   ملفات فيها @Composable =", comp_files, "| إجمالي @Composable =", comp)
print("   أصناف *Screen =", screens)
print("   أعلى الحزم بـCompose:")
for k, v in pkg.most_common(12):
    print("     %6d  %s" % (v, k))
