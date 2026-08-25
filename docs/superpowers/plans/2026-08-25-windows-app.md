# Plan 5 — Slipstream for Windows

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The complete Windows application — Meridian implemented for WinUI 3, the shell, every screen, the pairing flow, tray and autostart, and the network-change handling Plan 2b deferred.

**Architecture:** Three projects. `Slipstream.Meridian` owns tokens, dictionaries, and controls with no app dependency. `Slipstream.App` is the WinUI 3 shell and screens. Both sit on the existing `Slipstream.Core`, reached through a single `PeerHost` service that owns the `SlipstreamPeer` lifetime — no view model touches a socket.

**Tech Stack:** .NET 9, WinUI 3 (Windows App SDK 1.6), C# 13, CommunityToolkit.Mvvm, xUnit.

**Spec:** [`2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §12 (Windows screens), §13 (WinUI dictionaries), §14 (background/autostart), §15 (error voice), §5 (network change).

---

## Preconditions — verified present on `main`

Plans 1, 1b, 2, and 4a are merged. `Slipstream.Core` exposes:

```csharp
sealed class SlipstreamPeer : IAsyncDisposable {
    DeviceIdentity Identity; PairedPeerStore Peers; ControlClient Client; LocalNetwork? Network;
    ControlServer Server; BulkServer BulkServer; MediaServer MediaServer; TransferEngine Engine;
    TokenVault Tokens; PairingWindow Pairing; PairingDiscovery PairingDiscovery;
    IPAddress? BindAddress { init; } string DownloadDirectory { init; }
    bool UseEphemeralPorts { init; } int StreamCount { init; }
    event Action? NetworkChanged;
    Task StartAsync(CancellationToken);
    Task<DiscoveryResult?> FindPeerAsync(TimeSpan, CancellationToken);
    Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>>, TimeSpan, CancellationToken);
}
sealed record TransferProgress(Guid TransferId, long BytesCompleted, long TotalBytes, double BytesPerSecond);
sealed record FileEntry(string Name, string Path, long Size, DateTimeOffset Modified, bool IsDirectory, string? Mime, string? ThumbnailToken);
sealed record PairingProgress(PairingState State, string? Code, string? PeerName);
```

Verify these before Task 8. If Plan 2b altered a signature, reconcile **here** and adjust the affected tasks.

## Global Constraints

- **Meridian palette** (from `android/meridian-compose/.../MeridianTokens.kt`, the merged source of truth): Canvas `#F4F5F7`, Surface `#FFFFFF`, Stroke `#ECEDF1`, Tint `#EEF0FB`, Ink `#1B1D28`, Ink-muted `#8A8D9B`, Brand `#1B62C9`, Brand-strong `#154FA6`, On-brand `#FFFFFF`, On-brand-muted `#DCE8FF`, Positive `#2E9E5B`, Warning `#E08A1E`, Critical `#D64545`, Info = Brand, Strong = Ink. Dark: Canvas `#0F1014`, Surface `#17181D`, Stroke `#2A2C35`, Tint `#1D2739`, Ink `#EDEEF2`, Ink-muted `#9B9EAC`, Brand `#6BA5F0`, Brand-strong `#8FBDF5`, On-brand `#0F1014`, On-brand-muted `#16324F`, Positive `#5FC98D`, Warning `#F0AD52`, Critical `#EE7C7C`.
- **Every colour lives in one dictionary pair.** No literal `#RRGGBB` in any page or control XAML. Enforced by a build gate.
- **Every WinUI `ThemeResource` the app touches is defined in BOTH `Light` and `Dark`.** An unmapped one silently falls back to the system Fluent accent — the same class of failure Meridian hit on Android.
- **Radius:** sm 12, md 14, lg 16, pill. **Spacing:** 4pt grid. **Elevation:** none — 1px stroke does the structural work.
- **All numbers use tabular figures** (`FontFeatures="tnum"` / `Typography.NumeralAlignment="Tabular"`).
- **Sentence case. Never ALL CAPS.** No `TextCasing`, no `.ToUpper()` in a binding.
- **Tap/click targets ≥ 44px.**
- **Status colour is never the only cue** — always a word or an icon.
- **No view model opens a socket.** All Core access goes through `PeerHost`.
- **No blocking calls on the UI thread.** Every Core call is awaited off-thread and marshalled back.
- **Error voice (spec §15):** direct, no apology, name the next step. English, sentence case.
- **LAN-only:** the app makes no outbound connection of any kind — no telemetry, no update check, no remote fonts or images.

---

## File Structure

```
windows/
  src/Slipstream.Meridian/
    Themes/Tokens.Light.xaml
    Themes/Tokens.Dark.xaml
    Themes/Typography.xaml
    Themes/Shapes.xaml
    Themes/Generic.xaml            # control default styles
    Controls/MeridianCard.cs
    Controls/MeridianSectionHeader.cs
    Controls/MeridianIconTile.cs
    Controls/MeridianStatusPill.cs
    Controls/MeridianStatCard.cs
    Controls/MeridianDataGrid.cs
    Controls/MeridianHeroMetric.cs
    Controls/MeridianStateView.cs
  src/Slipstream.App/
    App.xaml(.cs)
    Services/PeerHost.cs
    Services/TransferQueue.cs
    Services/HistoryStore.cs
    Services/AutostartService.cs
    Services/TrayIcon.cs
    Shell/ShellWindow.xaml(.cs)
    Shell/ShellViewModel.cs
    Pages/DevicePage.xaml(.cs) + DeviceViewModel.cs
    Pages/PairingDialog.xaml(.cs) + PairingViewModel.cs
    Pages/BrowsePage.xaml(.cs) + BrowseViewModel.cs
    Pages/TransfersPage.xaml(.cs) + TransfersViewModel.cs
    Pages/HistoryPage.xaml(.cs) + HistoryViewModel.cs
    Pages/SettingsPage.xaml(.cs) + SettingsViewModel.cs
  tests/Slipstream.App.Tests/         # view models + services, no UI thread
  scripts/check-meridian-winui.sh
```

