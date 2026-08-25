# Meridian design playbook

The procedure for building with `:meridian-compose`. For the catalogue of what exists —
tokens, type, radius, spacing, components — see [`design-guide.md`](design-guide.md).

## Building a new screen

**Copy the nearest existing screen. Never start from a blank file.** The gallery
(`MeridianGallery.kt`) and the components' own `@Preview` functions show every
component in context; find the screen (or gallery section) that most resembles what
you're building and copy its structure rather than assembling `Column`/`Row`/padding
from scratch. This keeps spacing, section headers, and card structure consistent
without anyone having to remember the rules by heart.

The shape every screen shares:

```kotlin
@Composable
fun SomeScreen() {
    val colors = MeridianTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(MeridianSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.section),
    ) {
        // MeridianSectionHeader + components, one section per logical group
    }
}
```

Wrap the screen (or, more commonly, the app root) in exactly one `MeridianTheme { }` —
it is the only permitted call site of `isSystemInDarkTheme()`.

## The three-state rule

Every data-backed region of a screen — a list, a device panel, a transfer queue — has
exactly one of three states at any moment: loading, content, or "nothing to show"
(which splits into empty and error). Render all of them through one
`MeridianStateView`, never through hand-toggled sibling views:

```kotlin
MeridianStateView(
    state = when {
        isLoading -> MeridianUiState.Loading
        error != null -> MeridianUiState.Error(error.message, onRetry = ::retry)
        items.isEmpty() -> MeridianUiState.Empty("Nothing here yet.", "Add one", ::onAdd)
        else -> MeridianUiState.Content
    },
) {
    // the real content, rendered only for MeridianUiState.Content
}
```

The point of routing everything through one component is that "spinner and empty text
visible at once" becomes structurally impossible — there is no code path where two
states render simultaneously, because the `when` in `MeridianStateView` picks exactly
one.

Empty states invite action: give them a message plus a next step, not just "No data."
Error messages are direct and name the next step; they never apologise.

## When to reach for each component — and when not to

- Use `MeridianListRow` for anything list-shaped. Don't reassemble its layout by hand
  with `MeridianCard` + `Row` + `Text` — that's how spacing drifts off-token.
- Use `MeridianHeroMetric` for at most one number per screen. A second one competing
  for attention means neither is actually the hero; use `MeridianStat` instead for
  supporting numbers.
- Use `MeridianStatusPill` any time you're conveying connected/warning/failed/etc. —
  never render a status as a coloured dot or a bare coloured `Text` with no label.
- Use `MeridianHeaderCard` at most once per screen — it's the one place brand fills an
  area, and stacking two spends that signal twice.
- Use `MeridianStepper` only for a genuinely bounded integer (parallel stream count,
  retry limit). It is not the right control for a free-typed decimal or an unbounded
  count — reach for a text field instead.
- Prefer `MeridianPrimaryButton` singular: one primary action per view.
  `MeridianSecondaryButton` is for the alternative, not a second primary.
- Don't invent a new coloured surface. If nothing in the catalogue fits, that's a sign
  to extend the design system deliberately (new component, reviewed against the token
  table) rather than to reach for a raw `Color(0x…)` at the call site — which the
  tokens gate would reject anyway.

## The four traps

Each of these fails silently — no crash, no lint warning, and often a clean-looking IDE
preview — which is why each one has an automated check backing it up.

1. **Unmapped M3 role.** Skipping a role in `MeridianColors.toColorScheme()` means the
   first stock Material component that reaches for it (a `Switch`, a `Snackbar`,
   whatever wasn't exercised yet) renders Material's baseline lavender instead of a
   Meridian colour — silently, in production. Fix: map every role in both light and
   dark, in the same edit that adds it. Caught by `MeridianThemeTest`.

2. **`Surface`/`Card` tonal elevation.** Material 3 surfaces apply tonal elevation by
   default, which tints the container colour rather than casting a shadow — cards
   drift off-token with no crash, no warning, and a preview that still looks
   plausible. Fix: pin both `tonalElevation` and `shadowElevation` to `0.dp` on every
   surface; structure comes from the 1px `stroke` border, never elevation.

3. **Missing `tnum`.** A numeric text style without `fontFeatureSettings = "tnum"` uses
   proportional figures, so a live rate readout's digit widths change from frame to
   frame and the number visibly jitters. Fix: every style whose content can be a
   number declares `tnum`. Caught by `MeridianTypographyTest`.

4. **`isSystemInDarkTheme()` outside `MeridianTheme`.** A second call site can read a
   different value mid-recomposition than the one `MeridianTheme` used, so two screens
   — or a screen and a dialog above it — disagree about which mode they're in. Fix:
   call it nowhere except inside `MeridianTheme.kt`; pass `darkTheme` explicitly
   everywhere else if it needs to be overridden (as the screenshot baselines do).
   Caught by `scripts/check-meridian-tokens.sh`.
