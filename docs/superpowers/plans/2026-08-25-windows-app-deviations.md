# Deviations from Plan 5 (Windows app)

**Date:** 2026-08-26
**Plan:** [`2026-08-25-windows-app.md`](2026-08-25-windows-app.md)
**Executed via:** subagent-driven development, 16 tasks + one final-review fix wave.

Everything below is a place where the delivered code differs from the plan's verbatim text, or
where the plan's own scope left a gap that surfaced during implementation. Each entry states what
changed, why, and whether it's closed or carried forward.

## Fixed during the final whole-branch review

The per-task review process verified each task against its own brief but never verified the
*assembled* application actually ran end-to-end. The final review caught this. One fix wave
(commit `13a657d`) addressed all of it; a scoped re-review confirmed 14 of 14 findings addressed,
with two residual gaps in the fix itself — see "Carried forward" below.

### App.xaml.cs never constructed the real `PeerHost` (Critical)

Task 8 built `PeerHost` and every later task assumed it was live, but no task's brief ever said
"wire `PeerHost` into `App.xaml.cs`" — the app shipped with `_peerHost = new NoOpPeerHost()` and a
`// TODO(Task 8)` comment through Tasks 9–16. Nothing in the running app ever touched the network.
**Fixed:** `App.xaml.cs` now loads `SettingsStore` first, constructs a real `SlipstreamPeer` with
the persisted `DownloadDirectory`/`StreamCount`, wraps it in `PeerHost`, and calls `StartAsync`.
`NoOpPeerHost` is deleted; tests use `FakePeerHost`. This is a plan defect, not an implementer
error — no task in the plan was assigned this wiring step.

### The Transfers page was still a placeholder (Critical)

`ShellWindow.xaml`'s Transfers destination rendered a literal `"Transfers — placeholder, replaced
by a later task."` TextBlock, even though Task 12 had already built and constructed the real
`TransfersPage` — it just wasn't plugged into the destination template the way Device/Browse/
History/Settings were. **Fixed:** same `ContentPresenter` pattern as the other four destinations.

### CI silently ran zero App tests (Critical)

`dotnet test windows/Slipstream.sln` without `-p:Platform=x64` fails to load
`Slipstream.App.Tests.dll` (wrong architecture output path) but exits 0 — every task from 7 onward
reported real local test counts, but CI itself never ran them. **Fixed:** CI now passes
`-p:Platform=x64`, uses `--logger trx`, and fails the job if the parsed test count is implausibly
low (a "0 tests ran but exit 0" guard).

### No UI path started a transfer (Critical)

`BrowsePage`'s double-tap only navigated into folders; nothing called `PullAsync`, `StreamAsync`,
or `SendClipboardAsync` on a selected file, so — even with the two fixes above — a user still
couldn't move a file. **Fixed:** `BrowsePage` now shares the app's `TransferQueue` instance and has
explicit Download/Stream toolbar actions.

### `StreamAsync` bypassed `PlaylistLauncher`, opening the browser instead of the media player (Important)

Spec §8 calls this out explicitly as "a real and easily-missed failure." The phone→PC `play` path
(`SlipstreamSession`) used `PlaylistLauncher` correctly; the PC-initiated `StreamAsync` used bare
`Process.Start(ShellExecute)` instead. **Fixed:** routed through the same `PlaylistLauncher`.

### Nav pill used `{StaticResource}` for a Meridian brush (Important)

A fix from Task 7's own review round (the original `Color="{ThemeResource ...}"` type-mismatch
fix) replaced the bug with a different violation of the same non-negotiable rule: re-keying via
`<StaticResource ResourceKey="MeridianBrandBrush"/>` inside a plain `ListView.Resources`
dictionary resolves once at parse time and doesn't follow a theme switch. **Fixed:** moved into
`ListView.Resources`' own `ThemeDictionaries` block, both Light and Dark.

### Nav items under the 44px tap-target floor (Important)

`MinHeight="0"` explicitly overrode the default. **Fixed:** `MinHeight="44"`.

### Settings changes were inert with no indication (Important)

`StreamCount`/`DownloadDirectory` persisted but nothing read them back — resolved by the
`PeerHost` wiring fix above, since `SlipstreamPeer.StreamCount`/`DownloadDirectory` are
`init`-only and genuinely can't change live. **Fixed:** Settings now also shows "Takes effect the
next time Slipstream starts."

### Non-transient protocol errors retried for 20 seconds (Important)

`PeerHost`'s pull/resume retry loops caught every `Exception`, including protocol-level refusals
that aren't transient. **Fixed:** narrowed to retry only connectivity-class failures
(`ControlConnectionLostException`), letting protocol refusals surface immediately.

### Unsanitized peer-supplied filename (Important)

