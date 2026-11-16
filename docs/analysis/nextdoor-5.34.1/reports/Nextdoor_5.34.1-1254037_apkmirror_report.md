# تقرير التحقق — Nextdoor 5.34.1 (APKMirror APKM)

**تاريخ التقرير:** 2026-10-16
**الملف:** `C:\Users\w-co\Downloads\com.nextdoor_5.34.1-1254037_1arch_2dpi_8lang_56c1285e8915c2a1c0b48051bbba7e7e_apkmirror.com.apkm`
**الحكم:** ✅ **سليم 100% — قابل للتركيب/الاستخدام**

---

## 1) الهوية (SHA-256)

| البند | القيمة |
|---|---|
| الحجم | `82,627,875` بايت (78.8 MiB) |
| SHA-256 | `300402c56784d8943c5a126be788ca46fa86c319cbdfee7a459aa63c9db64e5f` |
| Magic | `50 4B 03 04` (ZIP سليم) |
| آخر تعديل | 2026-10-16 06:19:22 |

## 2) إثبات السلامة
- `tar -tf` → سرد كامل لجميع المدخلات دون خطأ.
- ‎.NET / Python `zipfile` → **`testzip: ALL CRC OK`** (فحص CRC لكل مدخل).
- 18 مدخلاً، لا مدخل تالف.

## 3) المحتوى (18 مدخلاً)

| المدخل | مضغوط | غير مضغوط | الطريقة |
|---|---|---|---|
| `META-INF/MANIFEST.MF` | 541 | 1,117 | deflate |
| `META-INF/APKMIRRO.SF` | 609 | 1,238 | deflate |
| `META-INF/APKMIRRO.RSA` | 1,191 | 1,597 | deflate |
| `info.json` | 315 | 819 | deflate |
| `icon.png` | 3,149 | 4,726 | deflate |
| `base.apk` | 58,543,891 | 121,288,070 | deflate |
| `split_config.arm64_v8a.apk` | 19,879,740 | 49,243,398 | deflate |
| `split_config.xxxhdpi.apk` | 1,699,073 | 2,516,790 | deflate |
| `split_config.xxhdpi.apk` | 1,184,704 | 1,365,768 | deflate |
| `split_config.fr.apk` | 429,945 | 885,258 | deflate |
| `split_config.en.apk` | 182,540 | 610,514 | deflate |
| `split_config.de.apk` | 137,849 | 442,578 | deflate |
| `split_config.es.apk` | 136,079 | 438,482 | deflate |
| `split_config.it.apk` | 134,155 | 434,386 | deflate |
| `split_config.nl.apk` | 134,037 | 426,194 | deflate |
| `split_config.sv.apk` | 132,970 | 422,098 | deflate |
| `split_config.fi.apk` | 24,709 | 102,610 | deflate |
| `APKM_installer.url` | 58 | 58 | stored |

## 4) بيانات `info.json` (مصدر APKMirror)

```json
{
  "apkm_version": 5,
  "apk_title": "Nextdoor: Neighborhood network 5.34.1 (arm64-v8a) (480-640dpi) (Android 10+)",
  "app_name": "Nextdoor: Neighborhood network",
  "release_version": "5.34.1",
  "variant": "(arm64-v8a) (480-640dpi) (Android 10+)",
  "versioncode": "1254037",
  "pname": "com.nextdoor",
  "post_date": "2026-10-14 02:48:53",
  "languages": ["de","en","es","fi","fr","it","nl","sv"],
  "arches": ["arm64-v8a"],
  "dpis": ["480","640"],
  "min_api": "29",
  "apk_id": 16581208,
  "release_id": 16580451
}
```

## 5) التوقيع
- **توقيع الحزمة (APKMirror):** `META-INF/APKMIRRO.{SF,RSA}` + `MANIFEST.MF` (v1 JAR signature على مستوى الـ`.apkm`).
- **توقيع `base.apk`:** APK Signature Scheme **v2** (‎`id=0x7109871a`‎)؛ v1 غير موجود؛ الشهادة SHA-256 `6b0039757b3666404626f2d606ee17b56ad47770f5aebad2d7b7326882190dbd`.

## 6) أوامر التحقق (لإعادة الإنتاج)
```powershell
Get-FileHash -LiteralPath "<file>" -Algorithm SHA256   # يجب أن يطابق 300402c5...
tar -tf "<file>"                                        # يجب أن يسرد 18 مدخلاً
python -c "import zipfile;print(zipfile.ZipFile(r'<file>').testzip())"   # يجب أن يطبع None
```

## 7) المصدر
APKMirror — `apk_id=16581208` · `release_id=16580451` · `post_date=2026-10-14 02:48:53`.
نطاق التوزيع المُعلَن: arm64-v8a · كثافات 480/640dpi · Android 10+ (min API 29) · 8 لغات.
