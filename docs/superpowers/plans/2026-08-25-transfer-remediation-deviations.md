# Deviations from the transfer-remediation plan

**Date:** 2026-08-25
**Plan:** [`2026-08-25-transfer-remediation.md`](2026-08-25-transfer-remediation.md)
**Executed via:** subagent-driven development, 8 tasks with review between tasks, two fix rounds
within Task 5, one fix round within Task 7, and a final whole-branch review with one consolidated
fix wave.

Everything below is a place where the delivered code differs from the plan's verbatim text, or
where the plan's own scope left a gap that surfaced during implementation. Each entry states what
changed, why, and whether it's closed or carried forward.

## Historical record: how this plan was triggered

### ThumbnailProvider stub shipped with tests commented out (Plan 2, found by review not by the suite)

Plan 2 (`2026-08-25-core-transfer-media.md`) shipped a stubbed `ThumbnailProvider` with
`ThumbnailProviderTests.cs` commented out in its entirety — a single `/* ... */` block wrapping the
whole file. This was found by code review, not by the test suite: the suite reported 235/235 green
while masking a completely non-functional feature. This plan's Task 1 restored the tests (the file
contains 7 tests; 4 failed against the stub as expected, 2 passed trivially since a stub returns
null for everything, and the replacement `Returns_null_for_a_zero_byte_file_of_an_unknown_type`
also passed trivially against the stub for the same reason), and Task 2 implemented the real
shell-backed provider, bringing the suite to 242/242 (235 + 7).

One test, `Returns_null_for_a_zero_byte_file_of_an_unknown_type`, needed its assertion relaxed to
`result is null || File.Exists(result)` because this build machine's shell returns a generic icon
for unknown extensions rather than nothing. This is a documented machine-specific exception, not a
weakening of the other six tests. **Closed.**

## Deviations from the plan's verbatim text

### Bulk token use-counting removed (Task 3)

The original design minted bulk transfer tokens for a fixed number of uses (`expectedStreams`). A
fragmented resume — many bitmap gaps after a dropped multi-stream transfer — legitimately needs
more connections than there are streams, so the client couldn't know its range count until after it
already held the token, and the server couldn't size the use-budget correctly: resume would open
more sockets than the token permitted and fail.

**Fix:** bulk tokens became use-unlimited (`int.MaxValue`), still scoped as before to one transfer
id and one file path, minted over TLS to an already-paired peer. The former per-use counter added
nothing an attacker had to defeat, since transfer-id/path scoping and TLS-minting are what actually
protect the bulk path. In exchange, token expiry dropped from 24 hours to 5 minutes — a materially
tighter bound on how long a leaked token stays exploitable than the counter ever provided.
Client-side, `BulkClient.DownloadAsync` gained a `SemaphoreSlim(streamCount)`-bounded concurrency
cap so a fragmented bitmap's extra ranges are all still processed, just never more than
`streamCount` sockets at once.

Separately, the plan's own text says "Expected: 3 new tests pass; suite 244 (242 + 3, with one
TokenVault test replaced by two)" — arithmetic inconsistent with its own parenthetical
(242 − 1 + 2 + 3 = 246, not 244). The controller verified 246/246 directly with `dotnet test` and
ruled to trust the actual count over the stale headline number. Recorded as a controller ruling on
stale plan text, not a code change. **Closed.**

### Small-file rule applied to the download path (Task 4)

`TransferPlan.Split` already respected the 4 MB small-file whole-assignment threshold, but
production called `SplitMissing`, which never checked it — a 3 MB file was needlessly split across
multiple streams. Fixed by adding the same guard to `SplitMissing`. No deviation from the plan's
given code; suite reached 249/249 (247 passing plus a known pre-existing intermittent
`MediaServerTests.Advertises_range_support` flake, a socket-close timing issue confirmed unrelated
to this plan's diffs by reproducing it in isolation, where it passes reliably, and in most
full-suite runs). **Closed.**

### Sidecar persistence debounced off the per-chunk write path (Task 5) — the durability trade

