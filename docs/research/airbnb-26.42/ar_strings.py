import sys, re
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
p = r"D:\rea-airbnb\ar_decoded\res\values-ar\strings.xml"
t = open(p, encoding="utf-8", errors="replace").read()
items = re.findall(r'<string name="([^"]+)">(.*?)</string>', t, re.S)
arab = [(n, v) for n, v in items if re.search(r"[\u0600-\u06FF]", v)]
print("ملف:", p)
print("عدد strings الكلي =", len(items), "| منها عربية فعلية =", len(arab), "| حجم =", len(t), "حرف")
vals = [v for _, v in items if re.search(r"[\u0600-\u06FF]", v)]
avg = sum(len(v) for v in vals) / max(len(vals), 1)
print("متوسط طول النص العربي = %.1f حرف" % avg)
for n, v in arab[:20]:
    print("   %-42s %s" % (n[:42], v[:70]))