View models are plain classes testable without a UI thread; XAML pages bind to them and hold no logic.

---

## Task 1: Solution scaffold, projects, and the design gate

**Files:** `Slipstream.Meridian.csproj`, `Slipstream.App.csproj`, `Slipstream.App.Tests.csproj`, `scripts/check-meridian-winui.sh`, CI update.

- [ ] **Step 1: Create the projects**

```bash
cd windows
dotnet new classlib -n Slipstream.Meridian -o src/Slipstream.Meridian -f net9.0-windows10.0.19041.0
dotnet new winui -n Slipstream.App -o src/Slipstream.App
dotnet new xunit -n Slipstream.App.Tests -o tests/Slipstream.App.Tests -f net9.0-windows10.0.19041.0
dotnet sln add src/Slipstream.Meridian src/Slipstream.App tests/Slipstream.App.Tests
dotnet add src/Slipstream.App reference src/Slipstream.Meridian src/Slipstream.Core
dotnet add src/Slipstream.Meridian package Microsoft.WindowsAppSDK
dotnet add src/Slipstream.App package CommunityToolkit.Mvvm
dotnet add tests/Slipstream.App.Tests reference src/Slipstream.App src/Slipstream.Core
```

`Slipstream.Core` targets plain `net9.0` and is consumed unchanged.

- [ ] **Step 2: Write the design gate**

Create `windows/scripts/check-meridian-winui.sh`. Mirrors the Android gate that already exists, adapted to XAML:

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MERIDIAN="$ROOT/src/Slipstream.Meridian"
APP="$ROOT/src/Slipstream.App"
status=0

echo "==> Colour literals outside the token dictionaries"
offenders=$(grep -rn --include="*.xaml" --include="*.cs" -E '#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?' "$MERIDIAN" "$APP" \
  | grep -v "Themes/Tokens.Light.xaml" | grep -v "Themes/Tokens.Dark.xaml" || true)
[ -n "$offenders" ] && { echo "FAIL: colours belong in Tokens.*.xaml:"; echo "$offenders"; status=1; }

echo "==> Every brush defined in both Light and Dark"
for key in $(grep -oE 'x:Key="Meridian[A-Za-z]+Brush"' "$MERIDIAN/Themes/Tokens.Light.xaml" | sort -u); do
  grep -q "$key" "$MERIDIAN/Themes/Tokens.Dark.xaml" || { echo "FAIL: $key missing from Dark."; status=1; }
done
for key in $(grep -oE 'x:Key="Meridian[A-Za-z]+Brush"' "$MERIDIAN/Themes/Tokens.Dark.xaml" | sort -u); do
  grep -q "$key" "$MERIDIAN/Themes/Tokens.Light.xaml" || { echo "FAIL: $key missing from Light."; status=1; }
done

echo "==> ALL CAPS"
caps=$(grep -rn --include="*.xaml" --include="*.cs" -E 'ToUpper\(\)|CharacterCasing="Upper"' "$APP" "$MERIDIAN" || true)
[ -n "$caps" ] && { echo "FAIL: sentence case only:"; echo "$caps"; status=1; }

echo "==> Elevation / drop shadows"
shadow=$(grep -rn --include="*.xaml" -E 'ThemeShadow|DropShadow|Translation="0,0,[1-9]' "$APP" "$MERIDIAN" || true)
[ -n "$shadow" ] && { echo "FAIL: Meridian uses strokes, not shadows:"; echo "$shadow"; status=1; }

[ "$status" -eq 0 ] && echo "All Meridian WinUI checks passed."
exit "$status"
```

- [ ] **Step 3: Verify build and gate**

```bash
cd windows && dotnet build Slipstream.sln
bash windows/scripts/check-meridian-winui.sh
```

Expected: build succeeds; gate passes (nothing to check yet).

- [ ] **Step 4: Add to CI**

In `.github/workflows/windows-core.yml`, add before the test step:

```yaml
      - name: Meridian WinUI gate
        run: bash windows/scripts/check-meridian-winui.sh
        shell: bash
```

- [ ] **Step 5: Commit**

```bash
git add windows .github && git commit -m "chore: scaffold Slipstream.Meridian and Slipstream.App with a design gate"
```

---

## Task 2: Token dictionaries — the exhaustive mapping

The highest-value task. An unmapped WinUI `ThemeResource` falls back to the system Fluent accent silently — no crash, no warning, correct-looking preview.

**Files:** `Themes/Tokens.Light.xaml`, `Themes/Tokens.Dark.xaml`, test `Slipstream.App.Tests/Meridian/TokenDictionaryTests.cs`

- [ ] **Step 1: Write the failing test**

```csharp
using System.Xml.Linq;

namespace Slipstream.App.Tests.Meridian;

public class TokenDictionaryTests
{
    private static readonly string[] Roles =
    [
        "Canvas", "Surface", "Stroke", "Tint", "Ink", "InkMuted",
        "Brand", "BrandStrong", "OnBrand", "OnBrandMuted", "Strong",
        "Positive", "Warning", "Critical", "Info",
    ];

