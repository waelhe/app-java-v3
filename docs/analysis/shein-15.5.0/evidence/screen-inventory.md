# Screenshot inventory

## Coverage

- 29 application screenshots: `s02`, `s04`, `s05`, `s09`–`s34`.
- 1 environment capture: `s00-system-noise.png` (Pixel Launcher ANR over the app). Excluded from product findings.
- Missing identifiers: `s01`, `s03`, `s06`, `s07`, `s08`. They are not evidence and must not be referenced as if captured.
- Resolution: 1,080×2,400; density 2.625; Android emulator.
- Total: 30 PNG files in `screenshots/`; checksums in `../screenshots.sha256`.

## Inventory

| ID | File | Language | Screen / state | Notes |
|---|---|---|---|---|
| s00 | `screenshots/s00-system-noise.png` | EN | Pixel Launcher ANR over app error | Environment noise; excluded |
| s02 | `screenshots/s02.png` | EN | Shop error + System UI ANR | Environment overlay; partially excluded |
| s04 | `screenshots/s04.png` | EN | Shop error, clean | Full header + bottom nav |
| s05 | `screenshots/s05.png` | EN | Shop error, repeat | Confirms state |
| s09 | `screenshots/s09.png` | EN | Notification permission dialog over Shop error | System dialog |
| s10 | `screenshots/s10.png` | EN | `OOPS!` without header/nav | Standalone error |
| s11 | `screenshots/s11.png` | EN | `OOPS!` standalone repeat | Confirms state |
| s12 | `screenshots/s12.png` | EN | Shop error | Pill search + mail/heart |
| s13 | `screenshots/s13.png` | EN | Category error | Rectangular gray search |
| s14 | `screenshots/s14.png` | EN | Trends error | Black `Trends Store` header |
| s15 | `screenshots/s15.png` | EN | Cart error | No header; bottom nav |
| s16 | `screenshots/s16.png` | EN | Me, signed out | Sign in, QR, settings, counters |
| s17 | `screenshots/s17.png` | EN | Auth, empty | Logo, email, Continue, Facebook |
| s18 | `screenshots/s18.png` | EN | Auth, input/keyboard | Entry state |
| s19 | `screenshots/s19.png` | EN | Auth, email entered | `test@example.com` |
| s20 | `screenshots/s20.png` | EN | Auth, after Continue | No validation/loading/error visible |
| s21 | `screenshots/s21.png` | EN | Settings | 48dp rows; pink placeholder |
| s22 | `screenshots/s22.png` | EN | Clear Cache dialog | NO/YES at 36.2dp |
| s23 | `screenshots/s23.png` | EN | Settings after dialog | Confirms return state |
| s24 | `screenshots/s24.png` | EN | Change Currency | Blank white |
| s25 | `screenshots/s25.png` | EN | Change Currency repeat | Blank white |
| s26 | `screenshots/s26.png` | EN | Black screen | GMS/network failure state |
| s27 | `screenshots/s27.png` | EN | Shop error + warning icons | System status noise |
| s28 | `screenshots/s28.png` | AR | Shop error | RTL; `حاولي مرة اخرى` |
| s29 | `screenshots/s29.png` | AR | Me, signed out | RTL; copy issues |
| s30 | `screenshots/s30.png` | AR | Auth + GMS toast over black | Black screen; truncated toast |
| s31 | `screenshots/s31.png` | AR | Me repeat/angle | Confirms RTL |
| s32 | `screenshots/s32.png` | AR | Settings | RTL; pink placeholder |
| s33 | `screenshots/s33.png` | AR | About → Shop error | Leaves Settings |
| s34 | `screenshots/s34.png` | AR | Search | Blank + keyboard; no history/suggestions |

## Missing coverage

- `s03`, `s06`, `s07`, `s08`: not captured; no claims are made about them.
- No PDP, PLP, search results, populated cart, checkout, payment, orders, wishlist content, or authenticated profile.
- No dark mode, landscape, tablet, foldable, or large-font screenshots.
- No TalkBack/focus-order capture.

## Relevance

The screenshot set is sufficient to establish the shell/navigation issues, the error and empty states, the auth dead end, and the touch-target measurements. It is not sufficient to judge the full shopping experience or the intended brand palette.