`Path.Combine(_downloadDirectory, response.Name)` used the wire-supplied name directly — a
path-traversal risk in principle, even under the paired-only trust model. **Fixed:**
`Path.GetFileName(response.Name)` before combining.

### `ReconnectAsync` could swallow a network-change-triggered reconnect (Important)

A caller-driven reconnect completing against the *old* network could be mistaken for success by a
concurrent network-change-triggered reconnect. **Fixed (partially):** a generation counter closes
the common case. See "Carried forward" below for the residual window the re-review found in this
fix itself.

### Two of three Device page stat cards were permanently blank (Important)

`LinkRateText` and `TransferredTodayText` both unconditionally showed "—". `TransferredTodayText`
is computable from `HistoryStore` (available since Task 13) and was never wired. **Fixed:** sums
today's completed transfers. `LinkRateText` has no live data source in this codebase and honestly
remains "—" — see "Scope gaps carried forward" below.

### Thumbnails were never implemented (Important)

`IPeerHost` had no thumbnail-resolution method despite Core already serving thumbnails via token
URLs (spec §9); `BrowsePage`'s gallery view rendered empty placeholders. **Fixed:**
`IPeerHost.GetThumbnailUrl` added, wired to the real media-server thumbnail endpoint.

### No view model unsubscribed from long-lived events (Important)

Five view models subscribed to `StateChanged`/`ItemUpdated` with no `IDisposable`. Harmless while
every page is a singleton constructed once alongside `ShellWindow`, but an invisible coupling.
**Fixed:** all five implement `IDisposable` and unsubscribe correctly, though no call site disposes
them yet (still singletons for the lifetime of the app) — disclosed as mechanism-only-by-design,
not a live bug today.

## Carried forward — residual gaps found in the final fix wave itself, not fixed (per the "no second fix wave" rule)

### `PeerHost.StartAsync` is fire-and-forget with no retry on first launch

`App.xaml.cs` calls `_ = peerHost.StartAsync(CancellationToken.None)`. `StartAsync` is a *single*
connect attempt, not a loop — on the common first-run/peer-not-yet-present path it throws
`InvalidOperationException("Could not find the paired peer on this network.")`, which is silently
swallowed as an unobserved task fault. The app is then pinned at `Lost` with no retry until an OS
`NetworkAddressChanged` event happens to fire `NetworkChanged`. This undermines the spec's stated
#1 goal: "discovery and reconnection in ~1s on either network, with no user action." The
re-reviewer's suggested fix is small (wrap in a retry loop, or at minimum observe the task's
exception and drive it into the existing reconnect path) but was not applied here, per the
process's "no second fix wave" rule — adjudicated and parked rather than silently shipped.
**Recommendation before this ships to real users:** apply the fix above; it's localized to
`App.xaml.cs`/`PeerHost.StartAsync`.

### `ReconnectAsync`'s generation-counter fix narrows but doesn't fully close the race