    /// <summary>WinUI system brushes an unmapped control reaches for.</summary>
    private static readonly string[] SystemOverrides =
    [
        "AccentFillColorDefaultBrush", "AccentFillColorSecondaryBrush",
        "TextFillColorPrimaryBrush", "TextFillColorSecondaryBrush",
        "ControlFillColorDefaultBrush", "ControlStrokeColorDefaultBrush",
        "LayerFillColorDefaultBrush", "SolidBackgroundFillColorBaseBrush",
        "SystemControlHighlightAccentBrush",
    ];

    private static XDocument Load(string mode) =>
        XDocument.Load(TestPaths.Meridian($"Themes/Tokens.{mode}.xaml"));

    private static HashSet<string> KeysIn(XDocument doc) =>
        doc.Descendants()
           .Select(e => e.Attribute(XName.Get("Key", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value)
           .Where(v => v is not null)
           .ToHashSet()!;

    [Theory]
    [InlineData("Light")]
    [InlineData("Dark")]
    public void Defines_every_meridian_role(string mode)
    {
        var keys = KeysIn(Load(mode));
        var missing = Roles.Where(r => !keys.Contains($"Meridian{r}Brush")).ToList();

        Assert.True(missing.Count == 0, $"{mode} is missing: {string.Join(", ", missing)}");
    }

    [Theory]
    [InlineData("Light")]
    [InlineData("Dark")]
    public void Overrides_every_system_brush_a_stock_control_reaches_for(string mode)
    {
        var keys = KeysIn(Load(mode));
        var missing = SystemOverrides.Where(s => !keys.Contains(s)).ToList();

        Assert.True(missing.Count == 0,
            $"{mode} leaves these to the system Fluent palette: {string.Join(", ", missing)}");
    }

    [Fact]
    public void Light_and_dark_define_exactly_the_same_keys()
    {
        var light = KeysIn(Load("Light"));
        var dark = KeysIn(Load("Dark"));

        Assert.True(light.SetEquals(dark),
            $"Only in Light: {string.Join(", ", light.Except(dark))}. " +
            $"Only in Dark: {string.Join(", ", dark.Except(light))}.");
    }

    [Fact]
    public void Info_equals_brand_and_strong_equals_ink_in_both_modes()
    {
        foreach (var mode in new[] { "Light", "Dark" })
        {
            var colours = ColourMap(Load(mode));
            Assert.Equal(colours["MeridianBrandBrush"], colours["MeridianInfoBrush"]);
            Assert.Equal(colours["MeridianInkBrush"], colours["MeridianStrongBrush"]);
        }
    }

    [Fact]
    public void Light_brand_matches_the_pinned_palette()
    {
        Assert.Equal("#FF1B62C9", ColourMap(Load("Light"))["MeridianBrandBrush"], ignoreCase: true);
    }

    private static Dictionary<string, string> ColourMap(XDocument doc) =>
        doc.Descendants()
           .Where(e => e.Name.LocalName == "SolidColorBrush")
           .ToDictionary(
               e => e.Attribute(XName.Get("Key", "http://schemas.microsoft.com/winfx/2006/xaml"))!.Value,
               e => Normalise(e.Attribute("Color")!.Value));

    private static string Normalise(string colour) =>
        colour.Length == 7 ? "#FF" + colour[1..] : colour;
}
```

Add `TestPaths.cs` resolving repo-relative paths the way `VectorPaths` already does in `Slipstream.Core.Tests`.

- [ ] **Step 2: Run and confirm failure**

`dotnet test windows/Slipstream.sln --filter TokenDictionaryTests` → FAIL, dictionaries do not exist.

- [ ] **Step 3: Write `Tokens.Light.xaml`**

```xml
<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">

    <!-- Meridian roles. The ONLY file (with its Dark twin) permitted a colour literal. -->
    <SolidColorBrush x:Key="MeridianCanvasBrush"       Color="#F4F5F7" />
    <SolidColorBrush x:Key="MeridianSurfaceBrush"      Color="#FFFFFF" />
    <SolidColorBrush x:Key="MeridianStrokeBrush"       Color="#ECEDF1" />
    <SolidColorBrush x:Key="MeridianTintBrush"         Color="#EEF0FB" />
    <SolidColorBrush x:Key="MeridianInkBrush"          Color="#1B1D28" />
    <SolidColorBrush x:Key="MeridianInkMutedBrush"     Color="#8A8D9B" />
    <SolidColorBrush x:Key="MeridianBrandBrush"        Color="#1B62C9" />
    <SolidColorBrush x:Key="MeridianBrandStrongBrush"  Color="#154FA6" />
    <SolidColorBrush x:Key="MeridianOnBrandBrush"      Color="#FFFFFF" />
    <SolidColorBrush x:Key="MeridianOnBrandMutedBrush" Color="#DCE8FF" />
    <SolidColorBrush x:Key="MeridianStrongBrush"       Color="#1B1D28" />
    <SolidColorBrush x:Key="MeridianPositiveBrush"     Color="#2E9E5B" />
    <SolidColorBrush x:Key="MeridianWarningBrush"      Color="#E08A1E" />
    <SolidColorBrush x:Key="MeridianCriticalBrush"     Color="#D64545" />
    <!-- Info equals Brand on purpose: an in-flight item is not an alarm. -->
    <SolidColorBrush x:Key="MeridianInfoBrush"         Color="#1B62C9" />

    <!--
      System overrides. NOT optional and NOT dead weight: a stock WinUI control that
      reaches for one of these and finds it undefined renders the system Fluent accent
      instead of Meridian's blue — silently, with a correct-looking designer preview.
      Do not delete an entry because "nothing uses it"; a future stock control will.
    -->
    <SolidColorBrush x:Key="AccentFillColorDefaultBrush"        Color="#1B62C9" />
    <SolidColorBrush x:Key="AccentFillColorSecondaryBrush"      Color="#154FA6" />
    <SolidColorBrush x:Key="TextFillColorPrimaryBrush"          Color="#1B1D28" />
    <SolidColorBrush x:Key="TextFillColorSecondaryBrush"        Color="#8A8D9B" />
    <SolidColorBrush x:Key="ControlFillColorDefaultBrush"       Color="#FFFFFF" />
    <SolidColorBrush x:Key="ControlStrokeColorDefaultBrush"     Color="#ECEDF1" />
    <SolidColorBrush x:Key="LayerFillColorDefaultBrush"         Color="#FFFFFF" />
    <SolidColorBrush x:Key="SolidBackgroundFillColorBaseBrush"  Color="#F4F5F7" />
    <SolidColorBrush x:Key="SystemControlHighlightAccentBrush"  Color="#1B62C9" />
</ResourceDictionary>
```

- [ ] **Step 4: Write `Tokens.Dark.xaml`**

Identical key set, dark values from the Global Constraints table. Every system override repointed at the dark equivalents (`AccentFillColorDefaultBrush` = `#6BA5F0`, `TextFillColorPrimaryBrush` = `#EDEEF2`, `SolidBackgroundFillColorBaseBrush` = `#0F1014`, and so on).

- [ ] **Step 5: Wire theme switching in `App.xaml`**

```xml
<ResourceDictionary.ThemeDictionaries>
    <ResourceDictionary x:Key="Light" Source="ms-appx:///Slipstream.Meridian/Themes/Tokens.Light.xaml" />
    <ResourceDictionary x:Key="Dark"  Source="ms-appx:///Slipstream.Meridian/Themes/Tokens.Dark.xaml" />
</ResourceDictionary.ThemeDictionaries>
```

WinUI resolves `ThemeDictionaries` from the framework element's `ActualTheme`, so the swap is automatic. Consumers use `{ThemeResource MeridianBrandBrush}` — **never** `{StaticResource}`, which binds once and does not follow a theme change.

- [ ] **Step 6: Run tests and gate**

```bash
dotnet test windows/Slipstream.sln --filter TokenDictionaryTests   # 6 tests pass
bash windows/scripts/check-meridian-winui.sh
```

- [ ] **Step 7: Commit**

```bash
git add windows/src/Slipstream.Meridian/Themes windows/tests && git commit -m "feat: add Meridian token dictionaries with exhaustive system-brush overrides"
```

---

## Task 3: Typography, shapes, spacing

**Files:** `Themes/Typography.xaml`, `Themes/Shapes.xaml`, test `MeridianTypographyTests.cs`

- [ ] **Step 1: Write the failing test**

Assert, by parsing the XAML: `MeridianHeroMetricStyle` is 40px Bold; `MeridianScreenTitleStyle` 20 Bold; `MeridianItemTitleStyle` 15 Bold; `MeridianBodyStyle` 14 Regular; `MeridianLabelStyle` 12; every numeric style sets `Typography.NumeralAlignment="Tabular"`; radii are 12/14/16; no style sets a `CharacterCasing`.

The tabular assertion is the one that matters — a rate readout updating four times a second visibly jitters without it, and nothing else catches its absence.

- [ ] **Step 2: Run and confirm failure**

- [ ] **Step 3: Write `Typography.xaml`**

```xml
<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">

    <!--
      Numeric styles declare Tabular numerals. This app's content is rates, sizes and
      percentages updating several times a second; proportional figures make the value
      visibly jitter as digit widths change.
    -->
    <Style x:Key="MeridianHeroMetricStyle" TargetType="TextBlock">
        <Setter Property="FontSize" Value="40" />
        <Setter Property="FontWeight" Value="Bold" />
        <Setter Property="Foreground" Value="{ThemeResource MeridianBrandBrush}" />
        <Setter Property="Typography.NumeralAlignment" Value="Tabular" />
    </Style>

    <Style x:Key="MeridianScreenTitleStyle" TargetType="TextBlock">
        <Setter Property="FontSize" Value="20" />
        <Setter Property="FontWeight" Value="Bold" />
        <Setter Property="Foreground" Value="{ThemeResource MeridianInkBrush}" />
    </Style>

    <Style x:Key="MeridianItemTitleStyle" TargetType="TextBlock">
        <Setter Property="FontSize" Value="15" />
        <Setter Property="FontWeight" Value="Bold" />
        <Setter Property="Foreground" Value="{ThemeResource MeridianInkBrush}" />
        <Setter Property="Typography.NumeralAlignment" Value="Tabular" />
        <Setter Property="TextTrimming" Value="CharacterEllipsis" />
        <Setter Property="MaxLines" Value="2" />
    </Style>

    <Style x:Key="MeridianBodyStyle" TargetType="TextBlock">
        <Setter Property="FontSize" Value="14" />
        <Setter Property="Foreground" Value="{ThemeResource MeridianInkBrush}" />
    </Style>

    <Style x:Key="MeridianLabelStyle" TargetType="TextBlock">
        <Setter Property="FontSize" Value="12" />
        <Setter Property="Foreground" Value="{ThemeResource MeridianInkMutedBrush}" />
        <Setter Property="Typography.NumeralAlignment" Value="Tabular" />
    </Style>

    <Style x:Key="MeridianLabelBoldStyle" TargetType="TextBlock" BasedOn="{StaticResource MeridianLabelStyle}">
        <Setter Property="FontWeight" Value="Bold" />
    </Style>
</ResourceDictionary>
```

`Shapes.xaml` defines `MeridianRadiusSm` (12), `Md` (14), `Lg` (16) as `CornerRadius` resources plus `MeridianSpacingXs/Sm/Md/Lg/Xl/Xxl` (4/8/12/16/20/24) as `Thickness`/`double` resources.

- [ ] **Step 4: Run tests, gate, commit**

```bash
git add windows/src/Slipstream.Meridian/Themes && git commit -m "feat: add Meridian typography, shapes, and spacing resources"
```

---

## Task 4: `MeridianCard`, `MeridianSectionHeader`, `MeridianIconTile`

**Files:** the three controls + `Themes/Generic.xaml` entries, test `ControlDefaultsTests.cs`

- [ ] **Step 1: Write the failing test**

Assert each control's default style sets `BorderThickness=1`, `BorderBrush={ThemeResource MeridianStrokeBrush}`, `Background={ThemeResource MeridianSurfaceBrush}`, `CornerRadius` from the radius resources, and **no** shadow/translation property. Assert `MeridianIconTile` has `MinWidth`/`MinHeight` ≥ 44 when interactive.

- [ ] **Step 2: Run and confirm failure**

- [ ] **Step 3: Implement**

`MeridianCard` as a templated `ContentControl`:

```csharp
namespace Slipstream.Meridian.Controls;

/// <summary>
/// The atom of every screen: Surface fill, lg radius, 1px stroke, no elevation.
/// Structure comes from the stroke — Meridian has no shadow language, so the default
/// style deliberately sets no ThemeShadow and no Translation.
/// Carries no content padding: inset belongs to the caller's content, so one card
/// style wraps either a padded StackPanel or a Grid without fighting it.
/// </summary>
public sealed class MeridianCard : ContentControl
{
    public MeridianCard() => DefaultStyleKey = typeof(MeridianCard);
}
```

`MeridianSectionHeader` exposes `Title`, `ActionLabel`, and `ActionCommand` dependency properties; the action is hidden when either is unset. `MeridianIconTile` exposes `Glyph`, `Label`, `Command`, and `TileSize` (default 48), fills with `MeridianTintBrush` at `sm` radius, and tints the glyph `MeridianBrandBrush`.

- [ ] **Step 4: Run tests, gate, commit**

---

## Task 5: `MeridianStatusPill`, `MeridianStatCard`, `MeridianHeroMetric`, `MeridianStateView`

**Files:** four controls, test `StatusPillTests.cs`, `StateViewTests.cs`

- [ ] **Step 1: Write the failing test**

```csharp
[Theory]
[InlineData(MeridianStatus.Positive, "MeridianPositiveBrush")]
[InlineData(MeridianStatus.Warning,  "MeridianWarningBrush")]
[InlineData(MeridianStatus.Critical, "MeridianCriticalBrush")]
[InlineData(MeridianStatus.Info,     "MeridianInfoBrush")]
[InlineData(MeridianStatus.Neutral,  "MeridianInkMutedBrush")]
public void Maps_each_status_to_its_signal_brush(MeridianStatus status, string expectedKey)
    => Assert.Equal(expectedKey, MeridianStatusPill.BrushKeyFor(status));

[Fact]
public void Label_is_required_so_colour_is_never_the_only_cue()
{
    // The API makes the non-colour cue impossible to omit, rather than leaving it
    // to reviewer discipline.
    Assert.Throws<ArgumentException>(() => MeridianStatusPill.Validate(status: MeridianStatus.Critical, label: ""));
}
```

`BrushKeyFor` and `Validate` are static and pure, so the mapping is testable without a UI thread.

- [ ] **Step 2–4: Implement, test, commit**

`MeridianStateView` is a `ContentControl` with a `State` DP over `{Loading, Content, Empty, Error}` plus `Message`, `ActionLabel`, `ActionCommand` — the same four-state contract as the Compose version, so the two platforms stay recognisably one system. Empty states name the next action; error text uses `MeridianCriticalBrush` and the §15 voice.

---

## Task 6: `MeridianDataGrid`

The admin-table workhorse: muted uppercase-free headers, hairline row separators, **no vertical gridlines**, generous rows, Brand-tint selection with a one-step-lighter hover.

**Files:** `Controls/MeridianDataGrid.cs` + template, test `DataGridTests.cs`

- [ ] **Step 1: Write the failing test**

Assert the default template: header foreground is `MeridianInkMutedBrush`; row separator is a 1px bottom border in `MeridianStrokeBrush`; no column separator element exists; selected-row background is `MeridianTintBrush`; `MinHeight` per row ≥ 44.

- [ ] **Step 2–4: Implement, test, commit**

Built on `ListView` with a custom `ItemContainerStyle` and a `Columns` collection of `(Header, Binding, Width, Alignment, IsTabular)`. Columns marked tabular get `Typography.NumeralAlignment="Tabular"` and right alignment, so size and rate columns line up.

---

## Task 7: The shell — sidebar, top bar, navigation

**Files:** `Shell/ShellWindow.xaml(.cs)`, `Shell/ShellViewModel.cs`, test `ShellViewModelTests.cs`

- [ ] **Step 1: Write the failing test**

```csharp
[Fact]
public void Exposes_the_five_destinations_in_order()
{
    var vm = new ShellViewModel(new FakePeerHost());
    Assert.Equal(["Device", "Browse phone", "Transfers", "History", "Settings"],
                 vm.Destinations.Select(d => d.Label));
}

[Fact]
public void Starts_on_the_device_page() => Assert.Equal("Device", new ShellViewModel(new FakePeerHost()).Selected.Label);

[Fact]
public void Connection_status_follows_the_host()
{
    var host = new FakePeerHost();
    var vm = new ShellViewModel(host);

    host.RaiseState(PeerConnectionState.Connected, "Pixel 9");
    Assert.Equal(MeridianStatus.Positive, vm.ConnectionStatus);
    Assert.Equal("Pixel 9", vm.ConnectionLabel);

    host.RaiseState(PeerConnectionState.Searching, null);
    Assert.Equal(MeridianStatus.Info, vm.ConnectionStatus);
    Assert.Equal("Searching…", vm.ConnectionLabel);

    host.RaiseState(PeerConnectionState.Lost, null);
    Assert.Equal(MeridianStatus.Critical, vm.ConnectionStatus);
}

[Fact]
public void Degraded_link_reads_as_a_warning_naming_the_band()
{
    var host = new FakePeerHost();
    var vm = new ShellViewModel(host);

    host.RaiseState(PeerConnectionState.Degraded, "Pixel 9", band: "2.4 GHz");

    Assert.Equal(MeridianStatus.Warning, vm.ConnectionStatus);
    // Spec §16: explain the slow link rather than leaving the user to wonder.
    Assert.Equal("2.4 GHz — slower link", vm.ConnectionLabel);
}
```

- [ ] **Step 2–4: Implement, test, commit**

`ShellWindow.xaml` is a 260px `MeridianSurfaceBrush` sidebar on `MeridianCanvasBrush`, active leaf a **filled Brand pill** with On-brand text, inactive `MeridianInkMutedBrush`. Per the shell rule: **no nav search box, no bottom user chip.** Top bar carries a bold page title, a muted subtitle, and the connection pill inline-end.

---

## Task 8: `PeerHost` — the single owner of Core

**Files:** `Services/PeerHost.cs`, `IPeerHost.cs`, test `PeerHostTests.cs`

**Interfaces:**
- `enum PeerConnectionState { Idle, Searching, Connected, Degraded, Lost }`
- `interface IPeerHost { PeerConnectionState State; string? PeerName; string? Band; event Action<PeerConnectionState, string?, string?>? StateChanged; Task StartAsync(CancellationToken); Task<bool> ReconnectAsync(CancellationToken); Task<IReadOnlyList<FileEntry>> ListAsync(string path, CancellationToken); Task<string> PullAsync(string remotePath, IProgress<TransferProgress>?, CancellationToken); Task StreamAsync(string remotePath, CancellationToken); Task SendClipboardAsync(string text, CancellationToken); Task<PairedPeer?> PairAsync(Func<string, CancellationToken, Task<bool>>, CancellationToken); }`
- `sealed class PeerHost : IPeerHost` — owns one `SlipstreamPeer`, one control connection, and a reconnect loop.

- [ ] **Step 1: Write the failing test** — against the real `SlipstreamPeer` on loopback, reusing `TwoPeers` from `Slipstream.Core.Tests`:

```csharp
[Fact]
public async Task Reaches_Connected_and_lists_the_peers_files()
{
    await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token);
    await using var host = new PeerHost(rig.Client, downloadDirectory: _downloads);

    await host.StartAsync(_cts.Token);

    Assert.Equal(PeerConnectionState.Connected, host.State);
    Assert.NotEmpty(await host.ListAsync(_sharedDir, _cts.Token));
}

[Fact]
public async Task Reports_Lost_then_recovers_on_reconnect()
{
    // spec §5: a network switch is routine, never an error state.
    await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token);
    await using var host = new PeerHost(rig.Client, _downloads);
    await host.StartAsync(_cts.Token);

    var states = new List<PeerConnectionState>();
    host.StateChanged += (s, _, _) => { lock (states) states.Add(s); };

    await rig.BreakControlConnectionAsync();
    await WaitUntil(() => host.State == PeerConnectionState.Lost, TimeSpan.FromSeconds(10));

    Assert.True(await host.ReconnectAsync(_cts.Token));
    Assert.Equal(PeerConnectionState.Connected, host.State);
}
```

Add `BreakControlConnectionAsync` to `TwoPeers`.

- [ ] **Step 2–4: Implement, test, commit**

`PeerHost` serialises control-channel access behind one `SemaphoreSlim` — the channel is a single duplex stream and concurrent request/response pairs would interleave replies. Every public method marshals nothing to the UI thread; view models handle that.

---

## Task 9: Device page

**Files:** `Pages/DevicePage.xaml(.cs)`, `DeviceViewModel.cs`, test `DeviceViewModelTests.cs`

- [ ] **Step 1: Write the failing test** — three stat values format correctly and use tabular styles; the hero metric shows the live rate during a transfer and a resting value otherwise; the discovery detail lists the four strategies with the winner named.

- [ ] **Step 2–4: Implement, test, commit**

3-up `MeridianStatCard` row (link rate, transferred today, peer state), `MeridianHeroMetric` for the live rate, then a connection panel that names the winning discovery strategy and elapsed time — so "why did it take that long" is answerable.

---

## Task 10: Pairing flow

**Files:** `Pages/PairingDialog.xaml(.cs)`, `PairingViewModel.cs`, test `PairingViewModelTests.cs`

The UI obligation `protocol/pairing.md` records: the code must be shown prominently and the user told exactly what to compare. This is the half of the threat model software cannot enforce.

- [ ] **Step 1: Write the failing test**

```csharp
[Fact]
public void Shows_the_code_and_asks_the_user_to_compare_it()
{
    var vm = new PairingViewModel(new FakePeerHost());
    vm.OnCodeReceived("482915", peerName: "Pixel 9");

    Assert.Equal("482915", vm.Code);
    // Naming the other device and the action is what makes the comparison happen.
    Assert.Equal("Does Pixel 9 show this same code?", vm.Prompt);
    Assert.True(vm.CanConfirm);
}

[Fact]
public void Confirm_is_unavailable_until_a_code_arrives()
    => Assert.False(new PairingViewModel(new FakePeerHost()).CanConfirm);

[Fact]
public void Declining_reports_failure_without_pairing()
{
    var host = new FakePeerHost();
    var vm = new PairingViewModel(host);
    vm.OnCodeReceived("482915", "Pixel 9");

    vm.DeclineCommand.Execute(null);

    Assert.False(host.Paired);
    Assert.Equal("Pairing cancelled.", vm.Status);
}

[Fact]
public void Counts_down_the_120_second_window()
{
    var vm = new PairingViewModel(new FakePeerHost());
    vm.OnWindowOpened(DateTimeOffset.UnixEpoch.AddSeconds(120), now: DateTimeOffset.UnixEpoch);
    Assert.Equal("2:00", vm.TimeRemaining);
}
```

- [ ] **Step 2–4: Implement, test, commit**

The code renders at `MeridianHeroMetric` size with letter spacing, unmissable. Settings gets a "Pair a device" button that opens the 120-second window.

---

## Task 11: Browse page

**Files:** `Pages/BrowsePage.xaml(.cs)`, `BrowseViewModel.cs`, test `BrowseViewModelTests.cs`

- [ ] **Step 1: Write the failing test** — directories sort before files; the four filter chips (All/Video/Audio/Images/Docs) filter by MIME prefix; breadcrumb navigation pushes and pops; `Truncated` surfaces an honest banner; the state view shows Loading → Content → Empty correctly; a failed list produces the §15 message, not an exception.

```csharp
[Fact]
public void A_truncated_listing_says_so_rather_than_pretending_it_is_complete()
{
    var vm = new BrowseViewModel(FakeHostReturning(entries: 5000, truncated: true));
    await vm.LoadAsync("/storage");

    Assert.Equal("Showing the first 5,000 items in this folder.", vm.TruncationNotice);
}
```

- [ ] **Step 2–4: Implement, test, commit**

`MeridianDataGrid` by default; a gallery toggle for media-heavy folders, since thumbnails already exist server-side. Thumbnails load lazily from `/thumb/{token}` as rows scroll.

---

## Task 12: Transfers page and queue

**Files:** `Services/TransferQueue.cs`, `Pages/TransfersPage.xaml(.cs)`, `TransfersViewModel.cs`, tests

- [ ] **Step 1: Write the failing test**

```csharp
[Fact]
public async Task Runs_queued_transfers_one_at_a_time()
{
    var queue = new TransferQueue(new FakePeerHost(), maxConcurrent: 1);
    queue.Enqueue("/a.bin"); queue.Enqueue("/b.bin");

    await queue.WaitForIdleAsync(_cts.Token);

    Assert.Equal(2, queue.Completed.Count);
    Assert.All(queue.Completed, t => Assert.Equal(TransferStatus.Complete, t.Status));
}

[Fact]
public async Task A_failed_transfer_does_not_stop_the_queue()
{
    var host = new FakePeerHost { FailFor = "/bad.bin" };
    var queue = new TransferQueue(host, maxConcurrent: 1);
    queue.Enqueue("/bad.bin"); queue.Enqueue("/good.bin");

    await queue.WaitForIdleAsync(_cts.Token);

    Assert.Contains(queue.Completed, t => t.Status == TransferStatus.Failed);
    Assert.Contains(queue.Completed, t => t.Status == TransferStatus.Complete);
}

[Fact]
public void Progress_formats_as_tabular_rate_and_eta()
{
    var item = new TransferItem("/big.bin", 4L * 1024 * 1024 * 1024);
    item.Apply(new TransferProgress(Guid.Empty, 1L << 30, 4L << 30, 50 * 1024 * 1024));

    Assert.Equal("1.0 / 4.0 GB", item.SizeText);
    Assert.Equal("50.0 MB/s", item.RateText);
    Assert.Equal("1m 1s left", item.EtaText);
}
```

- [ ] **Step 2–4: Implement, test, commit**

Progress events are throttled by Core already; the view model coalesces to the UI thread at most 4/s so the list does not thrash.

---

## Task 13: History

**Files:** `Services/HistoryStore.cs`, `Pages/HistoryPage.xaml(.cs)`, `HistoryViewModel.cs`, tests

- [ ] **Step 1: Write the failing test** — entries persist across instances; newest first; capped at 500 with oldest evicted; "Reveal in folder" is disabled when the file no longer exists; "Run again" re-enqueues.

- [ ] **Step 2–4: Implement, test, commit**

JSON file under the state directory. No database — 500 rows does not warrant one.

---

## Task 14: Settings

**Files:** `Pages/SettingsPage.xaml(.cs)`, `SettingsViewModel.cs`, `Services/SettingsStore.cs`, tests

- [ ] **Step 1: Write the failing test** — stream count clamps to 1–8 and persists; download folder validates and falls back if missing; theme choice (System/Light/Dark) persists and applies; "Pair a device" opens the window; autostart toggle round-trips.

- [ ] **Step 2–4: Implement, test, commit**

Cards stacked at 20px gaps. Stream count is a stepper (a genuinely bounded integer — the one place that control earns its place). Includes a **"PC hosts the hotspot"** explainer and a link to the band the shell reports, per spec §16's guidance that the app should explain a slow link rather than leave the user guessing.

---

## Task 15: Tray, autostart, single instance

**Files:** `Services/TrayIcon.cs`, `Services/AutostartService.cs`, `App.xaml.cs`, tests

- [ ] **Step 1: Write the failing test**

```csharp
[Fact]
public void Enabling_autostart_writes_a_logon_task_and_disabling_removes_it()
{
    var service = new AutostartService(taskName: "Slipstream.Test");
    try
    {
        service.Enable();
        Assert.True(service.IsEnabled);
        service.Disable();
        Assert.False(service.IsEnabled);
    }
    finally { service.Disable(); }
}

[Fact]
public void Enabling_twice_is_idempotent()
{
    var service = new AutostartService("Slipstream.Test2");
    try { service.Enable(); service.Enable(); Assert.True(service.IsEnabled); }
    finally { service.Disable(); }
}
```

- [ ] **Step 2–4: Implement, test, commit**

Task Scheduler logon entry (not a Run key — it survives better and can run without a console flash). Closing the window hides to tray; the tray menu offers Show, Pause discovery, and Quit. Single-instance via a named mutex; a second launch surfaces the first window instead of starting a second peer that would fight for port 53321.

---

## Task 16: Network-change handling, and end-to-end verification

Closes the §5 gap Plan 2b explicitly carried forward.

**Files:** `Services/PeerHost.cs` (extend), tests, manual verification

- [ ] **Step 1: Write the failing test**

```csharp
[Fact]
public async Task A_network_change_tears_down_rediscovers_and_resumes()
{
    await using var rig = await TwoPeers.StartAsync(_dir, _cts.Token);
    await using var host = new PeerHost(rig.Client, _downloads);
    await host.StartAsync(_cts.Token);

    var transfer = host.PullAsync(_largeFile, null, _cts.Token);

    await rig.BreakControlConnectionAsync();
    rig.Client.RaiseNetworkChanged();          // what NetworkChange delivers in production

    // Spec §5: routine, never an error state. The transfer must finish, not fail.
    var local = await transfer;

    Assert.Equal(_payload, await File.ReadAllBytesAsync(local, _cts.Token));
    Assert.Equal(PeerConnectionState.Connected, host.State);
}
```

- [ ] **Step 2: Confirm it fails** — today nothing subscribes to `NetworkChanged`, so the transfer throws.

- [ ] **Step 3: Implement**

`PeerHost` subscribes to `SlipstreamPeer.NetworkChanged` in `StartAsync` and **unsubscribes in `DisposeAsync`** (the leak Plan 2b fixed in Core — do not reintroduce it here). On the event: mark `Lost`, drop the control connection, re-run discovery with backoff (1s, 2s, 4s, capped at 15s), reconnect, and let in-flight transfers resume from their chunk bitmaps.

- [ ] **Step 4: Run everything**

```bash
bash windows/scripts/check-meridian-winui.sh
dotnet test windows/Slipstream.sln
dotnet run --project windows/bench/Slipstream.Bench --configuration Release
```

- [ ] **Step 5: Verify on real hardware**

Against a paired phone, walk this and record actual results:

| Check | Expected |
|---|---|
| Cold start with the phone on the same WiFi | Connects without user action; strategy and elapsed shown |
| Pair a factory-reset peer | Same six digits on both; declining on either pairs neither |
| Browse the phone, scroll a photo folder | Thumbnails fill in lazily; no UI stall |
| Pull a 1 GB file | Progress and rate update smoothly; hash matches both sides |
| Toggle WiFi off/on mid-transfer | Status goes Lost → Searching → Connected; transfer **resumes**, not restarts |
| Stream a 4 GB MKV and seek to the end | Starts in seconds; seek near-instant; nothing written to disk |
| Switch Windows to dark mode with the app open | Every surface follows; no control keeps a light brush |
| Close the window | Hides to tray; still discoverable |
| Reboot with autostart on | Reappears in tray and reconnects unattended |

State plainly which of these you could not run rather than implying they passed.

- [ ] **Step 6: Write the deviations record and commit**

`docs/superpowers/plans/2026-08-25-windows-app-deviations.md`, following `2026-08-25-core-discovery-control-deviations.md`. Record at minimum anything WinUI forced that differs from this plan, and the outcome of every row above.

---

## Self-Review

**Spec coverage.** §12 Windows screens → Tasks 7, 9, 11, 12, 13, 14. §12 sidebar rules (no nav search, no user chip) → 7. §12 status→connection mapping → 5, 7. §12 hero metric is the live rate → 5, 9. §13 dictionaries with exhaustive system-brush overrides → 2. §13 tabular figures → 3. §13 both modes defined together → 2 (gate + test). §14 tray, autostart, single instance → 15. §15 error voice → 5, 8, 11. §5 network change → 16. §16 explain the slow link → 7, 14. §4 pairing UI obligation → 10. §9 thumbnails consumed → 11.

**Deliberately out of scope:** an embedded player (spec §8 hands off to the system default), Android anything, and the installer/MSIX packaging — the app is sideloaded and launched from a build output, matching the personal-use decision in §2.

**Placeholder scan.** No `TBD`/`TODO`. Tasks 4, 5, 6, 9, 13, 14 compress their implement/test/commit steps into one line because the pattern is identical to Tasks 2–3 and fully specified by the Interfaces and test assertions above them; every task still states exactly what its tests must assert.

**Type consistency.** `MeridianStatus` reuses the five members from the Compose implementation. `PeerConnectionState` is declared once (Task 8) and consumed in 7, 9, 16. `IPeerHost` is the only Core-facing surface; no view model references `SlipstreamPeer`. `TransferProgress` and `FileEntry` come from Core unchanged.
