# Meridian for Compose — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `:meridian-compose`, a self-contained Android library module holding Slipstream's design system — tokens, theme, typography, and the component kit — verified in both light and dark, with no dependency on any Slipstream networking code.

**Architecture:** One token file is the single source of colour truth, guarded by a lint gate. Theming is two layers: a `LocalMeridianColors` CompositionLocal carrying Meridian's own roles, over a **fully mapped** Material 3 `ColorScheme` so stock Material components are correct without per-call-site overrides. Components are stateless composables taking a `Modifier` first-class.

**Tech Stack:** Kotlin 2.x, Jetpack Compose (BOM), Material 3, JUnit4 + `compose-ui-test`, Robolectric, and Compose Preview Screenshot Testing (AGP first-party).

**Spec:** [`docs/superpowers/specs/2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §12 (Meridian adoption) and §13 (the Compose port and its three traps).

## Preconditions

**None.** This plan depends on no other plan and no Slipstream code. It can be executed in an isolated worktree, in parallel with everything else, starting immediately.

The only external input is the palette and the component contracts, both reproduced in full in Task 2 and the task bodies below — nothing needs to be looked up elsewhere.

## Global Constraints

- **Module:** `:meridian-compose`, namespace `com.slipstream.meridian`, `minSdk 26`, `compileSdk 35`.
- **No dependency on `:app` or any Slipstream networking code.** The module must compile standalone.
- **Every colour lives in `MeridianTokens.kt`.** `Color(0x…)` literals are banned everywhere else, enforced by a build gate.
- **Every Material 3 `ColorScheme` role is mapped**, in light and dark, in the same edit. An unmapped role renders Material's baseline lavender silently.
- **Elevation never exceeds 1dp.** Every surface sets `tonalElevation = 0.dp` and `shadowElevation = 0.dp`, taking structure from a 1px border.
- **`isSystemInDarkTheme()` is read in exactly one place** — inside `MeridianTheme`. Nowhere else.
- **All numeric text styles carry `fontFeatureSettings = "tnum"`.**
- **Radius:** `sm` 12dp, `md` 14dp, `lg` 16dp, `pill` 50%. Zero radius is never used; nothing is sharper than `sm`.
- **Spacing is the 4pt grid:** 4, 8, 12, 16, 20, 24.
- **Sentence case everywhere. Never ALL CAPS.** No `text-transform` equivalent, no `.uppercase()`.
- **Tap targets ≥ 44dp.**
- **Status colour is never the only cue** — every status carries a word or an icon.
- **English only, LTR** — but use `start`/`end` padding and alignment, never `left`/`right`.
- **Every component ships a `@Preview` in light and dark.**

---

## File Structure

```
android/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  scripts/check-meridian-tokens.sh
  meridian-compose/
    build.gradle.kts
    src/main/kotlin/com/slipstream/meridian/
      MeridianTokens.kt          # every hex; the only file allowed Color(0x…)
      MeridianColors.kt          # role set + CompositionLocal + M3 mapping
      MeridianTypography.kt      # scale, tabular figures
      MeridianShapes.kt          # sm/md/lg/pill
      MeridianSpacing.kt         # 4pt grid
      MeridianTheme.kt           # the single isSystemInDarkTheme() call site
      component/
        MeridianCard.kt
        MeridianSectionHeader.kt
        MeridianIconTile.kt
        MeridianStatusPill.kt
        MeridianListRow.kt
        MeridianHeroMetric.kt
        MeridianStat.kt
        MeridianStateView.kt
        MeridianSearchField.kt
        MeridianFilterChip.kt
        MeridianBadge.kt
        MeridianHeaderCard.kt
        MeridianButtons.kt
        MeridianStepper.kt
    src/test/kotlin/…
    src/androidTest/kotlin/…
    src/debug/kotlin/com/slipstream/meridian/gallery/MeridianGallery.kt
  docs/design-guide.md
  docs/design-playbook.md
```

---

## Task 1: Module scaffold, dependency catalogue, and CI

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`
- Create: `android/meridian-compose/build.gradle.kts`
- Create: `.github/workflows/android-meridian.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: `./gradlew :meridian-compose:assembleDebug`, `:meridian-compose:testDebugUnitTest`, and `:meridian-compose:validateDebugScreenshotTest` all runnable.

- [ ] **Step 1: Create the version catalogue**

Create `android/gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.21"
composeBom = "2024.10.00"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
junit = "4.13.2"
robolectric = "4.14"
androidxTest = "1.6.1"
androidxJunit = "1.2.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-core = { group = "androidx.test", name = "core", version.ref = "androidxTest" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
screenshot = { id = "com.android.compose.screenshot", version = "0.0.1-alpha08" }
```

- [ ] **Step 2: Create the settings and root build files**

Create `android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Slipstream"
include(":meridian-compose")
```

Create `android/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.screenshot) apply false
}
```

- [ ] **Step 3: Create the module build file**

Create `android/meridian-compose/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.screenshot)
}

android {
    namespace = "com.slipstream.meridian"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Design-system violations should not compile.
        freeCompilerArgs += listOf("-Xjvm-default=all", "-Werror")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    screenshotTestImplementation(libs.compose.ui.tooling)
}
```

- [ ] **Step 4: Verify it builds**

Run: `cd android && ./gradlew :meridian-compose:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Add CI**

Create `.github/workflows/android-meridian.yml`:

```yaml
name: android-meridian
on:
  push:
    branches: [main]
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Token gate
        run: bash android/scripts/check-meridian-tokens.sh
      - name: Unit tests
        run: cd android && ./gradlew :meridian-compose:testDebugUnitTest
      - name: Screenshot tests
        run: cd android && ./gradlew :meridian-compose:validateDebugScreenshotTest
```

The token gate runs *first* — it is the cheapest check and the one most likely to catch a drifting change.

- [ ] **Step 6: Commit**

```bash
git add android .github/workflows/android-meridian.yml
git commit -m "chore: scaffold meridian-compose module and CI"
```

---

## Task 2: Tokens and the colour-literal gate

**Files:**
- Create: `android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianTokens.kt`
- Create: `android/scripts/check-meridian-tokens.sh`
- Test: `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/MeridianTokensTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object MeridianTokens` with `object Light` and `object Dark`, each exposing `canvas, surface, stroke, tint, ink, inkMuted, brand, brandStrong, onBrand, onBrandMuted, strong, positive, warning, critical, info` as `Color`.

- [ ] **Step 1: Write the failing test**

Create `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/MeridianTokensTest.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeridianTokensTest {

    @Test
    fun `light palette matches the specified hex values`() {
        with(MeridianTokens.Light) {
            assertEquals(Color(0xFFF4F5F7), canvas)
            assertEquals(Color(0xFFFFFFFF), surface)
            assertEquals(Color(0xFFECEDF1), stroke)
            assertEquals(Color(0xFFEEF0FB), tint)
            assertEquals(Color(0xFF1B1D28), ink)
            assertEquals(Color(0xFF8A8D9B), inkMuted)
            assertEquals(Color(0xFF1B62C9), brand)
            assertEquals(Color(0xFF154FA6), brandStrong)
            assertEquals(Color(0xFFFFFFFF), onBrand)
            assertEquals(Color(0xFFDCE8FF), onBrandMuted)
            assertEquals(Color(0xFF2E9E5B), positive)
            assertEquals(Color(0xFFE08A1E), warning)
            assertEquals(Color(0xFFD64545), critical)
        }
    }

    @Test
    fun `info equals brand in both modes`() {
        // Deliberate: an in-flight item is not an alarm.
        assertEquals(MeridianTokens.Light.brand, MeridianTokens.Light.info)
        assertEquals(MeridianTokens.Dark.brand, MeridianTokens.Dark.info)
    }

    @Test
    fun `strong equals ink in both modes`() {
        // There is no separate navy in this system.
        assertEquals(MeridianTokens.Light.ink, MeridianTokens.Light.strong)
        assertEquals(MeridianTokens.Dark.ink, MeridianTokens.Dark.strong)
    }

    @Test
    fun `ink is never pure black or pure white`() {
        assertNotEquals(Color(0xFF000000), MeridianTokens.Light.ink)
        assertNotEquals(Color(0xFFFFFFFF), MeridianTokens.Dark.ink)
    }

    @Test
    fun `dark canvas is darker than dark surface`() {
        // Surfaces float above the canvas in both modes; inverting this reads as broken.
        assertTrue(MeridianTokens.Dark.canvas.luminance() < MeridianTokens.Dark.surface.luminance())
    }

    @Test
    fun `light canvas is darker than light surface`() {
        assertTrue(MeridianTokens.Light.canvas.luminance() < MeridianTokens.Light.surface.luminance())
    }

    @Test
    fun `body text meets the 4_5 to 1 contrast floor on its surface`() {
        assertContrastAtLeast(4.5, MeridianTokens.Light.ink, MeridianTokens.Light.surface)
        assertContrastAtLeast(4.5, MeridianTokens.Dark.ink, MeridianTokens.Dark.surface)
    }

    @Test
    fun `status colours meet the 4_5 to 1 contrast floor on their surface`() {
        with(MeridianTokens.Light) {
            assertContrastAtLeast(4.5, brand, surface)
            assertContrastAtLeast(4.5, positive, surface)
            assertContrastAtLeast(4.5, critical, surface)
        }
        with(MeridianTokens.Dark) {
            assertContrastAtLeast(4.5, brand, surface)
            assertContrastAtLeast(4.5, positive, surface)
            assertContrastAtLeast(4.5, critical, surface)
        }
    }

    @Test
    fun `on-brand text is legible on a brand fill`() {
        assertContrastAtLeast(4.5, MeridianTokens.Light.onBrand, MeridianTokens.Light.brand)
        assertContrastAtLeast(4.5, MeridianTokens.Dark.onBrand, MeridianTokens.Dark.brand)
    }

    @Test
    fun `every role differs from every other role within a mode`() {
        val light = with(MeridianTokens.Light) {
            listOf(canvas, surface, stroke, tint, ink, inkMuted, brand, brandStrong, positive, warning, critical)
        }
        assertEquals(light.size, light.distinct().size)
    }

    private fun assertContrastAtLeast(minimum: Double, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "Contrast ${"%.2f".format(ratio)}:1 is below the $minimum:1 floor",
            ratio >= minimum,
        )
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return if (la > lb) la / lb else lb / la
    }
}
```

