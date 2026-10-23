# Spacing, layout metrics, and radii

## `dimen` inventory

`res/values/dimens.xml` contains **1,129** `<dimen>` entries. Of these, **870** are literal `dp` values; 46 are `sp` values, one is `px`, and the remainder are references or aliases. The top `dp` values:

| Value | Count |
|---|---:|
| 4dp | 67 |
| 8dp | 65 |
| 16dp | 56 |
| 12dp | 52 |
| 0dp | 50 |
| 2dp | 42 |
| 24dp | 39 |
| 1dp | 34 |
| 6dp | 29 |
| 10dp | 26 |
| 32dp | 23 |
| 48dp | 22 |
| 20dp | 19 |
| 40dp | 17 |

The base rhythm is 4dp, but 2dp, 6dp, and 10dp are common. This is a dense spacing collection rather than a strict 4dp scale.

## Measured layout values

| Metric | Value | Source |
|---|---:|---|
| Screen width | 1,080 px / 411.4dp | device |
| Screen height | 2,400 px / 914.3dp | device |
| Density | 2.625 px/dp | device |
| Settings horizontal margin | 12dp (31.5px) | settings.xml |
| Auth horizontal margin | 24dp (63px) | signin.xml |
| Settings row height | 48dp (126px) | settings.xml |
| Bottom navigation height | 57.9dp (152px) | me.xml / screenshots |
| Bottom navigation item width | 82.3dp (216px) | me.xml |
| Me card horizontal margin | 5.7dp (15px) | me.xml |
| Error CTA | 160×36.2dp (420×95px) | s12 |
| Dialog card | 329×143dp (864×376px) | s22 |
| Dialog button | 142.5×36.2dp (374×95px) | dialog.xml |

## Radii and shapes

There are **no named radius/corner tokens** in the analyzed resources. Shapes are defined per drawable or per layout:

| Element | Observed shape |
|---|---|
| Auth input | Rectangle, 0dp corner |
| Auth CTA | Rectangle, 0dp corner |
| Error CTA | Rectangle, 0dp corner |
| Shop search | Pill, ~100dp radius |
| Category search | Square-cornered gray rectangle |
| Trends search | Square-cornered white rectangle inside black header |
| Settings cards/rows | Flat white rectangles, minimal/no shadow |
| Cache dialog | Rounded card, ~16dp radius |
| Profile avatar placeholder | 28×27dp uniform pink square |

The absence of a radius scale is visible in the screenshots: the search field changes from a pill to a rectangle between Shop and Category, and the auth surface uses sharp corners while the dialog is rounded.

## Caveats

- The 870 `dp` literal count excludes references such as `@dimen/...`; it is not the total number of spacing decisions.
- Some dimensions are computed at runtime and are not represented in `dimens.xml`.
- The screenshot-derived measurements are pixel counts divided by 2.625; anti-aliasing can shift a measurement by ±1px.
- No tablet, foldable, or landscape layout was inspected.
