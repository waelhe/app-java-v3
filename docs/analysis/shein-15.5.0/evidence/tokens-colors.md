# Color tokens and observed surfaces

## Resource inventory

`res/values/colors.xml` contains **1,648** `<color>` entries. The most frequent raw values are listed below. Counts are occurrences of the literal value, not unique semantic roles.

| Value | Count |
|---|---:|
| `#000000` | 20 |
| `#FFFFFF` | 18 |
| `#666666` | 13 |
| `#222222` | 10 |
| `#E5E5E5` | 10 |
| `#F93A00` | 10 |
| `#FA6338` | 10 |
| `#F6F6F6` | 9 |
| `#959595` | 8 |
| `#1F000000` | 8 |
| `#D62000` | 8 |
| `#A63E00` | 7 |
| `#F82854` | 7 |
| `#99000000` | 6 |
| `#E6E6E6` | 6 |

Additional brand-adjacent values observed in resources include `#D62000` (deep red), `#F93A00` / `#FA6338` (orange), `#A63E00` (dark amber), and `#F82854` (pink-red). The captured shopping surfaces do not use these as dominant colors; they are primarily black/white/gray. This may reflect the captured failure states rather than the intended brand palette.

## Night resources

`res/values-night/colors.xml` includes:

| Role (observed name/value) | Value |
|---|---|
| Night surface | `#111111` |
| Elevated night surface | `#1E1E1E` |
| Night divider/secondary | `#48484B` |
| Night primary text | `#FFFFFF` |
| Night secondary text | `#DADDE0` |

Night mode was not exercised on device. These are resource observations only.

## Observed surfaces

Pixel counts are sampled from the 1,080×2,400 screenshots with a 4-pixel step (270×600 sample grid; ~162k samples). They are comparative, not exact screen-area percentages.

| Screen | Surface | Sampled count | Screenshot |
|---|---|---:|---|
| Shop error | `#F6F6F6` | 25,180 | s12 |
| Category error | `#F6F6F6` | 26,071 | s13 |
| Trends error | `#F6F6F6` | 23,295 | s14 |
| Cart error | `#FFFFFF` | 28,425 | s15 |
| Me | `#EFF0F3` | 20,923 | s16 |
| Settings | `#FFFFFF` | 21,146 | s21 |
| Auth | `#FFFFFF` | 27,451 | s19 |
| Search | `#FFFFFF` | — | s34 |

## Observed semantic colors

| Role | Value | Evidence |
|---|---|---|
| Primary text | `#000000` | s12–s16, s21 |
| Secondary text | `#666666` | s12, s16, s21 |
| Tertiary/disabled text | `#959595` | s12, s21 |
| Link | `#2D68A8` | s17–s20 |
| Facebook row | `#3949AB` | s17, s19 |
| Divider | `#E5E5E5` | s17, s19 |
| Auth CTA | `#222222` | s17, s19, s20 |
| Error CTA | `#000000` | s12, s28, s33 |
| Dialog scrim | `#666666` (translucent black over white) | s22 |
| Account placeholder | `#FFEBEE` | s21, s32 |
| Trust/security | `#198055` | s17, s19, s20 |

## Contrast checks

Computed with WCAG 2.1 relative luminance.

| Foreground | Background | Ratio | Result |
|---|---|---:|---|
| `#666666` | `#F6F6F6` | 5.31:1 | Passes AA normal text |
| `#959595` | `#F6F6F6` | 2.77:1 | Fails AA normal text |
| `#000000` | `#F6F6F6` | 19.43:1 | Passes AAA |
| `#2D68A8` | `#FFFFFF` | 5.74:1 | Passes AA normal text |
| `#FFFFFF` | `#222222` | 15.91:1 | Passes AAA |
| `#767676` | `#FFFFFF` | 4.54:1 | Passes AA normal text (borderline) |
| `#FFFFFF` | `#000000` | 21.00:1 | Maximum |

## Caveats

- The APK’s color names are obfuscated; no reliable semantic token names survive in the decoded resources.
- The same value can serve multiple roles; counts do not prove role reuse.
- `#FFEBEE` is treated as a placeholder surface because it appears as a uniform square where an account icon should be, in both English and Arabic Settings.
- No dark-mode screenshots were captured, so night contrast is not verified.