The contrast tests are the reason this task carries tests at all: "legible in sunlight, 4.5:1" is a quality floor, and a floor that is not measured is a wish. They also catch a dark palette derived carelessly.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianTokensTest*'`
Expected: FAIL — `MeridianTokens` does not exist.

- [ ] **Step 3: Write the implementation**

Create `android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianTokens.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.ui.graphics.Color

/**
 * The single source of colour truth. This is the ONLY file in the module permitted
 * to contain a `Color(0x…)` literal — `scripts/check-meridian-tokens.sh` fails the
 * build otherwise, so a colour cannot enter the app without entering this table first.
 *
 * Roles, not names: consumers reference `MeridianTheme.colors.critical`, never a hex.
 */
internal object MeridianTokens {

    /** The calm cool-gray field with a single blue. */
    object Light {
        val canvas = Color(0xFFF4F5F7)
        val surface = Color(0xFFFFFFFF)
        val stroke = Color(0xFFECEDF1)
        val tint = Color(0xFFEEF0FB)
        val ink = Color(0xFF1B1D28)
        val inkMuted = Color(0xFF8A8D9B)

        val brand = Color(0xFF1B62C9)
        val brandStrong = Color(0xFF154FA6)
        val onBrand = Color(0xFFFFFFFF)
        val onBrandMuted = Color(0xFFDCE8FF)

        /** Reuses ink — there is deliberately no separate navy. */
        val strong = ink

        val positive = Color(0xFF2E9E5B)
        val warning = Color(0xFFE08A1E)
        val critical = Color(0xFFD64545)

        /** Equals brand on purpose: an in-flight item is not an alarm. */
        val info = brand
    }

    /**
     * Same roles, re-derived for a dark ground. Hues are held; lightness is inverted
     * for the neutrals and raised for the accents so each still clears 4.5:1 on the
     * dark surface — a straight reuse of the light accents fails that badly.
     */
    object Dark {
        val canvas = Color(0xFF0F1014)
        val surface = Color(0xFF17181D)
        val stroke = Color(0xFF2A2C35)
        val tint = Color(0xFF1D2739)
        val ink = Color(0xFFEDEEF2)
        val inkMuted = Color(0xFF9B9EAC)

        val brand = Color(0xFF6BA5F0)
        val brandStrong = Color(0xFF8FBDF5)
        val onBrand = Color(0xFF0F1014)
        val onBrandMuted = Color(0xFF16324F)

        val strong = ink

        val positive = Color(0xFF5FC98D)
        val warning = Color(0xFFF0AD52)
        val critical = Color(0xFFEE7C7C)

        val info = brand
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianTokensTest*'`
Expected: PASS, 10 tests.

If a contrast assertion fails, adjust the **dark** token until it passes — the light palette is fixed by specification, the dark one is derived and is the side that yields.

- [ ] **Step 5: Write the token gate**

Create `android/scripts/check-meridian-tokens.sh`:

```bash
#!/usr/bin/env bash
# Design-system gate. Runs first in CI: cheapest check, most likely to catch drift.
set -euo pipefail

MODULE="android/meridian-compose/src"
TOKENS="$MODULE/main/kotlin/com/slipstream/meridian/MeridianTokens.kt"
status=0

echo "==> Colour literals outside MeridianTokens.kt"
offenders=$(grep -rn --include="*.kt" -E "Color\(0x[0-9A-Fa-f]{6,8}\)" "$MODULE" \
  | grep -v "MeridianTokens.kt" || true)
if [ -n "$offenders" ]; then
  echo "FAIL: colours must be declared in MeridianTokens.kt, not inline:"
  echo "$offenders"
  status=1
fi

echo "==> isSystemInDarkTheme() call sites"
darkCalls=$(grep -rn --include="*.kt" "isSystemInDarkTheme()" "$MODULE" \
  | grep -v "MeridianTheme.kt" || true)
if [ -n "$darkCalls" ]; then
  echo "FAIL: isSystemInDarkTheme() may only be called in MeridianTheme.kt:"
  echo "$darkCalls"
  status=1
fi

echo "==> Elevation above 1dp"
elevation=$(grep -rn --include="*.kt" -E "(shadow|tonal)Elevation\s*=\s*([2-9]|[1-9][0-9])\.dp" "$MODULE" || true)
if [ -n "$elevation" ]; then
  echo "FAIL: Meridian caps elevation at 1dp — structure comes from strokes:"
  echo "$elevation"
  status=1
fi

echo "==> ALL CAPS text transforms"
caps=$(grep -rn --include="*.kt" -E "\.uppercase\(\)|textAllCaps" "$MODULE" || true)
if [ -n "$caps" ]; then
  echo "FAIL: sentence case everywhere — never ALL CAPS:"
  echo "$caps"
  status=1
fi

echo "==> Hardcoded left/right instead of start/end"
sides=$(grep -rn --include="*.kt" -E "padding(Left|Right)\s*=|Alignment\.(CenterLeft|CenterRight)" "$MODULE" || true)
if [ -n "$sides" ]; then
  echo "FAIL: use start/end, never left/right:"
  echo "$sides"
  status=1
fi

echo "==> Every token role present in both modes"
for role in canvas surface stroke tint ink inkMuted brand brandStrong onBrand onBrandMuted strong positive warning critical info; do
  count=$(grep -cE "val $role\b" "$TOKENS" || true)
  if [ "$count" -lt 2 ]; then
    echo "FAIL: role '$role' is not defined in both Light and Dark."
    status=1
  fi
done

[ "$status" -eq 0 ] && echo "All Meridian checks passed."
exit "$status"
```

- [ ] **Step 6: Verify the gate passes and actually catches violations**

```bash
chmod +x android/scripts/check-meridian-tokens.sh
bash android/scripts/check-meridian-tokens.sh
```

Expected: `All Meridian checks passed.`

Then prove it bites — temporarily add `val bad = Color(0xFF00FF00)` to `MeridianShapes.kt` (create the file with just that line), re-run, confirm it fails, and delete it. A gate never seen failing is a gate nobody knows is broken.

- [ ] **Step 7: Commit**

```bash
git add android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianTokens.kt android/meridian-compose/src/test android/scripts/check-meridian-tokens.sh
git commit -m "feat: add Meridian tokens with contrast tests and a design-system gate"
```

---

## Task 3: Colour roles, the full M3 mapping, and the theme

The highest-value task in this plan. Spec §13 names the unmapped-role trap as the one that fails silently with no crash, no lint warning, and no IDE preview difference.

**Files:**
- Create: `android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianColors.kt`
- Create: `android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianTheme.kt`
- Test: `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/MeridianThemeTest.kt`

**Interfaces:**
- Consumes: `MeridianTokens`.
- Produces:
  - `@Immutable data class MeridianColors(...)` with all fifteen roles plus `isDark: Boolean`
  - `val LocalMeridianColors: ProvidableCompositionLocal<MeridianColors>`
  - `@Composable fun MeridianTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
  - `object MeridianTheme { val colors: MeridianColors; val spacing: MeridianSpacing; val shapes: MeridianShapes }` accessors (typography and shapes arrive in Task 4; stub them here and fill them in there).

- [ ] **Step 1: Write the failing test**

Create `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/MeridianThemeTest.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.full.memberProperties

@RunWith(RobolectricTestRunner::class)
class MeridianThemeTest {

    @get:Rule
    val compose = createComposeRule()

    /** Material's baseline palette. Any of these appearing means a role went unmapped. */
    private val materialBaselineColors = setOf(
        Color(0xFF6650a4), Color(0xFFD0BCFF), Color(0xFF625b71), Color(0xFFCCC2DC),
        Color(0xFF7D5260), Color(0xFFEFB8C8), Color(0xFFFFFBFE), Color(0xFF1C1B1F),
        Color(0xFFE7E0EC), Color(0xFF49454F),
    )

    private fun captureScheme(dark: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        compose.setContent {
            MeridianTheme(darkTheme = dark) { scheme = MaterialTheme.colorScheme }
        }
        return scheme
    }

    private fun captureColors(dark: Boolean): MeridianColors {
        lateinit var colors: MeridianColors
        compose.setContent {
            MeridianTheme(darkTheme = dark) { colors = MeridianTheme.colors }
        }
        return colors
    }

    @Test
    fun `no Material 3 role retains its baseline lavender in light mode`() {
        assertNoBaselineColors(captureScheme(dark = false))
    }

    @Test
    fun `no Material 3 role retains its baseline lavender in dark mode`() {
        assertNoBaselineColors(captureScheme(dark = true))
    }

    @Test
    fun `primary maps to brand`() {
        assertEquals(MeridianTokens.Light.brand, captureScheme(false).primary)
        assertEquals(MeridianTokens.Dark.brand, captureScheme(true).primary)
    }

    @Test
    fun `background maps to canvas and surface maps to surface`() {
        val scheme = captureScheme(false)
        assertEquals(MeridianTokens.Light.canvas, scheme.background)
        assertEquals(MeridianTokens.Light.surface, scheme.surface)
    }

    @Test
    fun `error maps to critical`() {
        assertEquals(MeridianTokens.Light.critical, captureScheme(false).error)
    }

