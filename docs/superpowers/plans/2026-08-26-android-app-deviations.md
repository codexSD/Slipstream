# Deviations from Plan 4 (Android app)

**Date:** 2026-08-26
**Plan:** [`2026-08-26-android-app.md`](2026-08-26-android-app.md)
**Executed via:** subagent-driven development, 14 planned tasks + 2 inserted `:core` protocol
tasks (1.5, 2.5) + a whole-branch review fix wave. Some tasks (5, 7, 8, 9) ran in parallel isolated
worktrees at the user's request; Tasks 11-12 were written without incremental build/test
verification at the user's request, then verified together in one combined pass.

Everything below is a place where the delivered code differs from the plan's verbatim text or the
design spec's stated behavior, where the plan's own scope left a gap that surfaced during
implementation, or where real-hardware verification could not be completed. Each entry states
what changed, why, and whether it's closed or carried forward.

---

## Task 14 — real-hardware verification could not be run

**This is the headline finding of this document.** Task 14's hard precondition is a real phone
and a real PC, both on `main`, with the `.NET ↔ Android` TLS handshake working. Neither condition
could be met in this session:

- **No physical Android device or Windows PC was reachable from this execution environment.**
  This session ran entirely inside an isolated git worktree on a single development machine, with
  an Android SDK and emulator toolchain available for build/test purposes only — no way to install
  and run the app on real hardware, no way to run the Windows companion app, and no way to put two
  real devices on a shared network.
- Separately, `2026-08-25-android-core-deviations.md` (Plan 3) already records the
  `.NET ↔ Android` TLS handshake as **unresolved** from a prior session: `.NET`'s `SslStream`
  client times out against Android's server, while a manual `openssl s_client` mutual-TLS
  handshake against the same server succeeds — root cause not identified (candidates: cipher-suite
  negotiation, ALPN, or session-resumption differences between .NET's SslStream and Android's
  JSSE/Conscrypt stack). This session did not re-attempt that investigation, since the hardware
  precondition was already unmet regardless.

**Per explicit instruction, no matrix row was faked, estimated, or skipped-without-marking.** The
table below records every row as **NOT RUN**, with the blocking reason, rather than a guessed or
extrapolated result.

### Pairing verification (Task 14 Step 1)

| Check | Result |
|---|---|
| Open pairing window on both devices, confirm same 6-digit code, accept on both | **NOT RUN** — no real device pair available |
| Unpair, retry, decline on one side — neither device ends up paired | **NOT RUN** — same reason |

### Discovery/transfer/media matrix (Task 14 Step 2)

| # | Check | Result |
|---|---|---|
| 1 | Phone hotspot, PC joins — gateway-probe wins | **NOT RUN** — no hardware; also blocked upstream by Plan 3's unresolved TLS-interop gap |
| 2 | Both on external WiFi — multicast/subnet-sweep wins | **NOT RUN** |
| 3 | Repeat on same network — cached-endpoint wins | **NOT RUN** |
| 4 | PC pulls 1 GB from phone, hashes match | **NOT RUN** |
| 5 | Phone pulls 1 GB from PC | **NOT RUN** |
| 6 | Kill WiFi mid-transfer, restore — resumes from stopped byte | **NOT RUN** |
| 7 | Phone picks a video → PC plays it | **NOT RUN** |
| 8 | PC browses phone DCIM, thumbnails/durations appear | **NOT RUN** |
| 9 | Clipboard both directions | **NOT RUN** |
| 10 | Share sheet → Slipstream → PC | **NOT RUN** |
| 11 | Airplane mode, cellular on — no traffic leaves the device | **NOT RUN** |

### Throughput numbers (Task 14 Step 3)

| Topology | Direction | MB/s | Band |
|---|---|---|---|
| Phone hotspot, PC client | PC ← phone | *not measured* | *not measured* |
| Phone hotspot, PC client | PC → phone | *not measured* | *not measured* |
| Both on router WiFi | PC ← phone | *not measured* | *not measured* |
| Both on router WiFi | PC → phone | *not measured* | *not measured* |

**All four cells are blank, as instructed** — this is the same open item Plan 3's own report had
to leave blank, and it remains blank after this plan too. Closing it requires a session with
access to two real devices and a working TLS handshake between the two implementations, neither
of which existed here.

### What Task 14 established anyway, from a build/test perspective (not a substitute for the above)