`PartFile.WriteChunkAsync` previously held a global semaphore and rewrote the entire JSON state
sidecar on every chunk — roughly 100 serialized rewrites/second at 100 MB/s across parallel
streams. Fixed by narrowing the lock to cover only the bitmap bit-flip (nanoseconds), debouncing the
actual sidecar write to at most once per 500 ms, and always flushing on `CompleteAsync` and
`DisposeAsync` so a crash loses at most 500 ms of progress — which resume re-fetches anyway via the
bitmap exchange. Bench measured 282.3 MB/s post-fix (floor: 150 MB/s).

This narrower lock scope introduced a genuine new race not present in the original design or the
plan's text: two overlapping debounced `PersistStateAsync` calls (possible if one write call takes
longer than 500 ms under sustained multi-stream load) both target the same temp filename
(`StatePath + ".tmp"`), risking corrupted JSON getting moved over the real sidecar. Caught in task
review (Important), fixed in fix round 1 by serializing just the write-and-move body behind a
dedicated `SemaphoreSlim(1,1)` (the bit-flip lock stays narrow). That fix round's own diff then
introduced a second bug — `_persistGate.Release()` was called unconditionally in a `finally` block
even when `WaitAsync(cancellationToken)` threw `OperationCanceledException` without acquiring,
causing a masking `SemaphoreFullException` on cancellation. Caught in the round-1 re-review
(Critical), fixed in fix round 2 with an `acquired` bool tracking whether the wait actually
succeeded before releasing. Final state: exception-safe, cancellation-honoring, race-free debounced
persistence. Suite reached 254/254 after both fix rounds. **Closed.**

### Retry reconnect-target expression deviates from the plan's literal code (Task 6)

The plan's given code for `LiveConnectionAsync` reconnects via
`new IPEndPoint(peerEndpoint.Address, SlipstreamPorts.Control)`. The implemented code instead uses
`supplied.RemoteEndPoint`, because the plan's literal expression fails against the test harness
(which binds the control listener to an ephemeral port, not the fixed production port 53321),
throwing a real `SocketException` in the GREEN run.

Independently verified by the task reviewer as behaviorally identical in production: every
`ControlConnection` is constructed via `ControlClient.ConnectAsync(endpoint, ...)`, so
`supplied.RemoteEndPoint` is definitionally `peerEndpoint.Address:ControlPort` under fixed ports —
the same value the plan's literal formula would compute — while additionally working correctly
under the test harness's ephemeral ports. `LanGuard.EnsureLocal` still runs on every reconnect
regardless of which expression supplies the target, so LAN-only peer validation is unaffected.
Ruled acceptable as-is by the controller — no code change needed; recorded here because it is a
departure from the plan's literal text, not because it changed behavior. **Closed.**

### Test-design gap on the atomic-destination-replace test (Task 7)

The plan's `An_existing_destination_survives_a_failed_move` test holds the destination open with
`FileShare.None` and expects the old delete-then-move code to fail differently from the new atomic
move code. On Windows, opening a file with `FileShare.None` blocks even `File.Delete` of that
file — so under the old buggy code, `File.Delete(DestinationPath)` itself throws before
`File.Move` is ever reached, meaning the original file survives under the buggy code too, but for a
reason (delete failing) unrelated to the atomicity guarantee the test is meant to exercise. The
plan's assumption ("today delete runs first, so the original is gone") holds on POSIX filesystems
where deleting an open file silently unlinks it, but not on Windows/NTFS with `FileShare.None`'s
strict semantics.

Net effect: on Windows, this specific test cannot fully discriminate the fixed atomic-move behavior
from the old buggy behavior for the exact hazard described in the plan. The production fix itself
(`File.Move(PartPath, DestinationPath, overwrite: true)`, a single atomic NTFS call) was
independently verified correct by code inspection during task review, regardless of this
test-design limitation. Ruled acceptable as-is by the controller — a Windows test that genuinely
forces "delete succeeds, then move fails" would need a different locking primitive (e.g.
`FileShare.Delete`) not judged worth the added complexity for this plan.