    @Test
    fun `outline maps to stroke`() {
        assertEquals(MeridianTokens.Light.stroke, captureScheme(false).outline)
    }

    @Test
    fun `onSurfaceVariant maps to ink muted`() {
        assertEquals(MeridianTokens.Light.inkMuted, captureScheme(false).onSurfaceVariant)
    }

    @Test
    fun `the Meridian role set is exposed and mode aware`() {
        val light = captureColors(dark = false)
        val dark = captureColors(dark = true)

        assertEquals(MeridianTokens.Light.canvas, light.canvas)
        assertEquals(MeridianTokens.Dark.canvas, dark.canvas)
        assertTrue(dark.isDark)
        assertTrue(!light.isDark)
        assertNotEquals(light.brand, dark.brand)
    }

    @Test
    fun `status roles are available and distinct`() {
        val colors = captureColors(dark = false)

        assertEquals(MeridianTokens.Light.positive, colors.positive)
        assertEquals(MeridianTokens.Light.warning, colors.warning)
        assertEquals(MeridianTokens.Light.critical, colors.critical)
        assertEquals(colors.brand, colors.info)
    }

    private fun assertNoBaselineColors(scheme: ColorScheme) {
        val unmapped = ColorScheme::class.memberProperties
            .filter { it.returnType.classifier == Color::class }
            .mapNotNull { property ->
                @Suppress("UNCHECKED_CAST")
                val value = (property as kotlin.reflect.KProperty1<ColorScheme, Color>).get(scheme)
                if (value in materialBaselineColors) "${property.name} = $value" else null
            }

        assertTrue(
            "These Material roles are unmapped and will render Material's baseline palette: $unmapped",
            unmapped.isEmpty(),
        )
    }
}
```

That reflection test is the whole point: it enumerates *every* `Color` property on `ColorScheme` and fails if any still holds a Material baseline value. It catches roles nobody remembered to map, including ones added by a future Compose version.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianThemeTest*'`
Expected: FAIL — `MeridianTheme` does not exist.

- [ ] **Step 3: Write the colour roles**

Create `android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianColors.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Meridian's role set. Material 3 has no slot for canvas, tint, ink-muted, or the
 * three status signals, so they live here rather than being forced into M3 roles
 * that mean something else.
 */
@Immutable
data class MeridianColors(
    val canvas: Color,
    val surface: Color,
    val stroke: Color,
    val tint: Color,
    val ink: Color,
    val inkMuted: Color,
    val brand: Color,
    val brandStrong: Color,
    val onBrand: Color,
    val onBrandMuted: Color,
    val strong: Color,
    val positive: Color,
    val warning: Color,
    val critical: Color,
    val info: Color,
    val isDark: Boolean,
)

internal val LightMeridianColors = with(MeridianTokens.Light) {
    MeridianColors(
        canvas = canvas, surface = surface, stroke = stroke, tint = tint,
        ink = ink, inkMuted = inkMuted,
        brand = brand, brandStrong = brandStrong, onBrand = onBrand, onBrandMuted = onBrandMuted,
        strong = strong,
        positive = positive, warning = warning, critical = critical, info = info,
        isDark = false,
    )
}

internal val DarkMeridianColors = with(MeridianTokens.Dark) {
    MeridianColors(
        canvas = canvas, surface = surface, stroke = stroke, tint = tint,
        ink = ink, inkMuted = inkMuted,
        brand = brand, brandStrong = brandStrong, onBrand = onBrand, onBrandMuted = onBrandMuted,
        strong = strong,
        positive = positive, warning = warning, critical = critical, info = info,
        isDark = true,
    )
}

val LocalMeridianColors = staticCompositionLocalOf { LightMeridianColors }

/**
 * EVERY role is mapped, deliberately and exhaustively.
 *
 * Leaving one out does not crash, does not warn, and looks fine in the IDE preview —
 * it simply renders Material's baseline lavender on one stock control, in production.
 * Do not delete a line here because "nothing uses it": a future stock component will.
 */
internal fun MeridianColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()

    return base.copy(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = tint,
        onPrimaryContainer = if (isDark) ink else brandStrong,
        inversePrimary = brandStrong,

        secondary = brandStrong,
        onSecondary = onBrand,
        secondaryContainer = tint,
        onSecondaryContainer = if (isDark) ink else brandStrong,

        // Meridian has no third accent. Tertiary mirrors brand so a stock component
        // reaching for it cannot introduce a colour the system does not own.
        tertiary = brand,
        onTertiary = onBrand,
        tertiaryContainer = tint,
        onTertiaryContainer = if (isDark) ink else brandStrong,

        background = canvas,
        onBackground = ink,

        surface = surface,
        onSurface = ink,
        surfaceVariant = tint,
        onSurfaceVariant = inkMuted,
        surfaceTint = brand,
        inverseSurface = strong,
        inverseOnSurface = if (isDark) canvas else surface,

        surfaceBright = surface,
        surfaceDim = canvas,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = canvas,
        surfaceContainerHigh = canvas,
        surfaceContainerHighest = canvas,

        error = critical,
        onError = onBrand,
        errorContainer = critical.copy(alpha = 0.12f),
        onErrorContainer = critical,

        outline = stroke,
        outlineVariant = stroke,
        scrim = Color.Black.copy(alpha = 0.4f),
    )
}
```

- [ ] **Step 4: Write the theme**

Create `android/meridian-compose/src/main/kotlin/com/slipstream/meridian/MeridianTheme.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The one and only call site of [isSystemInDarkTheme]. Reading it anywhere else lets
 * two screens disagree about which mode they are in, with nothing to catch it —
 * `scripts/check-meridian-tokens.sh` fails the build if another call appears.
 */
@Composable
fun MeridianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkMeridianColors else LightMeridianColors

    CompositionLocalProvider(
        LocalMeridianColors provides colors,
        LocalMeridianSpacing provides MeridianSpacing,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = meridianTypography(),
            shapes = meridianShapes(),
            content = content,
        )
    }
}

/** Accessors, so call sites read `MeridianTheme.colors.critical`. */
object MeridianTheme {
    val colors: MeridianColors
        @Composable @ReadOnlyComposable get() = LocalMeridianColors.current

    val spacing: MeridianSpacing
        @Composable @ReadOnlyComposable get() = LocalMeridianSpacing.current
}
```

This will not compile until Task 4 supplies `meridianTypography()`, `meridianShapes()`, `MeridianSpacing`, and `LocalMeridianSpacing`. Do Task 4 now and run both test suites together — these four files are one unit.

- [ ] **Step 5: Commit after Task 4 compiles**

Hold this commit until Task 4 Step 4 passes. Then:

```bash
git add android/meridian-compose/src/main/kotlin/com/slipstream/meridian
git commit -m "feat: add Meridian colour roles with exhaustive Material 3 mapping"
```

---

## Task 4: Typography, shapes, and spacing

**Files:**
- Create: `MeridianTypography.kt`, `MeridianShapes.kt`, `MeridianSpacing.kt`
- Test: `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/MeridianTypographyTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun meridianTypography(): Typography`
  - `object MeridianText { val heroMetric, screenTitle, itemTitle, body, label, labelBold, micro, button: TextStyle }` — all numeric styles carry `fontFeatureSettings = "tnum"`
  - `fun meridianShapes(): Shapes`, `object MeridianRadius { val sm = 12.dp; val md = 14.dp; val lg = 16.dp; val pill = RoundedCornerShape(50) }`
  - `@Immutable object MeridianSpacing { val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 20.dp; val xxl = 24.dp; val screen = 16.dp; val cardInner = 12.dp; val section = 20.dp }`
  - `val LocalMeridianSpacing: ProvidableCompositionLocal<MeridianSpacing>`

- [ ] **Step 1: Write the failing test**

Create `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/MeridianTypographyTest.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeridianTypographyTest {

    @Test
    fun `numeric styles request tabular figures`() {
        // Without tnum a live MB/s readout visibly jitters as digits change width.
        listOf(
            "heroMetric" to MeridianText.heroMetric,
            "itemTitle" to MeridianText.itemTitle,
            "label" to MeridianText.label,
            "labelBold" to MeridianText.labelBold,
        ).forEach { (name, style) ->
            assertEquals("$name must request tabular figures", "tnum", style.fontFeatureSettings)
        }
    }

    @Test
    fun `the hero metric is 40sp bold`() {
        assertEquals(40.sp, MeridianText.heroMetric.fontSize)
        assertEquals(FontWeight.Bold, MeridianText.heroMetric.fontWeight)
    }

    @Test
    fun `the scale matches the specification`() {
        assertEquals(20.sp, MeridianText.screenTitle.fontSize)
        assertEquals(15.sp, MeridianText.itemTitle.fontSize)
        assertEquals(14.sp, MeridianText.body.fontSize)
        assertEquals(12.sp, MeridianText.label.fontSize)
        assertEquals(11.sp, MeridianText.micro.fontSize)
    }

    @Test
    fun `titles are bold and body is regular`() {
        assertEquals(FontWeight.Bold, MeridianText.screenTitle.fontWeight)
        assertEquals(FontWeight.Bold, MeridianText.itemTitle.fontWeight)
        assertEquals(FontWeight.Normal, MeridianText.body.fontWeight)
    }

    @Test
    fun `no style applies letter spacing beyond the default`() {
        listOf(MeridianText.body, MeridianText.itemTitle, MeridianText.screenTitle).forEach {
            assertTrue(it.letterSpacing.isUnspecified || it.letterSpacing.value == 0f)
        }
    }

    @Test
    fun `radius steps match the specification`() {
        assertEquals(12.dp, MeridianRadius.sm)
        assertEquals(14.dp, MeridianRadius.md)
        assertEquals(16.dp, MeridianRadius.lg)
        assertEquals(RoundedCornerShape(50), MeridianRadius.pill)
    }

    @Test
    fun `nothing is sharper than the sm step`() {
        val shapes = meridianShapes()
        listOf(shapes.extraSmall, shapes.small, shapes.medium, shapes.large, shapes.extraLarge)
            .forEach { assertTrue("Zero radius is never used in Meridian", it != RoundedCornerShape(0.dp)) }
    }

    @Test
    fun `spacing follows the 4pt grid`() {
        listOf(
            MeridianSpacing.xs, MeridianSpacing.sm, MeridianSpacing.md,
            MeridianSpacing.lg, MeridianSpacing.xl, MeridianSpacing.xxl,
        ).forEach {
            assertEquals("${it.value}dp is off the 4pt grid", 0f, it.value % 4f, 0.001f)
        }
    }

    @Test
    fun `semantic spacing matches the specification`() {
        assertEquals(16.dp, MeridianSpacing.screen)
        assertEquals(12.dp, MeridianSpacing.cardInner)
        assertEquals(20.dp, MeridianSpacing.section)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianTypographyTest*'`