- The full build compiles and links against real Android SDK platforms 28/33-36 on this machine.
- Every module's unit/Robolectric test suite passes: `:core` 242, `:app` 131, `:meridian-compose`
  73 — all independently re-run with `--rerun-tasks` (forced, not cached) at least twice, zero
  failures, zero skipped, zero `@Ignore`.
- The Meridian design-token gate (`check-meridian-tokens.sh`) passes across the whole app.
- These are necessary but explicitly **not sufficient** evidence for the matrix above — none of
  them exercise two real devices talking over a real radio, which is the entire point of Task 14.

---

## Preconditions reconciliation (before Task 2) — two real `:core` gaps found

Reading `SlipstreamPeer.kt` before Task 2, as the plan required, surfaced two divergences from
what the plan assumed:

1. **Transfer progress is a callback (`onProgress: ((Long) -> Unit)?`), not a `Flow`.** The plan's
   own `PeerController` interface sketch assumed `:core` already exposed progress as a `Flow`.
   Reconciled by having `PeerController` bridge the callback into a `Flow` via `callbackFlow`, and
   by adding a small, additive `onProgress` parameter to `SlipstreamPeer.pullFile` (which didn't
   thread one through to `TransferEngine.pull` even though `TransferEngine` already accepted one).
2. **`:core` had no `push` (upload) wire protocol at all — only `pull`.** `SessionMessageTypes` had
   no `push.offer`/`push.ok`, and `BulkServer` only ever served files a peer had explicitly pulled.
   This is not a UI gap; it's a protocol gap that blocks Task 10 (Send) and the plan's own
   `PeerController.push()` entirely. **Inserted Task 1.5** to add it: `push.offer`/`push.ok`
   message types, a `SlipstreamSession.pushOffer` handler, `BulkServer.onBytesServed` (a
   sender-side progress hook, since the sender of a push never runs the download loop itself),
   and `SlipstreamPeer.pushFile`. Additive; all 227 pre-existing `:core` tests kept passing plus 3
   new ones. Reviewed clean.

## Ruling: Task 2.5 inserted — `play` message also missing

Task 2's own review found `PeerController.streamOnPeer` had no real wire backing: `:core` had no
`play` message type, so even a raw `{"type":"play"}` payload would have been silently dropped by
`SlipstreamSession.dispatch`'s documented forward-compatibility contract. **Inserted Task 2.5** to
add a one-way `play` message (modeled on the existing `clipboard` one-way-event precedent — no
reply, silent-drop on a bad path) plus an `onPlayRequested: (File) -> Unit` callback on
`SlipstreamPeer`. Additive; 228→231 `:core` tests, reviewed clean.

## Ruling: Task 11 found a real shape mismatch in Task 2.5's `play` message

Task 2.5's `play()` handler resolved a `path` field against the **receiver's own root** — correct
for "play a file the receiver already has," but design.md §8's actual push-to-play flow needs the
**receiver to open a URL for a file the sender owns** (the sender is the sole owner of the file
being played; the receiver has never seen it). Task 11 fixed this additively: `play()` now prefers
a `url` field (new `onPlayUrlRequested` callback) and falls back to the original `path`-based
behavior unchanged, so Task 2.5's own tests keep passing. `SlipstreamPeer.mediaEndpoint` was added
so a device can self-issue a stream token/URL for its own file without a wire round trip.

## Task 3 — `SlipstreamPeer.start()` is not idempotent (real `:core` defect, not fixed)

Found during Task 3's fix round: calling `SlipstreamPeer.start()` twice unconditionally
reconstructs `ControlServer`/`BulkServer`/`MediaServer`, rebinding already-bound fixed ports and
throwing. `RealPeerController.start()` calls the underlying `start()`, so any `:app` code path that
might run after the peer is already running must call `RealPeerController.reconnect()` instead —
`reconnect()` drives the identical `Idle → Searching → Connected/Lost` state machine without
touching `peer.start()`. `PeerForegroundService` (the only production driver) does this correctly,
verified in the final whole-branch review. Carried forward as a known `:core` defect for a future
session to fix properly (make `start()` idempotent, or throw a clearer error).

## Task 6 — thumbnail wire protocol added (also `:core` work bundled into an app-facing task)