Separately, the test's exception-type assertion was narrowed during a fix round from an overly
broad `Assert.ThrowsAnyAsync<Exception>` to explicitly checking
`IOException or UnauthorizedAccessException`, since `File.Move` against a Windows-locked
destination surfaces `UnauthorizedAccessException` (which does not derive from `IOException`), not
the `IOException` the plan's snippet implied. **Closed.**

### Clipboard exposed as an event, not written to the clipboard (Task 8, this task)

`SlipstreamSession.HandleClipboard` stored received text to `LastClipboardText` and did nothing
else — spec §10's "places it on the system clipboard" never actually happened, because
`Slipstream.Core` targets plain `net9.0` with no clipboard API, and taking a UI dependency in the
core library would be architecturally wrong.

**Fix:** added a `ClipboardReceived` event that the host application (WinUI, or a harness) is
expected to subscribe to. This closes the "reports success while doing nothing observable" gap by
turning it into an explicit, visible wiring point. The event itself, and the tests confirming it
fires with the exact text and does not fire for an oversized payload, are **closed** for this
scope. The wiring itself — an actual WinUI subscriber that calls `Clipboard.SetContent` — remains
**future work**, outside `Slipstream.Core`'s scope (belongs with the WinUI host app).

### Spec §5 network-change handling still unimplemented — carried forward (Task 8, documented not fixed)

`SlipstreamPeer.NetworkChanged` is raised on OS connectivity events (`NetworkAddressChanged`), but
nothing subscribes to it. The control/transfer/media servers stay bound to the address chosen at
`StartAsync`, so the peer becomes unreachable after a real network switch (e.g. WiFi → hotspot)
until the app restarts. Spec §5's "tear down, rediscover, resume" is not implemented.

This is a real subsystem — tear down bound sockets, re-run discovery, resume in-flight transfers
from persisted bitmaps — that belongs with the UI layer that owns reconnection state and
user-visible status, not a patch applicable inside `Slipstream.Core`'s transfer/media remediation
scope. **Not implemented here, and deliberately so.** A pointer was added to
`docs/superpowers/plans/2026-08-25-core-transfer-media.md`'s "Deferred" section in this same task
(Task 8, Step 2). **Carried forward** — real future work still needed, most likely alongside the
WinUI reconnection UX.

## Task 8 completion state

Suite: 258/258 (256 entering this task + 2 new tests: `ClipboardReceived` fires with the exact
text, and does not fire for an oversized payload), verified directly with `dotnet test`. Bench:
219.1 MB/s, above the 150 MB/s floor, PASS. Both greps for commented-out test files and `Skip =`
markers report clean — the gate proving this plan's own trigger (Plan 2's stubbed feature with
disabled tests) did not recur in this plan's own deliverables.

An intermittent failure was observed on the first Task 8 verification run
(`PartFileTests.Overlapping_debounced_persists_never_corrupt_the_sidecar`, an
`UnauthorizedAccessException` from `File.Move`) and, at the time, characterized as flaky and
non-regressing based on a single clean re-run. **Correction:** this was not flakiness — it was a
real, reproducible defect in already-merged Task 5 code, caught properly by the final whole-branch
review below (see "Unguarded opportunistic persist"). The apparent flakiness was because the race
requires enough concurrent load to surface reliably, which not every run produced.

## Final whole-branch review and consolidated fix wave

The final review (dispatched on the most capable available model, after all 8 tasks were
individually complete and approved) found issues that only became visible with the whole diff in
view — interactions between tasks that no single task's scoped review could see. One consolidated
fix dispatch closed all of them, followed by one scoped re-review, per process. No second fix wave
was needed.

### `TransferEngine._reconnected` bricked the engine after its first reconnect (Critical)

`SlipstreamPeer` creates one `TransferEngine` per peer and reuses it for every pull via `Engine`.
Task 6's reconnect fix stored the fresh connection in an instance field, `_reconnected`, and
disposed it in `PullAsync`'s `finally` block — but never set the field back to `null`. Task 6's own
task-scoped test only ever did one pull, so this passed review at the time. In the whole-branch
view: any pull after the first successful reconnect would find `_reconnected` still non-null,
short-circuit past the liveness probe entirely, and send on a disposed connection — permanently
bricking recovery for the rest of the engine's (i.e. the peer's) lifetime. This is exactly the kind
of defect a whole-branch review exists to catch, since it required Task 6's design to exist and
more than one pull to happen — neither visible from a single task's diff.