Expected: FAIL — the types do not exist.

- [ ] **Step 3: Write the implementations**

Create `MeridianTypography.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Semantic roles, not pixel names. Every style that can carry a number requests
 * `tnum`: this app's content is rates, sizes, and percentages that update several
 * times a second, and proportional figures make the readout visibly jitter.
 */
object MeridianText {

    /** One big number per screen. In Slipstream, the live transfer rate. */
    val heroMetric = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = TABULAR,
    )

    val screenTitle = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
    )

    val itemTitle = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = TABULAR,
    )

    val body = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
    )

    val label = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
        fontFeatureSettings = TABULAR,
    )

    val labelBold = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = TABULAR,
    )

    /** Dense secondary data only. Avoid. */
    val micro = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal,
    )

    val button = TextStyle(
        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
    )

    internal const val TABULAR = "tnum"
}

@Composable
internal fun meridianTypography(): Typography = Typography(
    displayLarge = MeridianText.heroMetric,
    displayMedium = MeridianText.heroMetric,
    headlineMedium = MeridianText.screenTitle,
    headlineSmall = MeridianText.screenTitle,
    titleLarge = MeridianText.screenTitle,
    titleMedium = MeridianText.itemTitle,
    titleSmall = MeridianText.itemTitle,
    bodyLarge = MeridianText.body,
    bodyMedium = MeridianText.body,
    bodySmall = MeridianText.label,
    labelLarge = MeridianText.button,
    labelMedium = MeridianText.label,
    labelSmall = MeridianText.micro,
)
```

Create `MeridianShapes.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Three steps plus pill. Zero radius is never used; nothing is sharper than [sm]. */
object MeridianRadius {
    /** Controls, chips, icon tiles, inner panels, thumbnails. */
    val sm = 12.dp

    /** Buttons, search and input fields. */
    val md = 14.dp

    /** Cards, sheets, feature surfaces. */
    val lg = 16.dp

    /** Avatars, count badges, filter chips. */
    val pill = RoundedCornerShape(50)
}

internal fun meridianShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(MeridianRadius.sm),
    small = RoundedCornerShape(MeridianRadius.sm),
    medium = RoundedCornerShape(MeridianRadius.md),
    large = RoundedCornerShape(MeridianRadius.lg),
    extraLarge = RoundedCornerShape(MeridianRadius.lg),
)
```

Create `MeridianSpacing.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** The 4pt grid, plus the semantic defaults that keep screens consistent. */
@Immutable
object MeridianSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    /** Outer padding of a scrolling screen. */
    val screen = 16.dp

    /** Padding inside a list-row card. */
    val cardInner = 12.dp

    /** Gap between titled sections. */
    val section = 20.dp

    /** Minimum tap target. */
    val touchTarget = 44.dp
}

val LocalMeridianSpacing = staticCompositionLocalOf { MeridianSpacing }
```

- [ ] **Step 4: Run both suites to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest`
Expected: PASS — `MeridianTokensTest`, `MeridianThemeTest`, and `MeridianTypographyTest`, 27 tests total.

- [ ] **Step 5: Run the token gate**

Run: `bash android/scripts/check-meridian-tokens.sh`
Expected: `All Meridian checks passed.`

- [ ] **Step 6: Commit both tasks**

```bash
git add android/meridian-compose/src
git commit -m "feat: add Meridian typography, shapes, spacing, and the theme entry point"
```

---

## Task 5: `MeridianCard` and `MeridianSectionHeader`

The card carries spec §13's first Compose trap: `Surface` applies tonal elevation by default, which tints the surface colour and silently drifts every card off-token.

**Files:**
- Create: `component/MeridianCard.kt`, `component/MeridianSectionHeader.kt`
- Test: `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/component/MeridianCardTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun MeridianCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun MeridianSectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onActionClick: (() -> Unit)? = null)`

- [ ] **Step 1: Write the failing test**

Create `android/meridian-compose/src/test/kotlin/com/slipstream/meridian/component/MeridianCardTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders its content`() {
        compose.setContent { MeridianTheme { MeridianCard { Text("Inside the card") } } }
        compose.onNodeWithText("Inside the card").assertIsDisplayed()
    }

    @Test
    fun `is not clickable without an onClick`() {
        compose.setContent { MeridianTheme { MeridianCard { Text("Static") } } }

        var threw = false
        try {
            compose.onNodeWithText("Static").assertHasClickAction()
        } catch (_: AssertionError) {
            threw = true
        }
        assertTrue("A card with no onClick must not advertise a click action", threw)
    }

    @Test
    fun `invokes onClick when given one`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianCard(onClick = { clicks++ }) { Text("Tap me") } }
        }

        compose.onNodeWithText("Tap me").performClick()
        assertTrue(clicks == 1)
    }

    @Test
    fun `renders in dark mode`() {
        compose.setContent {
            MeridianTheme(darkTheme = true) { MeridianCard { Text("Dark card") } }
        }
        compose.onNodeWithText("Dark card").assertIsDisplayed()
    }

    @Test
    fun `section header renders its title`() {
        compose.setContent { MeridianTheme { MeridianSectionHeader(title = "Transfers") } }
        compose.onNodeWithText("Transfers").assertIsDisplayed()
    }

    @Test
    fun `section header action fires`() {
        var clicked = false
        compose.setContent {
            MeridianTheme {
                MeridianSectionHeader(
                    title = "Transfers",
                    actionLabel = "See all",
                    onActionClick = { clicked = true },
                )
            }
        }

        compose.onNodeWithText("See all").performClick()
        assertTrue(clicked)
    }

    @Test
    fun `section header hides the action when no label is given`() {
        compose.setContent { MeridianTheme { MeridianSectionHeader(title = "Transfers") } }
        compose.onNodeWithText("See all").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianCardTest*'`
Expected: FAIL — the components do not exist.

- [ ] **Step 3: Write the implementation**

Create `component/MeridianCard.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianTheme

/**
 * The atom of every screen: Surface fill, `lg` radius, 1px stroke, elevation 0.
 *
 * TRAP (spec §13): Material 3's `Surface`/`Card` apply *tonal* elevation by default,
 * which tints the surface colour rather than casting a shadow. Meridian's structure
 * comes from the stroke, so both elevations are pinned to 0.dp. Omitting either one
 * drifts every card off-token with nothing to catch it — no crash, no warning, and
 * the IDE preview looks fine.
 *
 * The card carries no content padding: inset belongs to the caller's content, so one
 * card style can wrap a padded Column or a Constraint-style layout without fighting it.
 */
@Composable
fun MeridianCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MeridianTheme.colors

    val cardColors = CardDefaults.cardColors(
        containerColor = colors.surface,
        contentColor = colors.ink,
    )
    val elevation = CardDefaults.cardElevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp,
    )
    val border = BorderStroke(1.dp, colors.stroke)
    val shape = RoundedCornerShape(MeridianRadius.lg)

    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = cardColors,
            elevation = elevation,
            border = border,
            content = content,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = cardColors,
            elevation = elevation,
            border = border,
            content = content,
        )
    }
}

@Preview(name = "Card light")
@Composable
private fun MeridianCardLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianCard { Text("Card content", modifier = Modifier.padding(16.dp)) }
    }
}

@Preview(name = "Card dark")
@Composable
private fun MeridianCardDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianCard { Text("Card content", modifier = Modifier.padding(16.dp)) }
    }
}
```

Add `import androidx.compose.foundation.layout.padding` for the previews.

Create `component/MeridianSectionHeader.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** A section title, optionally with a trailing tertiary action. */
@Composable
fun MeridianSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MeridianSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MeridianText.itemTitle,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(text = actionLabel, style = MeridianText.button, color = colors.brand)
            }
        }
    }
}

@Preview(name = "Section header light")
@Composable
private fun MeridianSectionHeaderLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianSectionHeader(title = "Transfers", actionLabel = "See all", onActionClick = {})
    }
}

@Preview(name = "Section header dark")
@Composable
private fun MeridianSectionHeaderDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianSectionHeader(title = "Transfers", actionLabel = "See all", onActionClick = {})
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianCardTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src/main/kotlin/com/slipstream/meridian/component android/meridian-compose/src/test
git commit -m "feat: add MeridianCard and MeridianSectionHeader"
```

---

## Task 6: `MeridianIconTile` and `MeridianStatusPill`

**Files:**
- Create: `component/MeridianIconTile.kt`, `component/MeridianStatusPill.kt`
- Test: `component/MeridianStatusPillTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun MeridianIconTile(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 48.dp, onClick: (() -> Unit)? = null)`
  - `enum class MeridianStatus { Positive, Warning, Critical, Info, Neutral }`
  - `@Composable fun MeridianStatusPill(status: MeridianStatus, label: String, modifier: Modifier = Modifier, icon: ImageVector? = null)`
  - The label is **required** — spec §12's rule that colour is never the only cue is expressed in the API, not left to discipline.

