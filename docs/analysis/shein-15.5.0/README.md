# SHEIN 15.5.0 — UI/UX Study Archive

Closed analytical archive for the SHEIN Android client (`com.zzkko`, version `15.5.0`, versionCode `2941`).

> **Status:** draft PR opened and closed; not merged. This directory preserves the study, screenshots, and token measurements as a historical reference. It is not an approved design system or an implementation plan.

## Contents

| Path | Description |
|---|---|
| `reports/SHEIN_15.5.0_UI_UX_study.md` | Full Arabic report: methodology, design system, IA, inventory, findings, recommendations |
| `reports/SHEIN_15.5.0_UI_UX_study.html` | RTL HTML edition with embedded styling and relative screenshots |
| `reports/SHEIN_15.5.0_UI_UX_study.pdf` | Print edition generated from the HTML |
| `evidence/apk-metadata.md` | Package identity, build counts, APK hashes |
| `evidence/tokens-colors.md` | Color resource counts, observed surfaces, contrast checks |
| `evidence/tokens-typography.md` | Font faces, Arabic coverage, `sp` distribution |
| `evidence/tokens-spacing.md` | `dp` distribution, row/nav/margin measurements, radii |
| `evidence/touch-targets.md` | Touch-target interpretation |
| `evidence/touch-targets.csv` | Raw `uiautomator` measurements |
| `evidence/screen-inventory.md` | Screenshot inventory and coverage gaps |
| `evidence/screenshots.sha256` | SHA-256 for every screenshot |
| `evidence/ui-dumps.sha256` | SHA-256 for every UI hierarchy dump |
| `evidence/ui-dumps/` | Raw UI hierarchy dumps (`me`, `signin`, `settings`, `dialog`) |
| `evidence/screenshots/` | 30 PNG files: 29 application screenshots + 1 environment capture (`s00`) |

## Scope

- Static inspection of the APK: manifest, resources, layouts, styles, fonts.
- 29 application screenshots in English and Arabic, plus one environment capture (`s00-system-noise.png`).
- Four `uiautomator` hierarchy dumps for touch-target measurements.
- Device profile: 1,080×2,400 px, 420 dpi, 2.625 px/dp.

Not covered: live product feed, PDP/PLP, full cart, checkout, payment, authenticated account, dark mode, TalkBack, font scaling, tablets/foldables, motion, and production performance.

## Key findings

1. A global `OOPS!` state replaces every shopping tab with no offline path, cache fallback, error code, or support route.
2. Authentication accepts an email but shows no validation, loading, or error after `Continue`.
3. `Change Currency` and Arabic `Search` render blank; missing Google Play services produces a black screen with a truncated toast.
4. Headers and search fields differ across Shop, Category, Trends, Cart, and Me.
5. Recurrent touch targets are 21–36 dp: auth back, profile actions, dialog buttons, and the error CTA.
6. The local `Adieu` font has no Arabic `U+0627` coverage; Arabic falls back to the system face.
7. RTL mirroring is mostly correct, but copy quality and wrapping need work (`الاراء الكاملة`, `تعليق`, `المنتجات المسترجعة`).
8. Settings shows a pink `#FFEBEE` placeholder instead of an account icon.
9. Surfaces, header treatments, and corner radii are fragmented without semantic tokens.
10. `NO`/`YES` in the cache dialog are ambiguous, and the destructive action is not visually distinct.

## Evidence rules

Values taken from resources are labeled as resource values; values taken from screenshots or UI dumps are labeled as observed values. System ANR dialogs and the `Pixel Launcher` / `System UI` overlays are environment noise and are not attributed to SHEIN.

## Reuse

The report and evidence may be cited internally. Do not treat any recommendation as approved scope. The archive is intentionally not merged.
