# Meridian design guide

The reference for what exists in `:meridian-compose`. For how to use it when building a
screen, see [`design-playbook.md`](design-playbook.md).

Every value below has exactly one source in code: `MeridianTokens.kt` for colour,
`MeridianTypography.kt` for type, `MeridianShapes.kt` for radius, `MeridianSpacing.kt`
for spacing. This document restates them for reference; the code is the source of
truth — if the two disagree, the code wins and this file is stale.

## Colour tokens

Fifteen roles, each defined once in `MeridianTokens.kt` for a light ground and again
for a dark one. Nothing outside that file may contain a `Color(0x…)` literal —
`scripts/check-meridian-tokens.sh` fails the build otherwise. Consumers never reach for
a hex; they read `MeridianTheme.colors.<role>`.

| Role | Light | Dark | Use |
| --- | --- | --- | --- |
| `canvas` | `#F4F5F7` | `#0F1014` | Screen background |
| `surface` | `#FFFFFF` | `#17181D` | Card and control fill |
| `stroke` | `#ECEDF1` | `#2A2C35` | 1px borders — the only source of structure |
| `tint` | `#EEF0FB` | `#1D2739` | Icon tile fill, chip/badge containers |
| `ink` | `#1B1D28` | `#EDEEF2` | Primary text |
| `inkMuted` | `#8A8D9B` | `#9B9EAC` | Secondary text, captions, placeholders |
| `brand` | `#1B62C9` | `#6BA5F0` | The one blue — primary actions, links, in-flight status |
| `brandStrong` | `#154FA6` | `#8FBDF5` | Pressed/emphasis variant of brand |
| `onBrand` | `#FFFFFF` | `#0F1014` | Text/icons on a brand fill |
| `onBrandMuted` | `#DCE8FF` | `#16324F` | Secondary text on a brand fill |
| `strong` | = `ink` | = `ink` | Reuses ink; no separate navy |
| `positive` | `#2E9E5B` | `#5FC98D` | Connected, synced, complete |
| `warning` | `#E08A1E` | `#F0AD52` | Degraded but working |
| `critical` | `#D64545` | `#EE7C7C` | Failed, lost, rejected |
| `info` | = `brand` | = `brand` | In-flight — not an alarm, so it shares brand |

**There is exactly one blue.** `brand` and `info` are the same value on purpose: an
in-progress transfer is not a warning state, so it gets the calm colour, not a distinct
accent.