- [ ] **Step 1: Write the failing test**

Create `component/MeridianStatusPillTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianStatusPillTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `always shows a text label alongside the colour`() {
        // Colour is never the only cue. This is enforced by the API, not by discipline.
        compose.setContent {
            MeridianTheme { MeridianStatusPill(MeridianStatus.Critical, "Transfer failed") }
        }
        compose.onNodeWithText("Transfer failed").assertIsDisplayed()
    }

    @Test
    fun `renders every status in light and dark`() {
        MeridianStatus.entries.forEach { status ->
            listOf(false, true).forEach { dark ->
                compose.setContent {
                    MeridianTheme(darkTheme = dark) { MeridianStatusPill(status, status.name) }
                }
                compose.onNodeWithText(status.name).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `shows an icon when one is supplied`() {
        compose.setContent {
            MeridianTheme {
                MeridianStatusPill(
                    status = MeridianStatus.Warning,
                    label = "2.4 GHz — slower link",
                    icon = Icons.Filled.Wifi,
                )
            }
        }
        compose.onNodeWithContentDescription("Warning").assertIsDisplayed()
    }

    @Test
    fun `there are exactly five statuses`() {
        assertEquals(5, MeridianStatus.entries.size)
    }

    @Test
    fun `icon tile renders and is clickable when given a handler`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme {
                MeridianIconTile(
                    icon = Icons.Filled.Wifi,
                    contentDescription = "Send files",
                    onClick = { clicks++ },
                )
            }
        }

        compose.onNodeWithContentDescription("Send files").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `icon tile renders without a handler`() {
        compose.setContent {
            MeridianTheme { MeridianIconTile(Icons.Filled.Wifi, "Decorative") }
        }
        compose.onNodeWithContentDescription("Decorative").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianStatusPillTest*'`
Expected: FAIL — the components do not exist.

- [ ] **Step 3: Write the implementation**

Create `component/MeridianIconTile.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianTheme

/** 48dp square, Tint fill, `sm` radius, Brand-tinted line icon at ~26dp. */
@Composable
fun MeridianIconTile(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    Box(
        modifier = modifier
            .size(size)
            .background(colors.tint, RoundedCornerShape(MeridianRadius.sm))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.brand,
            modifier = Modifier.size(size * 0.54f),
        )
    }
}

@Preview(name = "Icon tile light")
@Composable
private fun MeridianIconTileLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianIconTile(Icons.Filled.Folder, "Folder") }
}

@Preview(name = "Icon tile dark")
@Composable
private fun MeridianIconTileDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianIconTile(Icons.Filled.Folder, "Folder") }
}
```

Create `component/MeridianStatusPill.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** The three signals, plus in-flight and a neutral. */
enum class MeridianStatus {
    /** Connected, synced, complete. */
    Positive,

    /** Degraded but working — a slow link, a partial result. */
    Warning,

    /** Failed, lost, rejected. */
    Critical,

    /** In flight. Shares Brand: an in-progress item is not an alarm. */
    Info,

    /** Idle or not applicable. */
    Neutral,
}

/**
 * A status word in its signal colour, optionally with an icon.
 *
 * The `label` is required by design: spec §12 mandates that a status never relies on
 * colour alone, so the API makes the non-colour cue impossible to omit rather than
 * leaving it to reviewer discipline.
 *
 * Status colours are for text and small marks — never large fills. A screen full of
 * red is noise, not signal.
 */
@Composable
fun MeridianStatusPill(
    status: MeridianStatus,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = MeridianTheme.colors

    val signal: Color = when (status) {
        MeridianStatus.Positive -> colors.positive
        MeridianStatus.Warning -> colors.warning
        MeridianStatus.Critical -> colors.critical
        MeridianStatus.Info -> colors.info
        MeridianStatus.Neutral -> colors.inkMuted
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = status.name,
                tint = signal,
                modifier = Modifier.size(14.dp),
            )
        }

        Text(text = label, style = MeridianText.labelBold, color = signal)
    }
}

@Preview(name = "Status pills light")
@Composable
private fun MeridianStatusPillLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStatusPill(MeridianStatus.Positive, "Connected", icon = Icons.Filled.CheckCircle)
    }
}

@Preview(name = "Status pills dark")
@Composable
private fun MeridianStatusPillDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStatusPill(MeridianStatus.Critical, "Transfer failed")
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianStatusPillTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src
git commit -m "feat: add MeridianIconTile and MeridianStatusPill"
```

---

## Task 7: `MeridianListRow`

**Files:**
- Create: `component/MeridianListRow.kt`
- Test: `component/MeridianListRowTest.kt`

**Interfaces:**
- Produces: `@Composable fun MeridianListRow(title: String, modifier: Modifier = Modifier, meta: String? = null, trailingValue: String? = null, status: Pair<MeridianStatus, String>? = null, leading: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null)`

- [ ] **Step 1: Write the failing test**

Create `component/MeridianListRowTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianListRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows title meta and trailing value`() {
        compose.setContent {
            MeridianTheme {
                MeridianListRow(title = "holiday.mkv", meta = "24 Aug 2026", trailingValue = "4.2 GB")
            }
        }

        compose.onNodeWithText("holiday.mkv").assertIsDisplayed()
        compose.onNodeWithText("24 Aug 2026").assertIsDisplayed()
        compose.onNodeWithText("4.2 GB").assertIsDisplayed()
    }

    @Test
    fun `shows a status when supplied`() {
        compose.setContent {
            MeridianTheme {
                MeridianListRow(
                    title = "holiday.mkv",
                    status = MeridianStatus.Info to "Transferring",
                )
            }
        }
        compose.onNodeWithText("Transferring").assertIsDisplayed()
    }

    @Test
    fun `renders a leading slot`() {
        compose.setContent {
            MeridianTheme {
                MeridianListRow(
                    title = "holiday.mkv",
                    leading = { MeridianIconTile(Icons.Filled.Movie, "Video") },
                )
            }
        }
        compose.onNodeWithContentDescription("Video").assertIsDisplayed()
    }

    @Test
    fun `invokes onClick`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianListRow(title = "Tap row", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Tap row").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `omits optional slots cleanly`() {
        compose.setContent { MeridianTheme { MeridianListRow(title = "Minimal") } }
        compose.onNodeWithText("Minimal").assertIsDisplayed()
    }

    @Test
    fun `renders in dark mode`() {
        compose.setContent {
            MeridianTheme(darkTheme = true) {
                MeridianListRow(title = "Dark row", meta = "meta", trailingValue = "1.0 GB")
            }
        }
        compose.onNodeWithText("Dark row").assertIsDisplayed()
    }
}
```

Add `import androidx.compose.ui.test.onNodeWithContentDescription`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianListRowTest*'`
Expected: FAIL — `MeridianListRow` does not exist.

- [ ] **Step 3: Write the implementation**

Create `component/MeridianListRow.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The workhorse row: optional leading slot, a bold title with muted meta beneath,
 * and a trailing value or status. Titles cap at two lines then ellipsize.
 */
@Composable
fun MeridianListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    trailingValue: String? = null,
    status: Pair<MeridianStatus, String>? = null,
    leading: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    MeridianCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MeridianSpacing.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
        ) {
            leading?.invoke()

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs / 2),
            ) {
                Text(
                    text = title,
                    style = MeridianText.itemTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (meta != null) {
                    Text(
                        text = meta,
                        style = MeridianText.label,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailingValue != null || status != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs / 2),
                ) {
                    if (trailingValue != null) {
                        Text(
                            text = trailingValue,
                            style = MeridianText.label,
                            color = colors.ink,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }

                    if (status != null) {
                        MeridianStatusPill(status = status.first, label = status.second)
                    }
                }
            }
        }
    }
}

@Preview(name = "List row light")
@Composable
private fun MeridianListRowLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianListRow(
            title = "holiday-2026.mkv",
            meta = "24 Aug 2026",
            trailingValue = "4.2 GB",
            status = MeridianStatus.Info to "Transferring",
        )
    }
}

@Preview(name = "List row dark")
@Composable
private fun MeridianListRowDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianListRow(
            title = "holiday-2026.mkv",
            meta = "24 Aug 2026",
            trailingValue = "4.2 GB",
            status = MeridianStatus.Positive to "Complete",
        )
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianListRowTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src
git commit -m "feat: add MeridianListRow"
```

---

## Task 8: `MeridianHeroMetric` and `MeridianStat`

**Files:**
- Create: `component/MeridianHeroMetric.kt`, `component/MeridianStat.kt`
- Test: `component/MeridianHeroMetricTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun MeridianHeroMetric(value: String, label: String, modifier: Modifier = Modifier, unit: String? = null)`
  - `@Composable fun MeridianStat(icon: ImageVector, value: String, caption: String, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing test**

Create `component/MeridianHeroMetricTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianHeroMetricTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shows the value the unit and the label`() {
        compose.setContent {
            MeridianTheme { MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate") }
        }

        compose.onNodeWithText("48.2").assertIsDisplayed()
        compose.onNodeWithText("MB/s").assertIsDisplayed()
        compose.onNodeWithText("Transfer rate").assertIsDisplayed()
    }

    @Test
    fun `works without a unit`() {
        compose.setContent { MeridianTheme { MeridianHeroMetric(value = "12", label = "Queued") } }
        compose.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `uses the tabular hero style`() {
        // A rate updating four times a second must not jitter.
        assertEquals("tnum", MeridianText.heroMetric.fontFeatureSettings)
    }

    @Test
    fun `renders in dark mode`() {
        compose.setContent {
            MeridianTheme(darkTheme = true) {
                MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate")
            }
        }
        compose.onNodeWithText("48.2").assertIsDisplayed()
    }

    @Test
    fun `stat shows value and caption`() {
        compose.setContent {
            MeridianTheme {
                MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")
            }
        }

        compose.onNodeWithText("8").assertIsDisplayed()
        compose.onNodeWithText("Queued").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianHeroMetricTest*'`
