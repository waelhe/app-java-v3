# Touch-target audit

## Method

`uiautomator dump` captured the actual accessibility/clickable bounds in pixels. Values were divided by the emulator density (2.625 px/dp). The threshold is **48×48dp** for independent touch targets. When a target is a compound row (for example a Settings row), the outer clickable parent was measured rather than the text.

Raw values: `touch-targets.csv`.

## Summary

| Result | Count |
|---|---:|
| Passing ≥48dp | 3 |
| Failing <48dp | 12 |

The failures cluster around four patterns:

1. **Icon-only header actions** at 24dp (auth back, Me QR, Me settings).
2. **Text links** with a visually large label but a small clickable height (sign-in 21.3dp, View all 16.4dp).
3. **Dialog and error buttons** at 36.2dp.
4. **Form controls** at 44–46dp, close to but below the threshold.

## Detailed results

| Screen | Element | Size | Result | Note |
|---|---|---:|---|---|
| Auth | Back (`CLOSE`) | 24×24dp | Fail | Smallest target; wrong accessibility label |
| Auth | Email input | 363.4×44.2dp | Fail | 3.8dp short |
| Auth | Continue | 363.4×45.0dp | Fail | 3.0dp short |
| Auth | Facebook | 363.4×46.1dp | Fail | 1.9dp short |
| Me | Sign in / Register | 322.3×21.3dp | Fail | Text-only click target |
| Me | Scan QR | 24×24dp | Fail | Icon-only |
| Me | Settings | 24×24dp | Fail | Icon-only |
| Me | View all | 120×16.4dp | Fail | Very small height |
| Me | Wishlist stats | 132.6×41.1dp | Fail | 6.9dp short |
| Settings | Back | 48×48dp | Pass | Exactly at threshold |
| Settings | Row 1 | 411.4×48dp | Pass | Full-width row |
| Bottom nav | Me item | 82.3×57.9dp | Pass | Good |
| Dialog | NO | 142.5×36.2dp | Fail | 11.8dp short |
| Dialog | YES | 142.5×36.2dp | Fail | 11.8dp short |
| Error | Try it Again | 160×36.2dp | Fail | 11.8dp short |

## Interpretation

- **Not every small icon is a failure** if its parent row is clickable. Here, the Me QR/settings icons are directly clickable at 24dp; the parent header around them is not the measured target.
- **Sign in / Register** is the clearest case of visual size diverging from hit area: the text is large, but only 56px (21.3dp) high is clickable.
- **`View all`** is a text link with 43px height. Increasing its clickable frame to 48dp would not change the visual design if padding is used.
- **Dialog buttons** have 95px height because the dialog style compresses them. The visible button is not tiny, but the hit frame is below 48dp.
- **Error CTA** is 95px high (36.2dp) despite being the only recovery action on the screen. It should be at least 48dp, and preferably 52–56dp for a primary action.

## Recommendations

- Wrap icon actions in 48dp containers without changing icon size.
- Use `minHeight=48dp` and vertical padding for text links and dialog buttons.
- Keep the Settings row and bottom-nav patterns; they already meet the threshold.
- Add a UI test that asserts the clickable bounds of the primary actions on Auth, Error, Dialog, and Me.
- Do not rely on `content-desc` alone for size; the clickable node is the measurement target.