Status colour is never the only cue — every status carries a word or an icon
(`MeridianStatusPill`'s `label` parameter is non-optional for this reason).

### Material 3 role mapping

Every `ColorScheme` role is mapped in `MeridianColors.toColorScheme()`, in light and
dark, in the same edit — an unmapped role silently renders Material's baseline
lavender on the first stock component that reaches for it.

| M3 role | Mapped to |
| --- | --- |
| `primary` / `onPrimary` | `brand` / `onBrand` |
| `primaryContainer` / `onPrimaryContainer` | `tint` / `brandStrong` (light) or `ink` (dark) |
| `inversePrimary` | `brandStrong` |
| `secondary` / `onSecondary` | `brandStrong` / `onBrand` |
| `secondaryContainer` / `onSecondaryContainer` | `tint` / `brandStrong` (light) or `ink` (dark) |
| `tertiary` / `onTertiary` / `tertiaryContainer` / `onTertiaryContainer` | Mirrors `primary`'s mapping — Meridian has no third accent |
| `background` / `onBackground` | `canvas` / `ink` |
| `surface` / `onSurface` | `surface` / `ink` |
| `surfaceVariant` / `onSurfaceVariant` | `tint` / `inkMuted` |
| `surfaceTint` | `brand` |
| `inverseSurface` / `inverseOnSurface` | `strong` / `canvas` (dark) or `surface` (light) |
| `surfaceBright` / `surfaceContainerLowest` / `surfaceContainerLow` | `surface` |
| `surfaceDim` / `surfaceContainer` / `surfaceContainerHigh` / `surfaceContainerHighest` | `canvas` |
| `error` / `onError` | `critical` / `onBrand` |
| `errorContainer` / `onErrorContainer` | `critical` at 12% alpha / `critical` |
| `outline` / `outlineVariant` | `stroke` |
| `scrim` | Black at 40% alpha |

## Type scale

Semantic roles, not pixel names, defined in `MeridianText` (`MeridianTypography.kt`).
Every style whose content can be a number carries `fontFeatureSettings = "tnum"` so a
live rate readout doesn't jitter as digit widths change.

| Style | Size / line height | Weight | `tnum` | Use |
| --- | --- | --- | --- | --- |
| `heroMetric` | 40sp / 46sp | Bold | Yes | One big number per screen |
| `screenTitle` | 20sp / 26sp | Bold | No | Screen and header-card titles |
| `itemTitle` | 15sp / 20sp | Bold | Yes | List row titles, stepper value |
| `body` | 14sp / 20sp | Regular | No | Default body text, state messages |
| `label` | 12sp / 16sp | Regular | Yes | Captions, meta, chip labels |
| `labelBold` | 12sp / 16sp | Bold | Yes | Status pill text |
| `micro` | 11sp / 14sp | Regular | No | Dense secondary data — avoid where possible |
| `button` | 14sp / 18sp | Medium | No | Button and text-button labels |

Sentence case everywhere. Never `.uppercase()` or an all-caps text style.

## Radius and spacing

Radius (`MeridianRadius`, `MeridianShapes.kt`) — zero radius is never used; nothing is
sharper than `sm`:

| Step | Value | Use |
| --- | --- | --- |
| `sm` | 12dp | Controls, chips, icon tiles, inner panels, thumbnails |
| `md` | 14dp | Buttons, search and input fields |
| `lg` | 16dp | Cards, sheets, feature surfaces |
| `pill` | 50% | Avatars, count badges, filter chips |

Spacing (`MeridianSpacing.kt`) — the 4pt grid, plus semantic defaults:

| Token | Value | Use |
| --- | --- | --- |
| `xs` | 4dp | Tightest gaps (icon-to-label) |
| `sm` | 8dp | Compact gaps, row spacing |
| `md` | 12dp | Standard component spacing |
| `lg` | 16dp | Card padding |
| `xl` | 20dp | Header-card padding |
| `xxl` | 24dp | Generous padding, empty-state insets |
| `screen` | 16dp | Outer padding of a scrolling screen |
| `cardInner` | 12dp | Padding inside a list-row card |
| `section` | 20dp | Gap between titled sections |
| `touchTarget` | 44dp | Minimum tap target |

Elevation never exceeds 1dp: every surface pins `tonalElevation` and `shadowElevation`
to `0.dp` and takes its structure from a 1px `stroke` border instead.

## Component catalogue

All components are stateless composables that take `Modifier` first (after any
required content parameters) and read colour exclusively through
`MeridianTheme.colors`. Source: `src/main/kotlin/com/slipstream/meridian/component/`.

| Component | Signature (essentials) | Reach for it when |
| --- | --- | --- |
| `MeridianCard` | `(modifier, onClick?, content: ColumnScope.() -> Unit)` | Any grouped surface — the atom every other card-like component builds on |
| `MeridianSectionHeader` | `(title, modifier, actionLabel?, onActionClick?)` | Titling a group of content, optionally with a "See all"-style action |
| `MeridianIconTile` | `(icon, contentDescription, modifier, size = 48.dp, onClick?)` | A square icon glyph on a tinted field — action tiles, list-row leading slots |
| `MeridianStatusPill` | `(status: MeridianStatus, label, modifier, icon?)` | Any status readout — `label` is required, colour is never the only cue |
| `MeridianListRow` | `(title, modifier, meta?, trailingValue?, status?, leading?, onClick?)` | The default row for a list of items — files, devices, transfers |
| `MeridianHeroMetric` | `(value, label, modifier, unit?)` | One big live number per screen — rare by design |
| `MeridianStat` | `(icon, value, caption, modifier)` | Compact three-up dashboard units |
| `MeridianStateView` / `MeridianUiState` | `(state, modifier, content)` | Every data-backed region — the three/four-state rule (see playbook) |
| `MeridianSearchField` | `(value, onValueChange, modifier, placeholder = "Search")` | Free-text filtering |
| `MeridianFilterChip` | `(label, selected, onClick, modifier)` | Single-select category filters — never mix with assist chips in one group |
| `MeridianBadge` | `(count, modifier, critical = false)` | A small pill count on a tab or icon — hidden at zero |
| `MeridianHeaderCard` | `(title, subtitle, modifier, trailing?)` | The one place brand fills an area — a screen has at most one |
| `MeridianPrimaryButton` | `(label, onClick, modifier, icon?, enabled, fullWidth)` | The one primary action per view |
| `MeridianSecondaryButton` | `(label, onClick, modifier, icon?, enabled)` | The alternative action alongside a primary button |
| `MeridianStepper` | `(value, onValueChange, modifier, min = 1, max = 8)` | A genuinely bounded integer control — e.g. parallel stream count |

`MeridianTheme(darkTheme = isSystemInDarkTheme(), content)` is the single entry point:
it is the only permitted call site of `isSystemInDarkTheme()`, and it provides
`LocalMeridianColors`, `LocalMeridianSpacing`, and a fully-mapped Material 3
`ColorScheme` in one composition.

## Gallery and screenshot baselines

`MeridianGallery()` (`src/main/kotlin/com/slipstream/meridian/gallery/MeridianGallery.kt`)
renders every token and component against the live theme, in a single scrolling
column. It lives in `src/main` — not `src/debug` — solely so the `screenshotTest`
source set can reach it for baseline generation; it is not part of the module's public
API and app code should not call it.

`GalleryScreenshots.kt` (`src/screenshotTest/`) wraps it in `MeridianTheme(darkTheme = …)`
for light and dark previews. These are the baselines checked by
`./gradlew :meridian-compose:validateDebugScreenshotTest` — a token or component change
that visibly alters the system shows up as an image diff instead of being noticed on a
device weeks later.