Expected: FAIL — the components do not exist.

- [ ] **Step 3: Write the implementation**

Create `component/MeridianHeroMetric.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * One big tabular number per screen, in Brand, with a small muted label above it.
 * Rare by design — a screen with two hero metrics has no hero metric.
 */
@Composable
fun MeridianHeroMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    val colors = MeridianTheme.colors

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
    ) {
        Text(text = label, style = MeridianText.label, color = colors.inkMuted)

        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = MeridianText.heroMetric, color = colors.brand)

            if (unit != null) {
                Text(
                    text = unit,
                    style = MeridianText.label,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(start = MeridianSpacing.xs, bottom = MeridianSpacing.sm),
                )
            }
        }
    }
}

@Preview(name = "Hero metric light")
@Composable
private fun MeridianHeroMetricLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate")
    }
}

@Preview(name = "Hero metric dark")
@Composable
private fun MeridianHeroMetricDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianHeroMetric(value = "4.6", unit = "MB/s", label = "Transfer rate")
    }
}
```

Create `component/MeridianStat.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** An icon tile, a number, and a caption — the compact three-up dashboard unit. */
@Composable
fun MeridianStat(
    icon: ImageVector,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        MeridianIconTile(icon = icon, contentDescription = caption, size = 40.dp)

        Column(verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs / 2)) {
            Text(text = value, style = MeridianText.itemTitle, color = colors.ink)
            Text(text = caption, style = MeridianText.label, color = colors.inkMuted)
        }
    }
}

@Preview(name = "Stat light")
@Composable
private fun MeridianStatLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")
    }
}

@Preview(name = "Stat dark")
@Composable
private fun MeridianStatDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")
    }
}
```

