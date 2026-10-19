import re, json, io, os

UI = r"D:\rea-beeorder\ui"
TOK = os.path.join(UI, "dart_tokens.txt")
EN  = os.path.join(UI, "clean_lang", "en.json")
AR  = os.path.join(UI, "clean_lang", "ar.json")

toks = [l.rstrip("\n") for l in open(TOK, encoding="utf-8") if l.strip()]
tset = set(toks)

def load_json(p):
    return json.loads(open(p, "rb").read().decode("utf-8", "replace"))

en = load_json(EN)
ar = load_json(AR)
en_keys = list(en.keys())
ar_keys = list(ar.keys())

def w(name, seq):
    p = os.path.join(UI, name)
    with open(p, "w", encoding="utf-8") as f:
        for s in seq:
            f.write(s + "\n")
    return len(seq)

# 1. SCREAMING_SNAKE constants
screaming = sorted(t for t in toks if re.fullmatch(r"[A-Z][A-Z0-9_]{2,}", t))
# 2. PascalCase identifiers
pascal = sorted(t for t in toks if re.fullmatch(r"[A-Z][A-Za-z0-9]{2,}", t))
SUF = ("Model","Page","View","ViewModel","Store","Card","Sheet","Bloc","Provider","Controller",
       "Screen","State","Exception","Service","Repository","Handler","Manager","Helper","Widget",
       "Dialog","Delegate","Response","Request","Event","Args","Config","Util","Extension","Type",
       "Data","Item","Info","Snapshot","Cubit","Router","Routes","Socket","Mapper","Entity","Repo")
classes = [t for t in pascal if t.endswith(SUF)]
APP = ("Bee","Order","Cart","Address","Voucher","Restaurant","Dish","Wallet","Subscription","Survey",
       "Feedback","Invite","Referral","Onboarding","Tracking","Checkout","Coupon","Point","Redeem",
       "Scratch","Pay","Payment","Category","Cuisine","City","Country","Branch","Favorit","Notif",
       "Socket","Cluster","Loyalty","Reward","Achievement","Profile","Search","Home","Offer","Banner",
       "Filter","Rating","Review","Chat","Group","Driver","Captain","Picker","Map","Locale","Theme",
       "Auth","Login","Otp","Signup","Session","Token","User","Guest","Splash","Intro","Permission",
       "Location","Delivery","Pickup","Vehicle","Refund","Dispute","Issue","Ticket","Support","Faq")
app_classes = [t for t in pascal if any(a in t for a in APP)]
# 3. snake_case keys (i18n-ish)
snake = sorted(t for t in toks if re.fullmatch(r"[a-z][a-z0-9]*(_[a-z0-9]+)+", t))
snake_in_en = [t for t in snake if t in en]
en_not_tok = [k for k in en_keys if k not in tset]
snake_not_en = [t for t in snake if t not in en]
# 4. camelCase functions / lower identifiers of interest
camel_app = sorted(t for t in toks if re.fullmatch(r"[a-z][A-Za-z0-9]{3,}", t)
                   and any(a.lower() in t.lower() for a in APP))

res = {
  "screaming": w("dart_constants.txt", screaming),
  "classes_all": w("dart_classes_all.txt", classes),
  "app_classes": w("dart_app_classes.txt", app_classes),
  "snake": w("dart_snake.txt", snake),
  "snake_in_en": w("dart_snake_in_en.txt", snake_in_en),
  "snake_not_en": w("dart_snake_not_en.txt", snake_not_en),
  "camel_app": w("dart_camel_app.txt", camel_app),
  "en_keys": w("en_keys.txt", en_keys),
  "en_not_in_tokens": w("en_keys_not_in_dart.txt", en_not_tok),
  "ar_keys": w("ar_keys.txt", ar_keys),
}
print(json.dumps(res, indent=1))
print("--- APP CLASSES (%d) ---" % len(app_classes))
print(", ".join(app_classes))
print("--- SCREAMING (%d) ---" % len(screaming))
print(", ".join(screaming))
