# Typography and font coverage

## Font files

`res/font/` contains 17 files:

- Four font binaries: three obfuscated `Adieu` files plus `shein.ttf`.
- Three downloadable font-family XMLs that request **Roboto** through Google Play services:
  - `Roboto`
  - `Roboto:wght700`
  - `Roboto:ital1`
- Ten local font-family XMLs that declare weights:
  - `300`, `370`, `450`, `500`, `510`, `550`, `590`, `600`, `858`, `950`.

## Observed faces

The embedded family is **Adieu**, with four named faces:

| Face | Role |
|---|---|
| `Adieu Light` | Light display/large text |
| `Adieu Regular` | Body/default |
| `Adieu Bold` | Emphasis and CTA |
| `Adieu Black` | Large promotional/display |

The local family is Latin-only for practical purposes. A `cmap` check for the Arabic letter alef (`U+0627`) returned **false** for every Adieu face. Arabic therefore falls back to the Android system Arabic font (typically Noto Naskh Arabic / Noto Sans Arabic depending on OEM). This produces a mixed-script typographic system: Latin uses Adieu, Arabic uses the platform face.

## `sp` token distribution

`res/values/dimens.xml` contains **46** `sp` tokens:

| Size | Count |
|---|---:|
| 14sp | 7 |
| 12sp | 6 |
| 20sp | 5 |
| 16sp | 4 |
| 10sp | 4 |
| 13sp | 3 |
| 11sp | 3 |
| 22sp | 2 |
| 24sp | 2 |
| 18sp | 2 |
| 15sp | 2 |
| 36sp | 1 |
| 8sp | 1 |
| 9sp | 1 |

## Reading

- The body/label cluster is **10–14sp**; 12sp and 14sp dominate.
- Headings cluster at **18–24sp**; 36sp is the single largest display token.
- 8sp, 9sp, and 10sp tokens exist. At a 2.625 density these are physically small; pairing them with `#959595` compounds readability problems.
- The scale is not a clean 2× or 4× type ramp. It behaves like a product-specific collection of label sizes, which matches the dense commerce UI.

## Arabic rendering implications

- Because Adieu lacks Arabic glyphs, line height, weight, and letterform proportions change when the locale switches to Arabic.
- The `font-family` weight XMLs may request weights that the platform Arabic fallback cannot synthesize consistently.
- The Arabic screens (s28, s29, s31, s32, s34) show a visibly different texture from the English screens (s12–s21), especially in headings and tab labels.
- No Arabic font file is bundled in the base APK. The only Arabic-capable face available at runtime is the system fallback (or a downloadable font if one is configured elsewhere, which was not observed).

## Caveats

- The exact optical size and line-height multipliers were not extracted from every `TextView`; the report treats the resource tokens and visible results as the primary evidence.
- Font licensing and redistribution were not audited.
- Text scaling (200%) was not tested.