The plan's own self-review states "Task 6 gives `ThumbnailProvider` its caller" as this task's
explicit job — but neither the wire protocol nor the media server supported thumbnails at all
before this task: `list()` returned no thumbnail field, and `MediaServer` recognized only
`/media/<token>`. Added additively: `list()` issues a `thumbnailToken` for image-mime entries only
(not attempted on every file), `MediaServer` recognizes `/thumb/<token>` via the same
validate/serve/Range logic as `/media/<token>`, and the thumbnail cache directory is kept as a
sibling of (never inside) the browsable root. Reviewed clean, no fix round needed.

## Task 10 — empty directories are dropped from folder-sends (disclosed `:core` gap, not fixed)

Spec §7 states empty directories should be preserved on the receiver. `:core`'s push protocol has
no wire message to create an empty directory (only file bytes travel), so `SendViewModel.expand()`
silently filters them out. This is a real, narrow gap — disclosed honestly in the final version of
this task's own documentation after an earlier draft incorrectly attributed the decision to a
nonexistent plan instruction (corrected during that task's own fix round). Carried forward.

---

## Found and fixed during per-task reviews (selected highlights)

Full per-round history lives in this plan's SDD ledger before it's deleted; git history carries
every commit. Selected items worth recording here:

- **Task 3:** the connection pill was fully wired visually but nothing ever called
  `peerController.start()`/`.reconnect()` in production — the pill would have shown "Not
  connected" forever regardless of real connectivity. Fixed by having `PeerForegroundService`
  drive the controller's lifecycle alongside the underlying peer's.
- **Task 4:** three of four Home action tiles crashed on tap (navigated to nonexistent nav
  routes) — fixed by disabling them until their features existed; a required brief test bypassed
  the real `HomeViewModel` — fixed to exercise it directly via Turbine.
- **Task 5:** clean first pass, no fix round.
- **Task 7:** two required tests (cancel removes an item, progress is throttled) were quietly
  dropped with a self-undermining "timing-sensitive" excuse — the underlying functionality was
  real, only the tests were missing; restored with deterministic (non-wall-clock) versions.
  Separately, the real UI list was built but never wired to `TransferQueue`'s live state in
  production (always showed the empty state) — fixed with an application-scoped singleton.
- **Task 9:** "Pair a device"/"Unpair" were inert `Text` composables with no `onClick`, and theme
  selection had no visible effect — both fixed. A first attempt at adding regression tests for the
  button fix only checked composition without ever simulating a click (would have passed even
  with the bug still present) — caught and replaced with real `performClick()`-based tests.
- **Task 10:** a required "multi-item share queues all of them" test only checked a count, not
  item identity — strengthened. A scope decision (dropping empty directories) was initially
  misattributed to a nonexistent plan instruction — corrected.

## Ruled during task review (kept as implemented, not fixed)

- **`Idle` connection state maps to `MeridianStatus.Neutral`** — not explicitly specified by the
  plan's state→color table; the obvious fit, kept.
- **Push-receives are not tracked in `SlipstreamPeer.activePulls`** (Task 1.5) — a network change
  mid-receive of a pushed file will not resume, only pulls will. Explicitly allowed either way by
  the task's own brief; the implication was disclosed, not hidden.
- **`TransferProgress.totalBytes` is `0` until the final tick for `pull()`** (Task 2) — the total
  size isn't known until `pullFile` returns; `push()` has the real total throughout since it comes
  from `File.length()`. Currently low-impact since a download affordance was only added in the
  final whole-branch fix wave (see below) — worth revisiting now that it's live in the UI.
- **Thumbnail URLs use the fixed `SlipstreamPorts.MEDIA` constant, not the media server's actual
  bound port** (Task 6) — correct in production, where the server always binds that fixed port;
  only diverges in a test harness that binds port 0 for collision avoidance.
- **`TreePathResolver` only resolves the primary storage volume** for folder-picker tree URIs
  (Task 10) — a secondary volume/SD card falls back gracefully to a clear "Send failed."-style
  message (verified non-crashing), rather than transferring. Real, disclosed follow-up.

---

## Final whole-branch review (Task 13) — 4 Critical, 4 Important integration gaps found and mostly closed

Every one of the 14 tasks passed its own scoped review, and the full test suite was green
throughout — but the actual end-to-end wiring between tasks had never been completed. This is
exactly the class of defect a final whole-branch review exists to catch (the same pattern Plan 3's
own final review hit, per its deviations doc). Findings:

- **Pairing was unreachable** — no navigation route existed for the fully-built, fully-tested
  `PairingScreen` from Task 5, and Home's "Start Pairing" button was a literal `TODO` no-op.
- **"Play on PC" from Browse was broken by construction** — it fed a remote (peer-owned) path into
  Task 11's correctly-redefined local-path-only push-to-play API. Required a real design decision,
  not a mechanical fix: Browse's broken action was removed, and push-to-play's real home was moved
  to the Send screen, where the file genuinely is local. Browse's "Play here" (local Media3
  playback of a remote file) was verified untouched by this change.