The generation is captured at connect-*attempt* time rather than connect-*success* time. A stale
caller-driven attempt (captured generation N) can still complete and set `Connected` after a
network-change-triggered attempt (captured generation N+1) has already been dispatched, and the
N+1 attempt's fast-path check (`if State == Connected return true`) can then return a false
success against the *old* network's connection. The re-reviewer's analysis: this self-heals in
most cases (the stale attempt's own loop redoes the connect), but if its caller token is cancelled
at the wrong moment, nobody re-discovers on the new network. The clean fix — stamp
`_connectedGeneration` inside `ConnectAsync` on success, compare *that* instead of the
attempt-time generation — is small and well-specified but not applied here, for the same
"no second fix wave" reason. **Recommendation before this ships:** apply the one-line stamp fix;
it's localized to `PeerHost.cs`'s reconnect logic.

## Ruled during task review (kept as implemented, not fixed)

### Task 7/8 declaration order — `IPeerHost`/`PeerConnectionState` declared in Task 7, not Task 8

The plan's text has Task 8 (PeerHost) define `IPeerHost`, but Task 7 (Shell)'s own test code
already references `IPeerHost`, `FakePeerHost`, and `PeerConnectionState`, which are Task 8
deliverables — a forward-reference the plan doesn't resolve. Ruled during pre-flight scan: Task 7
declares the interface/enum/fake; Task 8 implements `PeerHost : IPeerHost` against it. Both
tasks' tests run exactly as specified in the plan text; only the declaration site moved.

### `Microsoft.WindowsAppSDK` pinned to `1.8.260317003`, not the plan's placeholder `2.4.0`

Task 2's brief inherited a `2.4.0` version reference that isn't a real WinAppSDK release train
(the real numbering is `1.x.yyMMddNNN`-style). Caused an `NU1605` package-downgrade conflict
against `Slipstream.App`'s template-default `1.8.260317003`. Pinned both projects to
`1.8.260317003`. `Slipstream.Meridian` only ships XAML resources — no functional dependency on a
newer WinAppSDK API — so this carries no risk.

### `MeridianStepper` doesn't exist — Task 14's Settings page uses a stock `NumberBox` instead

The plan's §13 component list mentions `MeridianStepper` for the Android (Compose) side; no task
in this Windows plan actually built a WinUI equivalent. Task 14 substituted a stock `NumberBox`
(`SpinButtonPlacementMode="Inline"`, Min/Max 1–8), with the `[1,8]` clamp still enforced in
`SettingsViewModel` regardless of what the control itself permits. The design gate can verify no
literal-color/shadow/ALL-CAPS violations in `NumberBox`'s usage, but it cannot statically verify
that `NumberBox`'s own default Fluent template correctly resolves Meridian's system-brush
overrides at runtime — this needs a visual check on real hardware (see the Step 5 table below,
row "not verified").

### Task 12 surfaced the `ShellViewModel`/`DeviceViewModel.OnPeerStateChanged` cross-thread gap, deferred to Task 16 by design

Fixing `DeviceViewModel.RefreshHeroRate`'s cross-thread bug (Task 12) revealed the same class of
bug pre-existed in `ShellViewModel.OnPeerStateChanged` and `DeviceViewModel.OnPeerStateChanged`
since Tasks 7/9 — both mutated UI-bound properties directly from `IPeerHost.StateChanged` with no
dispatcher marshaling. Ruled to defer to Task 16 (network-change handling) rather than fix
out-of-scope in Task 12, since Task 16 was expected to exercise `StateChanged` far more heavily
via reconnect/backoff cycles, multiplying the crash risk. Task 16 applied the fix as planned.

### `AutostartService`/tray/single-instance implementation choices

- Autostart: `schtasks.exe` via `Process` (`/SC ONLOGON /RL LIMITED`, no elevation) rather than a
  NuGet Task Scheduler wrapper — no new dependency, matches the plan's "not a Run key" requirement.
- Tray icon: raw Win32 `Shell_NotifyIcon`/`TrackPopupMenu` P/Invoke rather than the community
  `H.NotifyIcon.WinUI` package — WinUI 3 has no first-class tray API either way; a 3-item popup
  menu didn't need XAML-hosted tray content, so the lower-dependency option was chosen.
- `PauseDiscovery`/`ResumeDiscovery` were added to `IPeerHost`/`PeerHost` as a genuine capability
  (skips `FindPeerAsync` while paused), not a stub — the plan's tray-menu spec ("Pause discovery")
  needed something real to wire to and nothing existed yet.

## Scope gaps carried forward, not closed in this branch

### `IPeerHost` has no live per-transfer rate signal for the Device page's hero metric

`DeviceViewModel.LinkRateText` is honestly a resting "—" placeholder. Wiring a genuine live rate
would need either a Core-level "current instantaneous throughput" signal or restructuring
`TransferQueue`'s aggregate-rate computation to feed `DeviceViewModel` continuously (today it only
feeds `HeroRateText` while at least one transfer is active, which the final fix wave did wire —
`LinkRateText` specifically, meant to represent link/connection quality independent of an active
transfer, still has no data source). Not fabricated; left as a known gap.

### Non-pixel `MeridianDataGrid` columns fall back to a fixed 140px width

`GridLength`-based proportional/auto column sizing isn't supported since the header/row
composition is StackPanel-based rather than a shared Grid — flagged in Task 6's report as a
documented limitation, not fixed in any later task since no page actually needed non-pixel
columns.

### `MeridianDataGrid`'s reflection-based cell binding has no diagnostics for a typo'd path

`MeridianDataGridColumn.Binding` resolves dotted property paths via reflection at render time; a
misspelled path silently renders an empty cell rather than throwing or logging. The final review
flagged this as the one deferred-minor the per-task ledger under-weighted, since it's now the only
cell-rendering path in the shipped app (every page uses `MeridianDataGrid`). Not fixed in this
branch; worth a small diagnostic/assert pass as a fast follow.

## Step 5 — manual hardware verification (per the plan's own instruction, and explicit user instruction to report plainly)

**This sandbox has no real Android phone and no way to pair one.** None of the rows below could be
executed. Reporting this plainly rather than implying any of them passed:

| Check | Result |
|---|---|
| Cold start with the phone on the same WiFi | **NOT VERIFIED** — no phone available |
| Pair a factory-reset peer | **NOT VERIFIED** — no phone available |
| Browse the phone, scroll a photo folder | **NOT VERIFIED** — no phone available |
| Pull a 1 GB file | **NOT VERIFIED** — no phone available (a synthetic ~80MB loopback transfer was exercised in automated tests, not a real 1GB cross-device transfer) |
| Toggle WiFi off/on mid-transfer | **NOT VERIFIED** on real hardware — automated tests exercise an analogous scenario via `TwoPeers.BreakAllConnectionsAsync()` + `RaiseNetworkChanged()` on loopback, which is a reasonable proxy but not the same as a real Windows `NetworkAddressChanged` event against a real WiFi adapter |
| Stream a 4 GB MKV and seek to the end | **NOT VERIFIED** — no phone/media file available |
| Switch Windows to dark mode with the app open | **NOT VERIFIED visually** — the design gate and `TokenDictionaryTests` mechanically verify every brush is defined in both Light and Dark, and the final review confirmed the nav-pill fix uses genuine `ThemeDictionaries` (not a one-time-resolved `StaticResource`), but no live app was ever run on a real desktop session in this sandbox to visually confirm the switch |
| Close the window | **NOT VERIFIED interactively** — code-reviewed as correct (the `AppWindow.Closing` handler cancels the close and hides instead), but never exercised in a live desktop session |
| Reboot with autostart on | **NOT VERIFIED** — `AutostartServiceTests` (the closest automated proxy) fail in this sandbox with a confirmed environment restriction (`schtasks` "Access is denied" on `ONLOGON` triggers, independently reproduced by the controller running the same command directly), so even the automated proxy for this check could not run here |

**Recommendation:** before shipping to the actual paired phone, re-run `AutostartServiceTests` on
real hardware/an unrestricted environment (2 tests currently fail only here), and walk this full
table manually against the real Android app.

## Post-plan — the app had never actually been launched (branch `fix-app-launch`)

The plan shipped with 120 green tests and an executable that died during `Window.Activate()`
without ever showing a window. Every test was a view-model or source-shape test; nothing
started the process, so nothing could notice. Two stacked defects in the shell:

1. `ShellWindow` stashed each destination view in `RootGrid.Resources` and pulled it back with
   `{StaticResource}` from a `DataTemplate` handed out by a `DataTemplateSelector`. Those
   templates have no parent chain when inflated, so the lookup never reached `RootGrid` and
   threw *Cannot find a Resource with the Name/Key DevicePageContent*.
2. Moving the entries to `Application.Current.Resources` made the lookup succeed and exposed
   the real defect: WinUI flags every `ResourceDictionary` value as shareable, so assigning one
   to `ContentControl.Content` throws `ArgumentException` / `E_INVALIDARG`. The pattern cannot
   work at all. The views were also `Page`, which a `ContentControl` cannot host either.

Fixed by building the five views in the code-behind and assigning them to `PageHost.Content`
directly, and by making them `UserControl`s. `DestinationTemplateSelector` is gone.

The reported `REGDB_E_CLASSNOTREG` at `Application.Start` did **not** reproduce on this branch:
the self-contained/`WindowsPackageType=None` configuration already in `Slipstream.App.csproj` is
correct, the Windows App SDK auto-initializer *is* generated (`WindowsAppSdkAutoInitialize=true`
via `WindowsAppSdkUndockedRegFreeWinRTInitialize`), and the app loads
`Microsoft.UI.Xaml.dll` from its own output folder. No build configuration was changed.

### Startup crash logging

An unpackaged `WinExe` that throws during XAML load leaves nothing but a generic `0xc000027b`
in the event log — no console, no window, no managed stack. `App` now writes any unhandled
exception to `%LOCALAPPDATA%\Slipstream\startup-error.log`.

### `AppLaunchSmokeTest` and CI

`Slipstream.App.Tests.AppLaunchSmokeTest` starts the built executable, waits for a top-level
window titled "Slipstream", and kills it. It is **not** environment-gated, by design.

**Unverified on CI.** It has only been run on a real interactive Windows desktop session, where
it passes (and, with a deliberately-thrown constructor, fails with the exact stack trace). The
`windows-core` workflow runs on GitHub-hosted `windows-latest`; whether an unpackaged WinUI 3
app can create a top-level window in that session was not tested here. If it turns out it
cannot, the correct response is to move this test behind a self-hosted/interactive runner —
**not** to gate it on an environment variable, which would restore exactly the blind spot it
exists to close. Until CI has been observed running it, treat a green CI as *not* evidence that
the app launches, and run it locally before shipping.

### Still not verified against real hardware

With the phone's foreground service reachable (`10.199.176.137:53321` accepts TCP), the app
starts, renders the Device page and reports its connection state — *Connection lost* / *Not yet
connected*, walking its S1–S4 discovery ladder — rather than throwing. It does not reach a
connected state, which is expected for an unpaired peer. Pairing still needs the user, so every
row of the Step 5 table above remains **NOT VERIFIED**.