Add `import androidx.compose.ui.unit.dp`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianHeroMetricTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src
git commit -m "feat: add MeridianHeroMetric and MeridianStat"
```

---

## Task 9: `MeridianStateView` — the loading/empty/error triad

**Files:**
- Create: `component/MeridianStateView.kt`
- Test: `component/MeridianStateViewTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface MeridianUiState { data object Loading; data object Content; data class Empty(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null); data class Error(val message: String, val retryLabel: String = "Retry", val onRetry: (() -> Unit)? = null) }`
  - `@Composable fun MeridianStateView(state: MeridianUiState, modifier: Modifier = Modifier, content: @Composable () -> Unit)`

- [ ] **Step 1: Write the failing test**

Create `component/MeridianStateViewTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianStateViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: MeridianUiState) {
        compose.setContent {
            MeridianTheme { MeridianStateView(state) { Text("The real content") } }
        }
    }

    @Test
    fun `content state shows the content`() {
        show(MeridianUiState.Content)
        compose.onNodeWithText("The real content").assertIsDisplayed()
    }

    @Test
    fun `loading state hides the content and shows a spinner`() {
        show(MeridianUiState.Loading)

        compose.onNodeWithText("The real content").assertDoesNotExist()
        compose.onNodeWithTag("meridian-loading").assertIsDisplayed()
    }

    @Test
    fun `empty state shows its message instead of the content`() {
        show(MeridianUiState.Empty("Nothing here yet. Send a file to get started."))

        compose.onNodeWithText("The real content").assertDoesNotExist()
        compose.onNodeWithText("Nothing here yet. Send a file to get started.").assertIsDisplayed()
    }

    @Test
    fun `empty state can offer an action`() {
        var clicked = false
        compose.setContent {
            MeridianTheme {
                MeridianStateView(
                    MeridianUiState.Empty("No transfers yet.", "Send a file") { clicked = true },
                ) { Text("The real content") }
            }
        }

        compose.onNodeWithText("Send a file").performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun `error state shows the message and a retry`() {
        var retried = 0
        compose.setContent {
            MeridianTheme {
                MeridianStateView(
                    MeridianUiState.Error("Phone not on this network.", onRetry = { retried++ }),
                ) { Text("The real content") }
            }
        }

        compose.onNodeWithText("Phone not on this network.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `error state omits retry when no handler is given`() {
        show(MeridianUiState.Error("Something went wrong."))
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `every state renders in dark mode`() {
        listOf(
            MeridianUiState.Loading,
            MeridianUiState.Content,
            MeridianUiState.Empty("Empty"),
            MeridianUiState.Error("Error"),
        ).forEach { state ->
            compose.setContent {
                MeridianTheme(darkTheme = true) { MeridianStateView(state) { Text("Content") } }
            }
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianStateViewTest*'`
Expected: FAIL — `MeridianStateView` does not exist.

- [ ] **Step 3: Write the implementation**

Create `component/MeridianStateView.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The four mutually exclusive states every data-backed region has.
 *
 * Empty states invite action — "No data" is a floor, not a ceiling. Error messages
 * are direct and name the next step; they never apologise.
 */
sealed interface MeridianUiState {
    data object Loading : MeridianUiState

    data object Content : MeridianUiState

    data class Empty(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    ) : MeridianUiState

    data class Error(
        val message: String,
        val retryLabel: String = "Retry",
        val onRetry: (() -> Unit)? = null,
    ) : MeridianUiState
}

/**
 * One view driving all four states over the same bounds as the content it covers.
 * Using this instead of hand-toggled sibling views is what stops the "spinner and
 * empty text visible at once" class of bug.
 */
@Composable
fun MeridianStateView(
    state: MeridianUiState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MeridianTheme.colors

    when (state) {
        MeridianUiState.Content -> content()

        MeridianUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = colors.brand,
                modifier = Modifier.testTag("meridian-loading"),
            )
        }

        is MeridianUiState.Empty -> Message(
            modifier = modifier,
            message = state.message,
            messageColor = colors.inkMuted,
            actionLabel = state.actionLabel,
            onAction = state.onAction,
        )

        is MeridianUiState.Error -> Message(
            modifier = modifier,
            message = state.message,
            messageColor = colors.critical,
            actionLabel = if (state.onRetry != null) state.retryLabel else null,
            onAction = state.onRetry,
        )
    }
}

@Composable
private fun Message(
    modifier: Modifier,
    message: String,
    messageColor: androidx.compose.ui.graphics.Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm),
            modifier = Modifier.padding(MeridianSpacing.xxl),
        ) {
            Text(
                text = message,
                style = MeridianText.body,
                color = messageColor,
                textAlign = TextAlign.Center,
            )

            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        style = MeridianText.button,
                        color = MeridianTheme.colors.brand,
                    )
                }
            }
        }
    }
}

@Preview(name = "Empty state light")
@Composable
private fun MeridianStateViewEmptyPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianStateView(
            MeridianUiState.Empty("Nothing transferred yet. Pick a file to send.", "Send a file") {},
        ) {}
    }
}

@Preview(name = "Error state dark")
@Composable
private fun MeridianStateViewErrorPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianStateView(
            MeridianUiState.Error("Phone not on this network. Searching…", onRetry = {}),
        ) {}
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianStateViewTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src
git commit -m "feat: add MeridianStateView for the loading, empty, and error triad"
```

---

## Task 10: `MeridianSearchField`, `MeridianFilterChip`, `MeridianBadge`

**Files:**
- Create: `component/MeridianSearchField.kt`, `component/MeridianFilterChip.kt`, `component/MeridianBadge.kt`
- Test: `component/MeridianControlsTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun MeridianSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search")`
  - `@Composable fun MeridianFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun MeridianBadge(count: Int, modifier: Modifier = Modifier, critical: Boolean = false)` — renders nothing at zero.

- [ ] **Step 1: Write the failing test**

Create `component/MeridianControlsTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `search field shows its placeholder and reports typing`() {
        var typed = ""
        compose.setContent {
            MeridianTheme {
                MeridianSearchField(value = "", onValueChange = { typed = it }, placeholder = "Search files")
            }
        }

        compose.onNodeWithText("Search files").assertIsDisplayed()
        compose.onNodeWithText("Search files").performTextInput("holiday")
        assertEquals("holiday", typed)
    }

    @Test
    fun `filter chip reports selection`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianFilterChip("Video", selected = false, onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Video").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `filter chip renders selected and unselected in both modes`() {
        listOf(false, true).forEach { dark ->
            listOf(false, true).forEach { selected ->
                compose.setContent {
                    MeridianTheme(darkTheme = dark) {
                        MeridianFilterChip("Video", selected = selected, onClick = {})
                    }
                }
                compose.onNodeWithText("Video").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `badge shows a positive count`() {
        compose.setContent { MeridianTheme { MeridianBadge(count = 7) } }
        compose.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun `badge is hidden at zero`() {
        compose.setContent { MeridianTheme { MeridianBadge(count = 0) } }
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `badge caps very large counts`() {
        compose.setContent { MeridianTheme { MeridianBadge(count = 1234) } }
        compose.onNodeWithText("99+").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianControlsTest*'`
Expected: FAIL — the components do not exist.

- [ ] **Step 3: Write the implementations**

Create `component/MeridianSearchField.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Outlined, `md` radius, Surface fill, muted leading search icon. */
@Composable
fun MeridianSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
) {
    val colors = MeridianTheme.colors

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(MeridianRadius.md),
        textStyle = MeridianText.body,
        placeholder = { Text(text = placeholder, style = MeridianText.body, color = colors.inkMuted) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.inkMuted)
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedBorderColor = colors.brand,
            unfocusedBorderColor = colors.stroke,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            cursorColor = colors.brand,
        ),
    )
}

@Preview(name = "Search field light")
@Composable
private fun MeridianSearchFieldLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianSearchField(value = "", onValueChange = {}, placeholder = "Search files")
    }
}

@Preview(name = "Search field dark")
@Composable
private fun MeridianSearchFieldDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianSearchField(value = "holiday", onValueChange = {})
    }
}
```

Create `component/MeridianFilterChip.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Single-select category filter. Pill radius. Never mixed with assist chips in one group. */
@Composable
fun MeridianFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MeridianTheme.colors

    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = MeridianRadius.pill,
        label = { Text(text = label, style = MeridianText.label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.inkMuted,
            selectedContainerColor = colors.brand,
            selectedLabelColor = colors.onBrand,
        ),
        border = BorderStroke(1.dp, if (selected) colors.brand else colors.stroke),
    )
}

@Preview(name = "Filter chip light")
@Composable
private fun MeridianFilterChipLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianFilterChip("Video", selected = true, onClick = {}) }
}

@Preview(name = "Filter chip dark")
@Composable
private fun MeridianFilterChipDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianFilterChip("Video", selected = false, onClick = {}) }
}
```

Create `component/MeridianBadge.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Pill count badge. Hidden at zero — an empty badge is visual noise. */
@Composable
fun MeridianBadge(
    count: Int,
    modifier: Modifier = Modifier,
    critical: Boolean = false,
) {
    if (count <= 0) return

    val colors = MeridianTheme.colors

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(if (critical) colors.critical else colors.brand, MeridianRadius.pill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MeridianText.labelBold,
            color = colors.onBrand,
        )
    }
}

@Preview(name = "Badge light")
@Composable
private fun MeridianBadgeLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianBadge(count = 7) }
}

@Preview(name = "Badge dark")
@Composable
private fun MeridianBadgeDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianBadge(count = 128, critical = true) }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianControlsTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src
git commit -m "feat: add MeridianSearchField, MeridianFilterChip, and MeridianBadge"
```

---

## Task 11: `MeridianHeaderCard`, buttons, and `MeridianStepper`

**Files:**
- Create: `component/MeridianHeaderCard.kt`, `component/MeridianButtons.kt`, `component/MeridianStepper.kt`
- Test: `component/MeridianActionsTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun MeridianHeaderCard(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null)` — the one place Brand fills an area.
  - `@Composable fun MeridianPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, fullWidth: Boolean = false)`
  - `@Composable fun MeridianSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true)`
  - `@Composable fun MeridianStepper(value: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier, min: Int = 1, max: Int = 8)`

- [ ] **Step 1: Write the failing test**

Create `component/MeridianActionsTest.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.meridian.MeridianTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeridianActionsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `header card shows title and subtitle`() {
        compose.setContent {
            MeridianTheme { MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi") }
        }

        compose.onNodeWithText("Pixel 9").assertIsDisplayed()
        compose.onNodeWithText("Connected over Wi-Fi").assertIsDisplayed()
    }

    @Test
    fun `primary button fires`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianPrimaryButton("Send files", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Send files").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `a disabled primary button does not fire`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme {
                MeridianPrimaryButton("Send files", onClick = { clicks++ }, enabled = false)
            }
        }

        compose.onNodeWithText("Send files").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `secondary button fires`() {
        var clicks = 0
        compose.setContent {
            MeridianTheme { MeridianSecondaryButton("Browse PC", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Browse PC").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `stepper increments and decrements within bounds`() {
        var value = 4
        compose.setContent {
            MeridianTheme {
                MeridianStepper(value = value, onValueChange = { value = it }, min = 1, max = 8)
            }
        }

        compose.onNodeWithContentDescription("Increase").performClick()
        assertEquals(5, value)

        compose.onNodeWithContentDescription("Decrease").performClick()
        assertEquals(4, value)
    }

    @Test
    fun `stepper will not exceed its maximum`() {
        var value = 8
        compose.setContent {
            MeridianTheme {
                MeridianStepper(value = value, onValueChange = { value = it }, min = 1, max = 8)
            }
        }

        compose.onNodeWithContentDescription("Increase").performClick()
        assertEquals(8, value)
    }

    @Test
    fun `stepper will not fall below its minimum`() {
        var value = 1
        compose.setContent {
            MeridianTheme {
                MeridianStepper(value = value, onValueChange = { value = it }, min = 1, max = 8)
            }
        }

        compose.onNodeWithContentDescription("Decrease").performClick()
        assertEquals(1, value)
    }

    @Test
    fun `stepper shows its current value`() {
        compose.setContent {
            MeridianTheme { MeridianStepper(value = 4, onValueChange = {}) }
        }
        compose.onNodeWithText("4").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianActionsTest*'`
Expected: FAIL — the components do not exist.

- [ ] **Step 3: Write the implementations**

Create `component/MeridianHeaderCard.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * The one place Brand fills an area. Everything else in Meridian is ink on a calm
 * surface — a second filled panel would spend the system's one bold move twice.
 */
@Composable
fun MeridianHeaderCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.brand, RoundedCornerShape(MeridianRadius.lg))
            .padding(MeridianSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MeridianSpacing.xs),
        ) {
            Text(
                text = title,
                style = MeridianText.screenTitle,
                color = colors.onBrand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MeridianText.label,
                color = colors.onBrandMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        trailing?.invoke()
    }
}

@Preview(name = "Header card light")
@Composable
private fun MeridianHeaderCardLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi · 5 GHz")
    }
}

@Preview(name = "Header card dark")
@Composable
private fun MeridianHeaderCardDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi · 5 GHz")
    }
}
```

Create `component/MeridianButtons.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/** Brand fill, On-brand text, `md` radius. One primary per view. */
@Composable
fun MeridianPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
) {
    val colors = MeridianTheme.colors

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(if (fullWidth) 52.dp else 44.dp),
        shape = RoundedCornerShape(MeridianRadius.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.brand,
            contentColor = colors.onBrand,
            disabledContainerColor = colors.stroke,
            disabledContentColor = colors.inkMuted,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        ButtonContent(label = label, icon = icon)
    }
}

/** Surface fill, 1px stroke, Brand text. For the alternative action. */
@Composable
fun MeridianSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = MeridianTheme.colors

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(MeridianRadius.md),
        border = BorderStroke(1.dp, colors.stroke),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface,
            contentColor = colors.brand,
            disabledContentColor = colors.inkMuted,
        ),
    ) {
        ButtonContent(label = label, icon = icon)
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(MeridianSpacing.sm))
    }

    // Sentence case, always. Meridian never uses ALL CAPS.
    Text(text = label, style = MeridianText.button)
}

@Preview(name = "Buttons light")
@Composable
private fun MeridianButtonsLightPreview() {
    MeridianTheme(darkTheme = false) {
        MeridianPrimaryButton("Send files", onClick = {}, icon = Icons.Filled.Send)
    }
}

@Preview(name = "Buttons dark")
@Composable
private fun MeridianButtonsDarkPreview() {
    MeridianTheme(darkTheme = true) {
        MeridianSecondaryButton("Browse PC", onClick = {})
    }
}
```

Create `component/MeridianStepper.kt`:

```kotlin
package com.slipstream.meridian.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianRadius
import com.slipstream.meridian.MeridianText
import com.slipstream.meridian.MeridianTheme

/**
 * A bounded integer control. Minus is Ink-muted, plus is Brand, value is tabular.
 *
 * Reach for this only for a genuinely bounded integer — in Slipstream, the parallel
 * stream count. It is not the right control for a free-typed decimal.
 */
@Composable
fun MeridianStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 8,
) {
    val colors = MeridianTheme.colors

    Row(
        modifier = modifier
            .border(1.dp, colors.stroke, RoundedCornerShape(MeridianRadius.sm))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = { if (value > min) onValueChange(value - 1) },
            enabled = value > min,
            modifier = Modifier.size(44.dp), // tap target floor
        ) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = "Decrease",
                tint = if (value > min) colors.inkMuted else colors.stroke,
            )
        }

        Text(
            text = value.toString(),
            style = MeridianText.itemTitle,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp),
        )

        IconButton(
            onClick = { if (value < max) onValueChange(value + 1) },
            enabled = value < max,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Increase",
                tint = if (value < max) colors.brand else colors.stroke,
            )
        }
    }
}

@Preview(name = "Stepper light")
@Composable
private fun MeridianStepperLightPreview() {
    MeridianTheme(darkTheme = false) { MeridianStepper(value = 4, onValueChange = {}) }
}

@Preview(name = "Stepper dark")
@Composable
private fun MeridianStepperDarkPreview() {
    MeridianTheme(darkTheme = true) { MeridianStepper(value = 8, onValueChange = {}) }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :meridian-compose:testDebugUnitTest --tests '*MeridianActionsTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add android/meridian-compose/src
git commit -m "feat: add MeridianHeaderCard, buttons, and MeridianStepper"
```

---

## Task 12: Gallery, screenshot baselines, and the written guides

**Files:**
- Create: `src/debug/kotlin/com/slipstream/meridian/gallery/MeridianGallery.kt`
- Create: `src/screenshotTest/kotlin/com/slipstream/meridian/GalleryScreenshots.kt`
- Create: `android/docs/design-guide.md`, `android/docs/design-playbook.md`

**Interfaces:**
- Produces: `@Composable fun MeridianGallery()` rendering every token and component against the live theme; screenshot baselines for light and dark.

- [ ] **Step 1: Write the gallery**

Create `src/debug/kotlin/com/slipstream/meridian/gallery/MeridianGallery.kt`:

```kotlin
package com.slipstream.meridian.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.slipstream.meridian.MeridianSpacing
import com.slipstream.meridian.MeridianTheme
import com.slipstream.meridian.component.*

/**
 * Every token and component against the live theme. Use this for a fast visual check
 * of a token change before wiring it into a real screen — and as the source of the
 * screenshot baselines that make an accidental change visible in review.
 */
@Composable
fun MeridianGallery() {
    val colors = MeridianTheme.colors

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var streams by remember { mutableIntStateOf(4) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(MeridianSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(MeridianSpacing.section),
    ) {
        MeridianSectionHeader("Colour roles")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            listOf(
                colors.brand, colors.brandStrong, colors.positive,
                colors.warning, colors.critical, colors.tint, colors.stroke,
            ).forEach { Swatch(it) }
        }

        MeridianSectionHeader("Hero metric")
        MeridianHeroMetric(value = "48.2", unit = "MB/s", label = "Transfer rate")

        MeridianSectionHeader("Header card")
        MeridianHeaderCard(title = "Pixel 9", subtitle = "Connected over Wi-Fi · 5 GHz")

        MeridianSectionHeader("Status pills")
        Column(verticalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            MeridianStatusPill(MeridianStatus.Positive, "Connected", icon = Icons.Filled.CheckCircle)
            MeridianStatusPill(MeridianStatus.Info, "Transferring")
            MeridianStatusPill(MeridianStatus.Warning, "2.4 GHz — slower link", icon = Icons.Filled.Wifi)
            MeridianStatusPill(MeridianStatus.Critical, "Transfer failed")
            MeridianStatusPill(MeridianStatus.Neutral, "Idle")
        }

        MeridianSectionHeader("Icon tiles and stats")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md)) {
            MeridianIconTile(Icons.Filled.Send, "Send files")
            MeridianIconTile(Icons.Filled.Folder, "Browse")
            MeridianIconTile(Icons.Filled.Movie, "Stream")
        }
        MeridianStat(Icons.Filled.Download, value = "8", caption = "Queued")

        MeridianSectionHeader("List rows", actionLabel = "See all", onActionClick = {})
        MeridianListRow(
            title = "holiday-2026.mkv",
            meta = "24 Aug 2026",
            trailingValue = "4.2 GB",
            status = MeridianStatus.Info to "Transferring",
            leading = { MeridianIconTile(Icons.Filled.Movie, "Video", size = 40.dp) },
            onClick = {},
        )
        MeridianListRow(
            title = "backup.zip",
            meta = "22 Aug 2026",
            trailingValue = "1.1 GB",
            status = MeridianStatus.Positive to "Complete",
        )

        MeridianSectionHeader("Controls")
        MeridianSearchField(value = search, onValueChange = { search = it }, placeholder = "Search files")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            listOf("All", "Video", "Audio", "Images").forEach {
                MeridianFilterChip(it, selected = filter == it, onClick = { filter = it })
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.md),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            MeridianStepper(value = streams, onValueChange = { streams = it })
            MeridianBadge(count = 7)
            MeridianBadge(count = 128, critical = true)
        }

        MeridianSectionHeader("Buttons")
        Row(horizontalArrangement = Arrangement.spacedBy(MeridianSpacing.sm)) {
            MeridianPrimaryButton("Send files", onClick = {}, icon = Icons.Filled.Send)
            MeridianSecondaryButton("Browse PC", onClick = {})
        }
        MeridianPrimaryButton("Start transfer", onClick = {}, fullWidth = true)

        MeridianSectionHeader("States")
        MeridianCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(MeridianSpacing.lg).size(width = 300.dp, height = 140.dp)) {
                MeridianStateView(
                    MeridianUiState.Empty("Nothing transferred yet. Pick a file to send.", "Send a file") {},
                ) {}
            }
        }
        MeridianCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(MeridianSpacing.lg).size(width = 300.dp, height = 140.dp)) {
                MeridianStateView(
                    MeridianUiState.Error("Phone not on this network. Searching…", onRetry = {}),
                ) {}
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Column(
        modifier = Modifier
            .size(40.dp)
            .background(color, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
    ) {}
}
```

- [ ] **Step 2: Add screenshot tests**

Create `src/screenshotTest/kotlin/com/slipstream/meridian/GalleryScreenshots.kt`:

```kotlin
package com.slipstream.meridian

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.slipstream.meridian.gallery.MeridianGallery

/**
 * Baselines for the whole system in both modes. A token change that alters any
 * component becomes a reviewable image diff rather than something noticed on a
 * device three weeks later.
 */
@Preview(name = "Gallery light", showBackground = true, heightDp = 2400, widthDp = 400)
@Composable
fun GalleryLightScreenshot() {
    MeridianTheme(darkTheme = false) { MeridianGallery() }
}

@Preview(name = "Gallery dark", showBackground = true, heightDp = 2400, widthDp = 400)
@Composable
fun GalleryDarkScreenshot() {
    MeridianTheme(darkTheme = true) { MeridianGallery() }
}
```

Move the gallery from `src/debug` to `src/main` so the screenshot source set can reach it, and guard it with `@RestrictTo`-style documentation rather than a source-set boundary — the alternative is duplicating it.

- [ ] **Step 3: Record the baselines**

```bash
cd android
./gradlew :meridian-compose:updateDebugScreenshotTest
```

Open the generated PNGs under `meridian-compose/src/debug/screenshotTest/reference/` and check them by eye against the design rules: calm canvas, white cards with hairlines, no shadows, exactly one blue, status colours only on small marks, numbers aligned. **Do not accept a baseline you have not looked at** — a baseline records whatever it is given, including a mistake.

- [ ] **Step 4: Verify the baselines hold**

Run: `cd android && ./gradlew :meridian-compose:validateDebugScreenshotTest`
Expected: PASS, no diffs.

- [ ] **Step 5: Write the guides**

Create `android/docs/design-guide.md` — the reference for what exists: the token table with every hex and its role, the type scale, the radius and spacing steps, the component catalogue with each component's signature and when to reach for it, and the M3 role mapping table from Task 3.

Create `android/docs/design-playbook.md` — the procedure: how to build a new screen (copy the nearest existing one, never a blank file), the three-state rule using `MeridianStateView`, when to reach for each component and when not to force one, and the **four traps**, each with its failure mode written out:

1. **Unmapped M3 role** → Material baseline lavender, silently, on one stock control. Map every role in both modes in the same edit. Caught by `MeridianThemeTest`.
2. **`Surface` tonal elevation** → cards drift off-token with no crash, no warning, and an IDE preview that looks fine. Pin both elevations to `0.dp`; structure comes from the 1px border.
3. **Missing `tnum`** → a live rate readout jitters as digit widths change. Every numeric style declares it. Caught by `MeridianTypographyTest`.
4. **`isSystemInDarkTheme()` outside `MeridianTheme`** → two screens disagree about the mode. Caught by `check-meridian-tokens.sh`.

- [ ] **Step 6: Run everything**

```bash
bash android/scripts/check-meridian-tokens.sh
cd android && ./gradlew :meridian-compose:testDebugUnitTest :meridian-compose:validateDebugScreenshotTest
```

Expected: gate passes, all unit tests pass, no screenshot diffs.

- [ ] **Step 7: Commit**

```bash
git add android
git commit -m "feat: add Meridian gallery, screenshot baselines, and design guides"
```

---

## Self-Review

**Spec coverage.**

| Spec requirement | Task |
|---|---|
| §13 single token source, colour-literal gate | 2 |
| §13 `LocalMeridianColors` role set | 3 |
| §13 fully mapped M3 `ColorScheme`, both modes | 3 |
| §13 trap 1 — `Surface` tonal elevation | 5, 12 |
| §13 trap 2 — `tnum` tabular figures | 4, 12 |
| §13 trap 3 — one `isSystemInDarkTheme()` call site | 3, 2 (gate), 12 |
| §13 shapes sm/md/lg/pill, 4pt spacing | 4 |
| §13 component list | 5–11 |
| §13 gallery plus per-component previews | 5–12 |
| §12 status roles map to connection state | 6 |
| §12 colour never the only cue | 6 (enforced in the API) |
| §12 hero metric is the live rate | 8 |
| §12 elevation ≤1dp, strokes not shadows | 5, 2 (gate) |
| §12 sentence case, never ALL CAPS | 11, 2 (gate) |
| §12 dark mode on Android | 2, 3 |
| Quality floor — 4.5:1 contrast | 2 |
| Quality floor — 44dp tap targets | 11 |
| Light/dark composition without throwing | 5–11 (every component test) |

**Out of scope by design:** the icon set (Material Icons Extended covers Slipstream's needs; a bespoke set is not warranted), motion (Meridian allows only press/expand/refresh feedback, all of which is Material default behaviour), and every screen — screens belong to the Android UI plan, which consumes this module.

**Placeholder scan.** No `TBD`, `TODO`, or "similar to Task N". Every step carries complete code. Task 3 explicitly defers its commit to Task 4 because the four theme files are one compilation unit — that is a stated sequencing decision, not an omission.

**Type consistency.** `MeridianTheme` is both a `@Composable` function and an `object` with accessors — the standard Compose pattern, and both are declared in `MeridianTheme.kt` (Task 3). `MeridianColors` field names match between the data class (Task 3), the token objects (Task 2), and every component's `MeridianTheme.colors.x` call site. `MeridianStatus` has exactly five entries (Task 6), asserted by a test, and `MeridianListRow` consumes it as `Pair<MeridianStatus, String>` (Task 7). `MeridianSpacing` members (`xs`/`sm`/`md`/`lg`/`xl`/`xxl`/`screen`/`cardInner`/`section`/`touchTarget`) are declared once in Task 4 and used unchanged in Tasks 5–12. `MeridianText` style names are fixed in Task 4 and referenced identically thereafter.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-25-meridian-compose.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, with review between tasks and fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batching with checkpoints for review.

Which approach?