- **History was dead code** — no navigation route, nothing ever called `HistoryStore.addEntry`,
  "Run again" was a no-op.
- **`TransferQueue` had zero production callers**, and there was no download affordance anywhere
  in the UI — `SendViewModel` drove `push()` directly into its own local list instead of the
  shared queue, so the Transfers screen was permanently empty regardless of real activity.
- Plus 4 Important findings: theme changes required an app restart to take effect; two Home tiles
  ("Stream to PC", "Send clipboard") were still wired to `null`; `HomeViewModel` was rebuilt on
  every recomposition instead of `remember`ed.

**A consolidated fix wave was dispatched** (one implementer, all findings together, per the
project's process rules against one-fixer-per-finding). During that fix wave, the implementer
initially violated the no-subagents rule by delegating to a background agent of its own — caught
before any changes landed, corrected, and the implementer then personally verified and fixed the
resulting code (including a flaky test the delegated work had introduced) before reporting done.

**The mandatory final re-review** (the last one permitted under this project's process — no second
fix wave follows it) found 9 of 11 verified items cleanly closed, but surfaced these residual gaps,
adjudicated as follows since no further fix wave was available:

- **Parked (real, but nothing downstream depends on it) — flagged as the top item to fix before
  real-world use:** History's "Run again" for a *downloaded* file re-pulls into a bogus
  destination (the entry recorded the peer's remote path, not the local download destination it
  actually landed at), and "Open" is permanently dead for every downloaded entry as a result. Push
  entries are unaffected. Small, contained fix: record the real local destination on a Pull
  `HistoryEntry`.
- **Parked (disclosed, narrow blast radius):** the newly-wired Transfers cancel button only ever
  attaches to already-running transfers, which `TransferQueue.cancel` cannot actually stop
  (it only removes items still queued) — cosmetically wired, functionally inert. The
  Transferring/Complete/Failed status pill can never update in the UI, because `TransferItem`'s
  `equals` excludes its mutable state field, so `MutableStateFlow` conflates the "changed" emission
  away as equal to the previous one.
- **Parked, recorded as a real coverage gap:** `HistoryViewModel` has zero tests — the direct cause
  of the "Run again" bug above surviving the fix wave's own verification.
- **Parked, Minor:** a tautological test for the theme-reactivity fix (asserts on the settings
  store's own value, not on anything the nav host actually renders); a hard
  `applicationContext as SlipstreamApplication` cast introduced in `SlipstreamNavHost`; an unused
  `destination` parameter on `TransferQueue.enqueue`; a dead unreachable fallback branch in
  `BrowseScreen`; Send showing a "Play on PC" action on non-media items; a few fully-qualified
  references used instead of imports (style only, no functional effect); `HistoryScreen`'s "Open"
  action is still a literal placeholder comment, now user-reachable for the first time.

Verified independently by the controller and by the final re-reviewer, on separate runs: `:core`
242, `:app` 131, `:meridian-compose` 73 tests, all green, no flakiness reproduced across multiple
`--rerun-tasks` runs; `check-meridian-tokens.sh` passes.

---

## Summary: what to do next

1. **Task 14's matrix and throughput table are the open item this document exists to record
   plainly** — none of it could be run without real hardware. A follow-up session with a real
   phone and PC, and the Plan 3 TLS-interop gap resolved, is required before any row can be filled
   in honestly.
2. **Fix the History "Run again"/download-destination bug** (parked NEW-1 above) before relying on
   that feature — it's small, contained, and currently actively wrong rather than merely
   incomplete.
3. The Transfers cancel-button and status-pill gaps, and the missing `HistoryViewModel` test
   coverage, are reasonable to defer to a dedicated follow-up but should not be forgotten — they
   are recorded here specifically so they aren't rediscovered from scratch.