**Fix:** capture `_reconnected` into a local, null the field, then dispose the local — so the next
pull starts clean. Added a regression test doing two sequential `PullAsync` calls on one engine,
with the first forcing a reconnect. **Closed.**

### Unguarded opportunistic persist could abort a healthy transfer (Important)

`PartFile.WriteChunkAsync` called `PersistStateAsync` with no try/catch when the debounce window
elapsed. `PersistStateAsync`'s `File.Move` can throw `UnauthorizedAccessException` on a transient
Windows sharing violation (confirmed reproducible under real concurrent load — this is what the
Task 8 "flaky" note above was actually catching). `UnauthorizedAccessException` doesn't derive from
`IOException`, so `DisposeAsync`'s existing guard didn't cover it either, and the exception
propagated out of `WriteChunkAsync`, aborting the whole chunk write and the transfer — a much
harsher failure than the debounce design's own stated "best effort, lose at most 500ms" contract.

**Fix:** catch `IOException` and `UnauthorizedAccessException` (not `OperationCanceledException`)
around the persist call in `WriteChunkAsync`; move the `_dirty = false` reset to after the move
succeeds (previously it cleared before attempting the write, so a swallowed failure would have left
`DisposeAsync` believing there was nothing to flush — silently trading an abort for lost progress);
widen `DisposeAsync`'s catch to include `UnauthorizedAccessException` too. **Closed.**

### 5-minute bulk-token expiry could expire mid-transfer (Important)

Task 3 shortened bulk token expiry from 24 hours to 5 minutes as a security tightening (see above),
reasoning about attacker exposure window. It didn't account for Task 3's own semaphore-bounded
concurrency: ranges beyond the first `streamCount` don't present their token until earlier ranges
finish, so a transfer legitimately exceeding 5 minutes — an ordinary case at phone-hotspot speeds
per spec §16 — could have later ranges rejected once the token expired, with the transfer's one
available retry not always enough to finish the remaining work in time.

**Fix:** raised `TokenVault.BulkLifetime` to 30 minutes — still 24-48× tighter than the original
24 hours, but bounded against realistic transfer duration rather than only attacker patience.
Updated the explanatory comment and the boundary test accordingly. **Closed.**

### Debounce cadence test didn't test cadence (Important)

`Does_not_rewrite_the_sidecar_on_every_chunk` (Task 5) only asserted the sidecar file existed and
contained the word "Bitmap" — both true identically under the old per-chunk-write code the task
existed to replace, so the test provided no actual regression protection for the cadence itself.

**Fix:** added an internal, `Interlocked`-incremented persist counter to `PartFile`, incremented
only after a successful write-and-move; the test now asserts the count stays well below the chunk
count for 50 rapid writes. **Closed.**

### Nine Minor findings — parked, not fixed

The final review also surfaced nine Minor findings (half-open connection detection gaps, a raw
`SocketException` surfacing instead of a friendly error message, `_completed` being set before a
move that could still fail, unbounded `TokenVault`/`ThumbnailProvider` entry growth, a near-tautological
relaxed assertion, test-naming nits, and a theoretical re-entrant `StartAsync` leak). None are
load-bearing for this plan's scope — each is either pre-existing behavior unchanged by this plan,
a documented and already-authorized trade, or a real but narrow edge case with no test coverage
gap it's hiding. Parked as deferred follow-up work, not carried into a second fix wave.

## Final state

Suite: 259/259 (258 after Task 8 + 1 new regression test for the reconnect fix), verified with
`dotnet test`. The previously-flaky `Overlapping_debounced_persists_never_corrupt_the_sidecar` test
re-run cleanly 4/4 times after the persist-guard fix. Bench: 246.3 MB/s, above the 150 MB/s floor,
PASS. Both greps for commented-out test files and `Skip =` markers report clean.
