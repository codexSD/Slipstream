# Plan 4 — Slipstream for Android

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The complete Android application — every screen on `:meridian-compose` over `:core`, the pairing UI, share-sheet and file-picker entry points, and the real-hardware verification that Plan 3 could not run.

**Architecture:** A `:app` module holding a `PeerController` (the Kotlin mirror of Plan 5's `PeerHost`) that owns the `SlipstreamPeer` lifetime and exposes `StateFlow`s. Composables bind to view models; **no composable and no view model touches a socket.** The existing foreground service hosts the controller.

**Tech Stack:** Kotlin 2.0, Compose, `:meridian-compose`, `:core`, coroutines/Flow, Media3 for local playback, JUnit4 + Robolectric + `compose-ui-test`.

**Spec:** [`2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §12 (Android screens), §8 (push-to-play), §10 (clipboard), §14 (background), §15 (error voice), §16 (throughput).

---

## Preconditions — verified on `main`

Plans 1, 1b, 2, 2b, 3, 4a, and 5 are merged. Before Task 2, read and confirm against the real code:

- `android/core/src/main/kotlin/com/slipstream/core/SlipstreamPeer.kt` — the actual public surface, including whether progress is exposed as `Flow` or a callback. **The plan below assumes `Flow`; if `:core` shipped a callback, reconcile here and adjust Tasks 2, 7, and 11 rather than patching task by task.**
- `docs/superpowers/plans/2026-08-25-android-core-deviations.md` — in particular the open items: the STA+AP tie-break, the weakened `onNetworkChanged` readiness gate, and that `ThumbnailProvider`/`FolderExpander` have no production caller. **Task 6 gives `ThumbnailProvider` its caller; Task 10 gives `FolderExpander` its caller.** Wiring them is part of this plan, not a later cleanup.
- `windows/src/Slipstream.App/Services/PeerHost.cs` — Task 2 mirrors its shape deliberately, so the two apps stay one design.

## Global Constraints

- **Module:** `:app`, namespace `com.slipstream.app`, `minSdk 26`, depends on `:core` and `:meridian-compose` only.
- **Every colour, type style, radius, and spacing comes from `:meridian-compose`.** No `Color(0x…)` and no hardcoded `.dp` typography anywhere in `:app` — the existing `check-meridian-tokens.sh` gate is extended to cover `:app` in Task 1.
- **`MeridianTheme` wraps the whole app once.** `isSystemInDarkTheme()` is read only inside it.
- **No composable or view model opens a socket.** All `:core` access goes through `PeerController`.
- **No blocking work on the main thread.** `:core` calls run on `Dispatchers.IO`; state reaches Compose as `StateFlow`.
- **Progress collection is lifecycle-aware** — `repeatOnLifecycle(STARTED)`, so a backgrounded screen stops recomposing.
- **Status colour is never the only cue** — `MeridianStatusPill` already requires a label; keep it that way.
- **All numbers use the tabular styles.** Rates, sizes, ETAs, percentages.
- **Sentence case. Never ALL CAPS.** English only, LTR.
- **Tap targets ≥ 44dp.**
- **Error voice (spec §15):** direct, no apology, name the next step.
- **LAN-only:** `:app` makes no outbound call of any kind. No analytics, no crash reporting, no remote images or fonts, no update check.
- **Never disable, skip, or `@Ignore` a test to get a green build.**

---

## File Structure

```
android/app/src/main/kotlin/com/slipstream/app/
  SlipstreamApplication.kt
  MainActivity.kt
  service/SlipstreamService.kt          # exists from Plan 3 — extended, not replaced
  service/BootReceiver.kt               # exists
  peer/PeerController.kt                # mirror of Windows PeerHost
  peer/PeerConnectionState.kt
  peer/TransferQueue.kt
  peer/HistoryStore.kt
  peer/SettingsStore.kt
  ui/SlipstreamNavHost.kt
  ui/home/HomeScreen.kt        + HomeViewModel.kt
  ui/pairing/PairingScreen.kt  + PairingViewModel.kt
  ui/browse/BrowseScreen.kt    + BrowseViewModel.kt
  ui/transfers/TransfersScreen.kt + TransfersViewModel.kt
  ui/history/HistoryScreen.kt  + HistoryViewModel.kt
  ui/settings/SettingsScreen.kt + SettingsViewModel.kt
  ui/send/SendSheet.kt         + SendViewModel.kt
  permissions/PermissionGate.kt
android/app/src/test/kotlin/…            # view models + stores, Robolectric
```

---

## Task 1: `:app` module wiring and the extended design gate

**Files:** `android/app/build.gradle.kts`, `settings.gradle.kts`, `android/scripts/check-meridian-tokens.sh`, CI

- [ ] **Step 1: Wire the module**

Add `:meridian-compose` and `:core` as dependencies, plus `androidx.activity:activity-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.navigation:navigation-compose`, `androidx.media3:media3-exoplayer` (Task 11), and test deps `robolectric`, `compose-ui-test-junit4`, `kotlinx-coroutines-test`, `turbine`.

- [ ] **Step 2: Extend the token gate to `:app`**

The existing script scans `android/meridian-compose/src`. Add `android/app/src` to its scan roots, with one difference: **`:app` may not declare colours at all**, not even in a tokens file.

```bash
# :app consumes the design system; it never defines it.
appColours=$(grep -rn --include="*.kt" -E "Color\(0x[0-9A-Fa-f]{6,8}\)" "$APP_SRC" || true)
[ -n "$appColours" ] && { echo "FAIL: :app must take colours from MeridianTheme:"; echo "$appColours"; status=1; }
```

- [ ] **Step 3: Verify the gate catches a violation**

Add `val bad = Color(0xFF00FF00)` to a scratch file in `:app`, run the gate, confirm it fails, delete it. A gate never seen failing is a gate nobody knows is broken.

- [ ] **Step 4: Build, run gate, add to CI, commit**

```bash
cd android && ./gradlew :app:assembleDebug
bash android/scripts/check-meridian-tokens.sh
git add android .github && git commit -m "chore: wire the :app module and extend the design gate to it"
```

---

## Task 2: `PeerController`

The single owner of `:core`. Mirrors `Slipstream.App/Services/PeerHost.cs` — read it first.

**Files:** `peer/PeerConnectionState.kt`, `peer/PeerController.kt`, tests

**Produces:**

```kotlin
enum class PeerConnectionState { Idle, Searching, Connected, Degraded, Lost }

data class PeerStatus(
    val state: PeerConnectionState,
    val peerName: String? = null,
    val band: String? = null,
    val strategy: String? = null,
)

interface PeerController {
    val status: StateFlow<PeerStatus>
    suspend fun start()
    suspend fun reconnect(): Boolean
    suspend fun list(path: String): Result<ListResult>
    fun pull(remotePath: String): Flow<TransferProgress>
    suspend fun push(localPath: String, remoteName: String): Flow<TransferProgress>
    suspend fun streamOnPeer(remotePath: String): Result<Unit>   // push-to-play
    suspend fun streamUrlFor(localPath: String): Result<String>  // for local playback
    suspend fun sendClipboard(text: String): Result<Unit>
    val clipboardReceived: SharedFlow<String>
    suspend fun openPairing(): Flow<PairingProgress>
    suspend fun confirmPairing(accept: Boolean)
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `reaches Connected and lists the peer's files`() = runTest {
    val rig = TwoPeers.start(tempDir)          // reuse :core's test rig
    val controller = RealPeerController(rig.local, dispatcher = testDispatcher)

    controller.start()

    assertEquals(PeerConnectionState.Connected, controller.status.value.state)
    assertTrue(controller.list(rig.sharedDir).getOrThrow().entries.isNotEmpty())
}

@Test fun `reports Lost then recovers on reconnect`() = runTest {
    // spec §5: a network switch is routine, never an error state.
    val controller = RealPeerController(rig.local, testDispatcher)
    controller.start()

    controller.status.test {
        assertEquals(PeerConnectionState.Connected, awaitItem().state)
        rig.breakControlConnection()
        assertEquals(PeerConnectionState.Lost, awaitItem().state)
        assertTrue(controller.reconnect())
        assertEquals(PeerConnectionState.Connected, awaitItem().state)
    }
}

@Test fun `a failed list surfaces a message, not an exception`() = runTest {
    // spec §15: direct, no apology, name the next step.
    val result = controller.list("/nope")
    assertEquals("That folder is no longer there.", result.exceptionOrNull()?.message)
}

@Test fun `serialises control-channel access`() = runTest {
    // The control channel is one duplex stream; concurrent request/response pairs
    // would interleave replies and mismatch ids.
    val results = (1..8).map { async { controller.list(rig.sharedDir) } }.awaitAll()
    assertTrue(results.all { it.isSuccess })
}
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Implement**

One `Mutex` guards the control channel for the same reason `PeerHost` uses a `SemaphoreSlim`. `status` is a `MutableStateFlow` updated from a supervisor scope. Discovery runs with backoff (1s, 2s, 4s, capped 15s) and the winning strategy name is surfaced so the UI can explain *why* connecting was slow.

- [ ] **Step 4: Run, commit**

---

## Task 3: Navigation shell

**Files:** `SlipstreamApplication.kt`, `MainActivity.kt`, `ui/SlipstreamNavHost.kt`, tests

- [ ] **Step 1: Write the failing test** — five destinations in order (Home, Browse, Transfers, History, Settings); Home is the start; the connection pill maps state→`MeridianStatus` exactly as Plan 5's shell does (Connected→Positive, Searching/transferring→Info, Degraded→Warning naming the band, Lost→Critical).

- [ ] **Step 2–4: Implement, test, commit**

`MeridianTheme` wraps the whole `NavHost` once. Bottom navigation (this is a phone, not the desktop's sidebar); the top bar carries the screen title and the connection pill inline-end.

---

## Task 4: Home screen

**Files:** `ui/home/HomeScreen.kt`, `HomeViewModel.kt`, tests

- [ ] **Step 1: Write the failing test** — header card shows the peer name and link state; the hero metric shows live MB/s during a transfer and a resting label otherwise; four icon-tile actions route correctly (Send files, Browse PC, Stream to PC, Send clipboard); with no paired peer the screen offers pairing instead of the action grid.

```kotlin
@Test fun `an unpaired device is offered pairing rather than dead actions`() = runTest {
    val vm = HomeViewModel(FakeController(paired = false))
    assertEquals(HomeMode.NeedsPairing, vm.state.value.mode)
    assertEquals("Pair a device to get started.", vm.state.value.message)
}
```

- [ ] **Step 2–4: Implement, test, commit**

`MeridianHeaderCard` + `MeridianHeroMetric` + a row of `MeridianIconTile`s, per spec §12.

---

## Task 5: Pairing screen

**This task unblocks the verification Plan 3 could not run.** Plan 3 had to pair by hand-editing `PairedPeerStore` JSON because no UI existed.

**Files:** `ui/pairing/PairingScreen.kt`, `PairingViewModel.kt`, tests

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `shows the code and names the device to compare against`() = runTest {
    val vm = PairingViewModel(FakeController())
    vm.onCodeReceived("482915", peerName = "Desktop-PC")

    assertEquals("482915", vm.state.value.code)
    // protocol/pairing.md records this as a UI obligation: the threat model's last
    // step is a human comparing two numbers, so the prompt must make that concrete.
    assertEquals("Does Desktop-PC show this same code?", vm.state.value.prompt)
    assertTrue(vm.state.value.canConfirm)
}

@Test fun `confirm is unavailable before a code arrives`() =
    assertFalse(PairingViewModel(FakeController()).state.value.canConfirm)

@Test fun `declining cancels without pairing`() = runTest {
    val controller = FakeController()
    val vm = PairingViewModel(controller)
    vm.onCodeReceived("482915", "Desktop-PC")

    vm.decline()

    assertFalse(controller.paired)
    assertEquals("Pairing cancelled.", vm.state.value.status)
}

@Test fun `counts the 120 second window down`() = runTest {
    val vm = PairingViewModel(FakeController())
    vm.onWindowOpened(closesAt = epoch + 120.seconds, now = epoch)
    assertEquals("2:00", vm.state.value.timeRemaining)
}

@Test fun `an expired window says so and offers to reopen`() = runTest {
    val vm = PairingViewModel(FakeController())
    vm.onWindowOpened(closesAt = epoch, now = epoch + 1.seconds)
    assertEquals("Pairing window closed. Open it again to retry.", vm.state.value.status)
}
```

- [ ] **Step 2–4: Implement, test, commit**

The six digits render at `MeridianText.heroMetric` size with wide letter spacing — unmissable across a desk. Settings gets "Pair a device"; Home offers it when unpaired.

---

## Task 6: Browse screen — and `ThumbnailProvider`'s first caller

**Files:** `ui/browse/BrowseScreen.kt`, `BrowseViewModel.kt`, tests

- [ ] **Step 1: Write the failing test** — directories before files; filter chips (All/Video/Audio/Images/Docs) filter by MIME prefix; breadcrumb push/pop; `truncated` surfaces an honest notice; the loading→content→empty triad drives one `MeridianStateView`; a failed list shows the §15 message.

```kotlin
@Test fun `a truncated listing says so rather than pretending it is complete`() = runTest {
    val vm = BrowseViewModel(FakeController(entries = 5000, truncated = true))
    vm.load("/storage/emulated/0")
    assertEquals("Showing the first 5,000 items in this folder.", vm.state.value.truncationNotice)
}

@Test fun `entries carry thumbnail urls when the peer supplied a token`() = runTest {
    // Closes a Plan 3 deviation: ThumbnailProvider had no production caller.
    val vm = BrowseViewModel(FakeController(thumbnailToken = "abc123"))
    vm.load("/DCIM")
    assertTrue(vm.state.value.entries.first().thumbnailUrl!!.endsWith("/thumb/abc123"))
}
```

- [ ] **Step 2–4: Implement, test, commit**

`MeridianListRow` with a thumbnail in a Tint-filled `sm` tile, loaded lazily from the peer's `/thumb/{token}` as rows scroll. **Use a plain `HttpURLConnection`/OkHttp-free loader bound to the peer's LAN address** — no image library that could fetch from anywhere else, per the LAN-only constraint.

---

## Task 7: Transfers screen and queue

**Files:** `peer/TransferQueue.kt`, `ui/transfers/*`, tests

- [ ] **Step 1: Write the failing test** — queued transfers run one at a time; a failed transfer does not stop the queue; progress formats as tabular size/rate/ETA; cancel removes an item; progress collection is throttled so the list does not thrash.

```kotlin
@Test fun `formats progress with tabular size rate and eta`() {
    val item = TransferItem("/big.bin", totalBytes = 4L * 1024 * 1024 * 1024)
    item.apply(TransferProgress(id, 1L shl 30, 4L shl 30, 50.0 * 1024 * 1024))

    assertEquals("1.0 / 4.0 GB", item.sizeText)
    assertEquals("50.0 MB/s", item.rateText)
    assertEquals("1m 1s left", item.etaText)
}
```

- [ ] **Step 2–4: Implement, test, commit**

---

## Task 8: History

**Files:** `peer/HistoryStore.kt`, `ui/history/*`, tests

- [ ] **Step 1: Write the failing test** — persists across instances; newest first; capped at 500 with oldest evicted; "Open" disabled when the file is gone; "Run again" re-enqueues; the list refreshes live as transfers complete.

- [ ] **Step 2–4: Implement, test, commit**

JSON file in app storage. No database for 500 rows.

---

## Task 9: Settings

**Files:** `peer/SettingsStore.kt`, `ui/settings/*`, tests

- [ ] **Step 1: Write the failing test** — stream count clamps 1–8 via `MeridianStepper` and persists; download folder validates and falls back; theme (System/Light/Dark) persists and applies; "Pair a device" opens the window; "Unpair" clears the store; battery-exemption state is reported honestly.

- [ ] **Step 2–4: Implement, test, commit**

Cards at 20dp gaps. Includes the spec §16 explainer: when the link is 2.4 GHz, say so and note that having the PC host the hotspot is usually faster — the app explains a slow link rather than leaving the user guessing.

---

## Task 10: Send — file picker and share sheet, and `FolderExpander`'s first caller

**This task unblocks the "phone picks a video" matrix rows.**

**Files:** `ui/send/SendSheet.kt`, `SendViewModel.kt`, manifest intent filters, tests

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `a shared folder expands to its files with relative paths preserved`() = runTest {
    // Closes a Plan 3 deviation: FolderExpander had no production caller.
    val vm = SendViewModel(FakeController())
    vm.onPathsSelected(listOf(folderWith("a.txt", "sub/b.txt")))

    assertEquals(listOf("a.txt", "sub/b.txt"), vm.state.value.items.map { it.relativePath })
}

@Test fun `a share intent with multiple items queues all of them`() = runTest {
    val vm = SendViewModel(FakeController())
    vm.onShareIntent(listOf(uriFor("one.jpg"), uriFor("two.jpg")))
    assertEquals(2, vm.state.value.items.size)
}

@Test fun `sending with no paired peer explains rather than failing silently`() = runTest {
    val vm = SendViewModel(FakeController(paired = false))
    vm.onPathsSelected(listOf(fileAt("a.txt")))
    assertEquals("Pair a device before sending.", vm.state.value.message)
}
```

- [ ] **Step 2–4: Implement, test, commit**

Manifest gains `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intent filters for `*/*`, so Slipstream appears in Android's share sheet — the natural way to push a file from the phone.

---

## Task 11: Push-to-play and local playback

**Files:** `ui/browse` extension, `SendViewModel` extension, tests

- [ ] **Step 1: Write the failing test** — selecting a local video and choosing "Play on PC" sends `stream.request` then `play` and reports success; selecting a remote video and choosing "Play here" opens Media3 against the peer's `/media/{token}` URL; a `play` message received **from** the peer opens the system default player via `ACTION_VIEW` with an explicit MIME type.

```kotlin
@Test fun `play on PC sends stream request then play, and does not download`() = runTest {
    val controller = FakeController()
    val vm = BrowseViewModel(controller)

    vm.playOnPeer("/DCIM/holiday.mp4")

    assertEquals(listOf("stream.request", "play"), controller.sentTypes)
    assertTrue(controller.downloads.isEmpty())   // spec §8: nothing lands on disk
}
```

- [ ] **Step 2–4: Implement, test, commit**

---

## Task 12: Clipboard and permissions

**Files:** `permissions/PermissionGate.kt`, clipboard wiring, tests

- [ ] **Step 1: Write the failing test** — received clipboard text reaches `ClipboardManager` and posts a notification; a URL offers "Open" instead of "Paste"; text over 64 KB is refused with the §15 message; the permission gate requests `MANAGE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`, and the battery exemption **once each**, explains why before asking, and the app still functions when any is denied.

- [ ] **Step 2–4: Implement, test, commit**

---

## Task 13: Full-suite green and a whole-branch review

- [ ] **Step 1: Run everything**

```bash
bash android/scripts/check-meridian-tokens.sh
cd android && ./gradlew :core:testDebugUnitTest :app:testDebugUnitTest \
                        :meridian-compose:testDebugUnitTest \
                        :meridian-compose:validateDebugScreenshotTest
```

State the counts. No skipped, no `@Ignore`, no commented-out test files.

- [ ] **Step 2: Whole-branch review**

Review the complete diff for races between the new UI collection paths and `:core`'s existing loops — that pass has caught a Critical bug on two of the three branches in this project so far.

- [ ] **Step 3: Commit**

---

## Task 14: Real-hardware verification — the matrix and the throughput numbers

**The task this whole plan exists to make possible.** Plan 3 could run almost none of it: pairing had to be done by hand-editing JSON, no phone UI existed to pick a file, and every transfer row was blocked behind the TLS-interop failure.

**Preconditions for this task specifically:**
- The `.NET ↔ Android` TLS handshake must be working. If it is not, **stop and report** — do not fake the rows. See `2026-08-25-android-core-deviations.md` for the known failure and its leading hypothesis.
- A real phone and a real PC, both on `main`.

- [ ] **Step 1: Pair through the UI**

Open the pairing window on both. Confirm both show the **same six digits**. Accept on both.

Then verify the negative path: unpair, retry, and **decline on one side** — neither device may end up paired.

- [ ] **Step 2: Walk the matrix, recording actual results**

| # | Check | Expected |
|---|---|---|
| 1 | Phone hotspot, PC joins | PC finds phone via `gateway-probe`; record elapsed ms |
| 2 | Both on external WiFi | `multicast` wins, or `subnet-sweep` if the AP drops it |
| 3 | Repeat on the same network | `cached-endpoint` wins, well under 500 ms |
| 4 | PC pulls 1 GB from phone | Completes; **hashes match**; record MB/s |
| 5 | Phone pulls 1 GB from PC | Same |
| 6 | Kill WiFi mid-transfer, restore | **Resumes from the stopped byte**, does not restart |
| 7 | Phone picks a video → PC plays it | PC's default player opens; seek is instant; nothing written to disk |
| 8 | PC browses phone DCIM | Thumbnails and durations appear |
| 9 | Clipboard both directions | Text arrives and pastes |
| 10 | Share sheet → Slipstream → PC | File arrives intact |
| 11 | Airplane mode, cellular on | **No traffic leaves the device** |

- [ ] **Step 3: Record the throughput numbers — the report's open item**

This is the measurement the Plan 3 report had to leave blank. Record **four** figures, each as an average of three 1 GB transfers:

| Topology | Direction | MB/s | Band |
|---|---|---|---|
| Phone hotspot, PC client | PC ← phone | | |
| Phone hotspot, PC client | PC → phone | | |
| Both on router WiFi | PC ← phone | | |
| Both on router WiFi | PC → phone | | |

Note the negotiated band (2.4 vs 5 GHz) for each. Spec §16 predicts 3–5 MB/s over the hotspot and 40–100 MB/s through a router, because the phone runs AP and client duty on one radio.

**Record what you measure. Do not tune toward the spec's numbers** — if the hotspot gives 1.5 MB/s, that is the finding, and it belongs in the deviations record rather than being explained away.

- [ ] **Step 4: Write the deviations record**

`docs/superpowers/plans/2026-08-26-android-app-deviations.md`, following `2026-08-25-core-discovery-control-deviations.md`. It must include the completed matrix table and the throughput table above, and must state plainly which rows could not be run and why.

- [ ] **Step 5: Update the Plan 3 record**

In `2026-08-25-android-core-deviations.md`, mark the carried-forward items this plan closed — the pairing-UI gap and any matrix rows now verified — with a pointer here. Leave genuinely open items open.

- [ ] **Step 6: Commit**

---

## Self-Review

**Spec coverage.** §12 Android screens → 3, 4, 6, 7, 8, 9. §12 status→connection mapping → 3. §12 hero metric is the live rate → 4. §4 pairing UI obligation → 5. §8 push-to-play both directions → 11. §9 thumbnails consumed → 6. §10 clipboard → 12. §14 foreground service, permissions, battery → 12. §15 error voice → 2, 4, 6, 10. §16 explain the slow link, and measure it → 9, 14.

**Plan 3 deviations closed here:** `ThumbnailProvider` gets a caller (Task 6); `FolderExpander` gets a caller (Task 10); the pairing-UI gap that blocked Task 13 (Task 5). **Left open, deliberately:** the STA+AP tie-break and the `onNetworkChanged` readiness gate — both are `:core` concerns being handled separately.

**Placeholder scan.** No `TBD`/`TODO`. Tasks 3, 4, 7, 8, 9, 12 compress implement/test/commit into single steps because the pattern is fixed by Tasks 1–2 and mirrored from Plan 5's shipped equivalents; each still states exactly what its tests must assert.

**Type consistency.** `PeerConnectionState` mirrors Plan 5's five members. `MeridianStatus`, `MeridianListRow`, `MeridianStateView`, `MeridianStepper`, `MeridianHeaderCard`, `MeridianHeroMetric`, and `MeridianIconTile` come from `:meridian-compose` unchanged. `TransferProgress`, `ListResult`, `FileEntry`, and `PairingProgress` come from `:core` unchanged — **verify their real shapes in the Preconditions step before Task 2.**
