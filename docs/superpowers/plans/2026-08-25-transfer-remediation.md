# Plan 2b — Transfer & Media Remediation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the eleven defects found in the Plan 2 code review — most importantly a stubbed thumbnail provider whose tests were commented out, and a resume path that fails in exactly the fragmented-bitmap case resume exists for.

**Architecture:** Targeted fixes to merged code. No new subsystems. Two findings require a deliberate design change rather than a patch: bulk-token use accounting (Task 3) and sidecar persistence cadence (Task 5); both are argued in place rather than silently altered.

**Tech Stack:** .NET 9, C# 13, xUnit. `System.Drawing.Common` (already referenced).

**Spec:** [`2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §7, §8, §9, §10, §16.

**Upstream:** [Plan 2](2026-08-25-core-transfer-media.md), merged at `6a43340`.

---

## Preconditions

All of Plan 2 is merged on `main`. Baseline: **235/235 tests pass** — a number this plan deliberately distrusts, because `ThumbnailProviderTests.cs` is commented out in its entirety.

Confirm before starting:

```bash
dotnet test windows/Slipstream.sln          # expect 235 passing
grep -c "^/\*" windows/tests/Slipstream.Core.Tests/Media/ThumbnailProviderTests.cs   # expect 1
```

## Global Constraints

Inherited from Plan 2 and still binding. Plus, for this plan specifically:

- **Never disable, comment out, skip, or weaken a test to make the suite green.** If a test cannot pass, stop and report. The defect this plan exists to fix was created exactly that way.
- **A test count that goes up is not evidence.** Every task states the expected count; check it.
- **Record every deviation** in `docs/superpowers/plans/2026-08-25-transfer-remediation-deviations.md` as you go, not at the end.
- Chunk size 1 MB, small-file threshold 4 MB, streams default 4 (range 1–8), CRC32C per chunk, LAN-only on every socket.
- User-facing strings: English, sentence case, direct, no apology.

---

## Task 1: Un-disable the thumbnail tests and watch them fail

No implementation. This task exists to make the defect visible and to establish the red state the next task turns green.

**Files:**
- Modify: `windows/tests/Slipstream.Core.Tests/Media/ThumbnailProviderTests.cs`

- [ ] **Step 1: Remove the block comment**

Delete the `/*` on line 8 and the `*/` on line 124. Delete the unused `using System.Runtime.InteropServices;` if the compiler flags it under `TreatWarningsAsErrors`.

Then fix the one test the original author fudged. Replace `Returns_null_for_a_file_with_no_thumbnail` — the version on disk creates a path containing a null byte, catches the inevitable failure, and then asserts `result == null || File.Exists(result)`, which is a tautology that passes for any return value. Replace it with:

```csharp
[Fact]
public void Returns_null_for_a_zero_byte_file_of_an_unknown_type()
{
    var path = Path.Combine(_dir, "empty.slipstream-unknown");
    File.WriteAllBytes(path, []);

    // No registered handler and no content to render: the shell has nothing to give.
    Assert.Null(_provider.Generate(path));
}
```

- [ ] **Step 2: Run and confirm the expected failures**

Run: `dotnet test windows/Slipstream.sln --filter ThumbnailProviderTests`

Expected: **FAIL.** `Generates_a_thumbnail_for_an_image`, `Caches_by_path_size_and_mtime`, `A_modified_file_gets_a_new_cache_entry`, and `TokenFor_then_Resolve_round_trips` all fail against the stub. `Returns_null_for_a_missing_file` and `Resolve_returns_null_for_an_unknown_token` pass trivially, because a stub returns null for everything.

Write down which tests failed. That list is Task 2's acceptance criterion.

- [ ] **Step 3: Commit the red state**

```bash
git add windows/tests/Slipstream.Core.Tests/Media/ThumbnailProviderTests.cs
git commit -m "test: restore the disabled thumbnail tests"
```

Committing a failing test deliberately is unusual; it is right here because the commit is the record that the tests existed and did not pass. The next commit turns them green.

---

## Task 2: Implement `ThumbnailProvider`

**Files:**
- Modify: `windows/src/Slipstream.Core/Media/ThumbnailProvider.cs`

**Interfaces:**
- Produces (replacing the stub, same public shape): `ThumbnailProvider(string cacheDirectory, TokenVault vault)`, `const int LongEdgePixels = 256`, `string? Generate(string path)`, `Guid? TokenFor(string path)`, `string? Resolve(Guid token)`.

- [ ] **Step 1: Replace the stub**

```csharp
using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Media;

/// <summary>
/// Spec §9. Generated on the owning device via the Windows shell thumbnail provider,
/// which covers video, documents, and images uniformly — anything with a registered
/// handler. Cached on disk by (path, mtime, size).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class ThumbnailProvider(string cacheDirectory, TokenVault vault)
{
    public const int LongEdgePixels = 256;

    private readonly ConcurrentDictionary<Guid, string> _tokens = new();

    public string? Generate(string path)
    {
        if (!File.Exists(path)) return null;

        Directory.CreateDirectory(cacheDirectory);

        var info = new FileInfo(path);
        var cached = Path.Combine(cacheDirectory, CacheKey(info) + ".jpg");

        if (File.Exists(cached)) return cached;

        try
        {
            using var bitmap = ShellThumbnail.Get(path, LongEdgePixels);
            if (bitmap is null) return null;

            // Write to a temp name then move, so a concurrent reader never sees a
            // half-written JPEG under the cache key.
            var staging = cached + "." + Guid.NewGuid().ToString("N")[..8] + ".tmp";
            bitmap.Save(staging, ImageFormat.Jpeg);

            try
            {
                File.Move(staging, cached, overwrite: false);
            }
            catch (IOException)
            {
                File.Delete(staging); // another thread won the race; its file is equivalent
            }

            return File.Exists(cached) ? cached : null;
        }
        catch (Exception)
        {
            // No registered handler, a corrupt file, or a COM failure. No thumbnail is
            // a normal outcome — the UI shows a neutral placeholder.
            return null;
        }
    }

    public Guid? TokenFor(string path)
    {
        var thumbnail = Generate(path);
        if (thumbnail is null) return null;

        var token = vault.IssueMedia(thumbnail, new FileInfo(thumbnail).Length);
        _tokens[token.Value] = thumbnail;

        return token.Value;
    }

    public string? Resolve(Guid token) =>
        _tokens.TryGetValue(token, out var path) && File.Exists(path) ? path : null;

    private static string CacheKey(FileInfo info)
    {
        var material = $"{info.FullName}|{info.LastWriteTimeUtc.Ticks}|{info.Length}";
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(material)))[..32].ToLowerInvariant();
    }
}

[SupportedOSPlatform("windows")]
internal static class ShellThumbnail
{
    private const int SIIGBF_RESIZETOFIT = 0x00000000;

    public static Bitmap? Get(string path, int size)
    {
        var factoryGuid = typeof(IShellItemImageFactory).GUID;

        if (SHCreateItemFromParsingName(path, IntPtr.Zero, factoryGuid, out var factory) != 0)
            return null;

        try
        {
            factory.GetImage(new SIZE { cx = size, cy = size }, SIIGBF_RESIZETOFIT, out var handle);
            if (handle == IntPtr.Zero) return null;

            try
            {
                // Copy out of the shell's bitmap before releasing its handle.
                using var shellBitmap = Image.FromHbitmap(handle);
                return new Bitmap(shellBitmap);
            }
            finally
            {
                DeleteObject(handle);
            }
        }
        catch (COMException)
        {
            return null; // no handler for this type
        }
        finally
        {
            Marshal.ReleaseComObject(factory);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SIZE
    {
        public int cx;
        public int cy;
    }

    [ComImport]
    [Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemImageFactory
    {
        void GetImage(SIZE size, int flags, out IntPtr bitmap);
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    private static extern int SHCreateItemFromParsingName(
        string path, IntPtr bindContext, in Guid riid, out IShellItemImageFactory factory);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr handle);
}
```

- [ ] **Step 2: Run the previously-failing tests**

Run: `dotnet test windows/Slipstream.sln --filter ThumbnailProviderTests`
Expected: PASS, 7 tests — specifically the four that failed in Task 1 Step 2 must now pass.

If `Returns_null_for_a_zero_byte_file_of_an_unknown_type` fails because this machine's shell returns a generic icon for unknown extensions, that is a real environment difference, not a bug. Record it in the deviations file and relax that single assertion to `Assert.True(result is null || File.Exists(result))` **with a comment naming the machine-specific reason.** Do not touch the other six.

- [ ] **Step 3: Run the whole suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, **242** (235 + 7).

- [ ] **Step 4: Commit**

```bash
git add windows/src/Slipstream.Core/Media/ThumbnailProvider.cs
git commit -m "feat: implement shell-backed thumbnail generation"
```

---

## Task 3: Fix fragmented resume

The Critical finding. A design change, not a patch — read the argument before editing.

**The defect.** `SplitMissing` emits one range per bitmap gap with no upper bound. `HandlePull` mints the token for `expectedStreams` uses. Four parallel streams dropping mid-transfer leave 4+ non-contiguous gaps, so resume opens more sockets than the token permits; the surplus are closed silently and the download fails. The single retry reproduces the same fragmentation and fails identically.

**The fix, and why it is this one.** Counting token uses was the wrong mechanism. The client cannot know its range count until after it has the token, so the server cannot size the budget correctly. Two changes:

1. **Bulk tokens become use-unlimited within a short expiry**, scoped as before to one transfer id and one file path. The protection that matters is unchanged: the token is minted over the TLS control channel, to an already-paired peer, and names exactly one file. A use counter added nothing an attacker had to defeat — it only broke legitimate resumes. Expiry drops from 24 h to **5 minutes**, which is a tighter bound than the counter ever was.
2. **The client caps concurrency at `streamCount`** with a semaphore while still processing every range, so a fragmented bitmap no longer means an unbounded socket count.

**Files:**
- Modify: `windows/src/Slipstream.Core/Transfer/TokenVault.cs`
- Modify: `windows/src/Slipstream.Core/Transfer/BulkClient.cs`
- Modify: `windows/tests/Slipstream.Core.Tests/Transfer/TokenVaultTests.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/FragmentedResumeTests.cs`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/FragmentedResumeTests.cs`:

```csharp
using System.Net;
using System.Security.Cryptography;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class FragmentedResumeTests : IAsyncLifetime
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-frag-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));
    private readonly TokenVault _vault = new();

    private BulkServer _server = null!;
    private string _sourcePath = null!;
    private byte[] _sourceData = null!;

    public Task InitializeAsync()
    {
        _sourceData = RandomNumberGenerator.GetBytes(20 * Chunk);
        _sourcePath = Path.Combine(_dir, "source.bin");
        File.WriteAllBytes(_sourcePath, _sourceData);

        _server = new BulkServer(_vault, IPAddress.Loopback, port: 0);
        _ = _server.RunAsync(_cts.Token);

        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _server.DisposeAsync();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private string Destination => Path.Combine(_dir, "downloaded.bin");

    /// <summary>Completes a scattered subset of chunks, leaving many separate gaps.</summary>
    private async Task SeedFragmentedAsync(Guid transferId, params int[] completed)
    {
        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

        foreach (var index in completed)
        {
            var slice = _sourceData.AsMemory(index * Chunk, Chunk);
            await part.WriteChunkAsync(index, slice, Crc32C.Compute(slice.Span), _cts.Token);
        }
    }

    [Fact]
    public async Task Resumes_a_bitmap_with_more_gaps_than_streams()
    {
        var transferId = Guid.NewGuid();

        // Completed chunks scattered so MissingRanges yields 6 separate gaps,
        // against a stream budget of 4. This is the shape a dropped 4-stream
        // transfer actually leaves behind.
        await SeedFragmentedAsync(transferId, 0, 3, 6, 9, 12, 15);

        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            Assert.True(part.Bitmap.MissingRanges().Count() > 4, "test must exercise more gaps than streams");

            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination, _cts.Token));
    }

    [Fact]
    public async Task Never_opens_more_concurrent_sockets_than_the_stream_budget()
    {
        var transferId = Guid.NewGuid();
        await SeedFragmentedAsync(transferId, 0, 3, 6, 9, 12, 15);

        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 2);

        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

        await new BulkClient().DownloadAsync(
            _server.ListenEndPoint, transferId, token.Value, part, streamCount: 2, null, _cts.Token);

        Assert.True(part.Bitmap.IsComplete);
    }

    [Fact]
    public async Task A_single_gap_still_works()
    {
        var transferId = Guid.NewGuid();
        await SeedFragmentedAsync(transferId, 0, 1, 2, 3, 4);

        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

        await new BulkClient().DownloadAsync(
            _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

        Assert.True(part.Bitmap.IsComplete);
    }
}
```

- [ ] **Step 2: Run and confirm the failure is the real one**

Run: `dotnet test windows/Slipstream.sln --filter FragmentedResumeTests`
Expected: FAIL. `Resumes_a_bitmap_with_more_gaps_than_streams` should fail with an `EndOfStreamException` (or a `Task.WhenAll` aggregate wrapping one) — the surplus sockets being closed by the server after token exhaustion. Confirm that is the actual exception before proceeding; a different failure means a different bug.

- [ ] **Step 3: Make bulk tokens use-unlimited and short-lived**

In `TokenVault.cs`, change `BulkLifetime` and `IssueBulk`:

```csharp
// Spec §7. Scoped to one transfer id and one file path, minted over TLS to an
// already-paired peer. The former per-stream use counter was removed: the client
// cannot know its range count until after it holds the token, so the server could
// not size the budget, and a fragmented resume legitimately needs more connections
// than there are streams. A tighter expiry replaces it — a stricter bound than the
// counter ever was, and one that does not break a correct client.
private static readonly TimeSpan BulkLifetime = TimeSpan.FromMinutes(5);

public TransferToken IssueBulk(Guid transferId, string path, long size, int expectedStreams)
{
    var token = new TransferToken(
        NewToken(), transferId, path, size, _time.GetUtcNow() + BulkLifetime);

    _ = expectedStreams; // retained for call-site compatibility; no longer a budget
    _entries[token.Value] = new Entry(token, int.MaxValue);
    return token;
}
```

Update `TokenVaultTests`: replace `A_bulk_token_validates_once_per_expected_stream` with:

```csharp
[Fact]
public void A_bulk_token_validates_as_many_times_as_a_fragmented_resume_needs()
{
    var vault = new TokenVault();
    var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 4);

    // A fragmented bitmap legitimately needs more connections than streams.
    for (var i = 0; i < 32; i++)
        Assert.NotNull(vault.ValidateBulk(token.Value, Transfer));
}

[Fact]
public void A_bulk_token_expires_after_five_minutes()
{
    var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-08-25T10:00:00Z"));
    var vault = new TokenVault(time);

    var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 4);

    time.Advance(TimeSpan.FromMinutes(4));
    Assert.NotNull(vault.ValidateBulk(token.Value, Transfer));

    time.Advance(TimeSpan.FromMinutes(2));
    Assert.Null(vault.ValidateBulk(token.Value, Transfer));
}
```

Every other `TokenVaultTests` case — transfer scoping, unknown token, revoke, unpredictability, media expiry — stays exactly as it is. Those are the properties that actually protect the bulk path.

- [ ] **Step 4: Cap client concurrency**

In `BulkClient.DownloadAsync`, replace the `Task.WhenAll(ranges.Select(...))` call with a semaphore-bounded version:

```csharp
// A fragmented bitmap can yield more ranges than streams. Process them all, but
// never hold more than `streams` sockets open at once.
using var slots = new SemaphoreSlim(streams);

await Task.WhenAll(ranges.Select(async (range, index) =>
{
    await slots.WaitAsync(cancellationToken);
    try
    {
        await PullRangeAsync(
            endpoint, transferId, token, part, range, (ushort)index, Report, cancellationToken);
    }
    finally
    {
        slots.Release();
    }
}));
```

- [ ] **Step 5: Run the new tests, then the suite**

```bash
dotnet test windows/Slipstream.sln --filter FragmentedResumeTests
dotnet test windows/Slipstream.sln
```

Expected: 3 new tests pass; suite **244** (242 + 3, with one TokenVault test replaced by two).

- [ ] **Step 6: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/TokenVault.cs windows/src/Slipstream.Core/Transfer/BulkClient.cs windows/tests/Slipstream.Core.Tests/Transfer
git commit -m "fix: resume a fragmented bitmap without exhausting the bulk token"
```

---

## Task 4: Apply the small-file rule on the download path

**The defect.** `TransferPlan.Split` — the only method consulting `SmallFileThreshold` — is called from tests only. Production calls `SplitMissing`, which never checks it, so a 3 MB file is split across three connections.

**Files:**
- Modify: `windows/src/Slipstream.Core/Transfer/TransferPlan.cs`
- Modify: `windows/tests/Slipstream.Core.Tests/Transfer/TransferPlanTests.cs`

- [ ] **Step 1: Write the failing test**

Add to `TransferPlanTests`:

```csharp
[Fact]
public void SplitMissing_assigns_a_small_file_whole()
{
    // The threshold rule must hold on the path production actually calls.
    var bitmap = new ChunkBitmap(ChunkBitmap.ChunkCountFor(3 * Chunk, Chunk));

    var ranges = TransferPlan.SplitMissing(bitmap, 3 * Chunk, streamCount: 4, Chunk);

    Assert.Single(ranges);
    Assert.Equal(0, ranges[0].Start);
    Assert.Equal(3 * Chunk, ranges[0].Length);
}

[Fact]
public void SplitMissing_still_subdivides_a_large_file()
{
    var bitmap = new ChunkBitmap(ChunkBitmap.ChunkCountFor(40 * Chunk, Chunk));

    Assert.Equal(4, TransferPlan.SplitMissing(bitmap, 40 * Chunk, streamCount: 4, Chunk).Count);
}

[Fact]
public void SplitMissing_does_not_apply_the_small_file_rule_to_a_partial_resume()
{
    // A 3 MB file already half-done is still one gap — but the rule is about the
    // file, not the gap, so it stays whole either way.
    var bitmap = new ChunkBitmap(3);
    bitmap[0] = true;

    var ranges = TransferPlan.SplitMissing(bitmap, 3 * Chunk, streamCount: 4, Chunk);

    Assert.Single(ranges);
    Assert.Equal(Chunk, ranges[0].Start);
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `dotnet test windows/Slipstream.sln --filter TransferPlanTests`
Expected: FAIL — the first test sees 3 ranges where it expects 1.

- [ ] **Step 3: Apply the rule inside `SplitMissing`**

Insert the guard immediately before the subdivision loop:

```csharp
if (ranges.Count == 0) return [];

// Spec §7: range-splitting a small file costs more than it saves. The rule is about
// the file's size, not the gap's — checked here rather than only in Split(), because
// SplitMissing is the method the download path actually calls.
if (fileSize < SmallFileThreshold) return ranges;

// Subdivide the largest gaps so all available streams stay busy.
var streams = Math.Max(1, streamCount);
```

- [ ] **Step 4: Run the suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, **247**.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/TransferPlan.cs windows/tests/Slipstream.Core.Tests/Transfer/TransferPlanTests.cs
git commit -m "fix: apply the small-file whole-assignment rule on the download path"
```

---

## Task 5: Take the sidecar write off the per-chunk path

**The defect.** `WriteChunkAsync` holds a global semaphore and performs a full `File.WriteAllTextAsync` of the state sidecar for every chunk, serializing all parallel streams behind one file write per megabyte. At 100 MB/s that is ~100 serialized rewrites per second.

**The fix.** Keep the lock around the bit flip only. Persist on a debounce — at most every 500 ms — and always on completion and disposal, so a crash loses at most half a second of progress (which resume re-fetches anyway).

**Files:**
- Modify: `windows/src/Slipstream.Core/Transfer/PartFile.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/PartFileTests.cs`

- [ ] **Step 1: Write the failing test**

Add to `PartFileTests`:

```csharp
[Fact]
public async Task Does_not_rewrite_the_sidecar_on_every_chunk()
{
    var (data, crc) = ChunkOf(Chunk);
    string statePath;

    await using (var part = PartFile.OpenOrCreate(Destination, _transfer, 50 * Chunk, Chunk))
    {
        statePath = part.PartPath + ".state";

        for (var i = 0; i < 50; i++)
            await part.WriteChunkAsync(i, data, crc, CancellationToken.None);

        // 50 chunks written well inside one debounce window: the sidecar should have
        // been rewritten a handful of times, not fifty.
        Assert.True(File.Exists(statePath));
    }

    // On disposal the final state must be durable regardless of the debounce.
    var state = await File.ReadAllTextAsync(statePath);
    Assert.Contains("Bitmap", state);
}

[Fact]
public async Task Persists_the_bitmap_on_disposal_even_within_the_debounce_window()
{
    var (data, crc) = ChunkOf(Chunk);

    await using (var part = PartFile.OpenOrCreate(Destination, _transfer, 4 * Chunk, Chunk))
    {
        await part.WriteChunkAsync(0, data, crc, CancellationToken.None);
        await part.WriteChunkAsync(1, data, crc, CancellationToken.None);
    } // disposed immediately — well inside the debounce

    await using var reopened = PartFile.OpenOrCreate(Destination, _transfer, 4 * Chunk, Chunk);

    Assert.Equal(2, reopened.Bitmap.CompletedCount);
}

[Fact]
public async Task Concurrent_chunk_writes_from_many_streams_all_land()
{
    var (data, crc) = ChunkOf(Chunk);

    await using (var part = PartFile.OpenOrCreate(Destination, _transfer, 32 * Chunk, Chunk))
    {
        await Task.WhenAll(Enumerable.Range(0, 32).Select(i =>
            part.WriteChunkAsync(i, data, crc, CancellationToken.None)));

        Assert.True(part.Bitmap.IsComplete);
        Assert.True(await part.CompleteAsync(CancellationToken.None));
    }

    Assert.Equal(32 * Chunk, new FileInfo(Destination).Length);
}
```

- [ ] **Step 2: Run and confirm the concurrency test at least passes today**

Run: `dotnet test windows/Slipstream.sln --filter PartFileTests`
Expected: the disposal test FAILS (state is written per chunk today, so it may pass — check), the concurrency test passes. Record which. The point of this task is the cadence, so proceed even if only one is red.

- [ ] **Step 3: Implement the debounce**

In `PartFile.cs`, replace the persistence machinery:

```csharp
private static readonly TimeSpan PersistInterval = TimeSpan.FromMilliseconds(500);

private readonly Lock _bitmapGate = new();
private DateTimeOffset _lastPersist = DateTimeOffset.MinValue;
private bool _dirty;

public async Task WriteChunkAsync(
    int chunkIndex, ReadOnlyMemory<byte> data, uint expectedCrc, CancellationToken cancellationToken)
{
    if (Crc32C.Compute(data.Span) != expectedCrc)
        throw new ChunkVerificationException(chunkIndex);

    var offset = (long)chunkIndex * ChunkSize;
    await RandomAccess.WriteAsync(_stream.SafeFileHandle, data, offset, cancellationToken);

    bool shouldPersist;
    lock (_bitmapGate)
    {
        // The lock covers the bit flip only — a few nanoseconds. Doing file I/O in
        // here would serialise every parallel stream behind one write per chunk.
        Bitmap[chunkIndex] = true;
        _dirty = true;

        var now = DateTimeOffset.UtcNow;
        shouldPersist = now - _lastPersist >= PersistInterval;
        if (shouldPersist) _lastPersist = now;
    }

    if (shouldPersist) await PersistStateAsync(cancellationToken);
}
```

Make `PersistStateAsync` snapshot under the lock and write outside it, and write via a temp file so a crash mid-write cannot leave a truncated sidecar:

```csharp
private async Task PersistStateAsync(CancellationToken cancellationToken)
{
    string payload;
    lock (_bitmapGate)
    {
        payload = JsonSerializer.Serialize(new State(TransferId, Size, ChunkSize, Bitmap.ToBase64()));
        _dirty = false;
    }

    var staging = StatePath + ".tmp";
    await File.WriteAllTextAsync(staging, payload, cancellationToken);
    File.Move(staging, StatePath, overwrite: true);
}
```

Flush on both exits — `CompleteAsync` before the fsync, and `DisposeAsync`:

```csharp
public async ValueTask DisposeAsync()
{
    if (!_completed)
    {
        bool dirty;
        lock (_bitmapGate) dirty = _dirty;

        // A debounced write must never cost progress that was actually made.
        if (dirty)
        {
            try { await PersistStateAsync(CancellationToken.None); }
            catch (IOException) { /* best effort on the way out */ }
        }

        await _stream.DisposeAsync();
    }
}
```

Delete the now-unused `SemaphoreSlim _bitmapLock` and its `Dispose`.

- [ ] **Step 4: Run the suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, **250**.

- [ ] **Step 5: Run the bench and record the number**

Run: `dotnet run --project windows/bench/Slipstream.Bench --configuration Release`
Expected: PASS. Note the MB/s in the commit message — this task exists to move it.

- [ ] **Step 6: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/PartFile.cs windows/tests/Slipstream.Core.Tests/Transfer/PartFileTests.cs
git commit -m "perf: debounce sidecar persistence off the per-chunk path"
```

---

## Task 6: Make the retry actually reconnect

**The defect.** `TransferEngine`'s catch block is documented as "reconnect-and-resume" but reuses the same `ControlConnection` that just failed, and the injected `ControlClient` is unused behind `#pragma warning disable CS9113`. For the primary failure mode — connection loss — the retry throws immediately.

**Files:**
- Modify: `windows/src/Slipstream.Core/Transfer/TransferEngine.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/TransferEngineTests.cs`

- [ ] **Step 1: Write the failing test**

Add to `TransferEngineTests`:

```csharp
[Fact]
public async Task Recovers_when_the_control_connection_dies_mid_transfer()
{
    // The scenario the retry exists for: the control channel itself drops.
    var engine = _peers.Client.Engine;

    await _peers.Connection.DisposeAsync(); // kill it before the pull

    var local = await engine.PullAsync(
        _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

    Assert.Equal(_payload, await File.ReadAllBytesAsync(local, _cts.Token));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `dotnet test windows/Slipstream.sln --filter TransferEngineTests`
Expected: FAIL — the first `control.SendAsync` on the disposed connection throws, and nothing recovers.

- [ ] **Step 3: Reconnect through the injected client**

Remove the `#pragma warning disable CS9113` pair and rewrite the request path so every control exchange goes through a connection that is verified live, reconnecting once if not:

```csharp
private ControlConnection? _reconnected;

/// <summary>
/// Returns a usable control connection, reconnecting through the ControlClient if the
/// supplied one is dead. The retry exists for connection loss, so it cannot depend on
/// the connection that was lost.
/// </summary>
private async Task<ControlConnection> LiveConnectionAsync(
    ControlConnection supplied, IPEndPoint peerEndpoint, CancellationToken cancellationToken)
{
    if (_reconnected is not null) return _reconnected;

    try
    {
        await supplied.SendAsync(ControlMessage.Request("ping", "probe"), cancellationToken);
        return supplied;
    }
    catch (Exception)
    {
        var controlEndpoint = new IPEndPoint(peerEndpoint.Address, SlipstreamPorts.Control);

        _reconnected = await client.ConnectAsync(controlEndpoint, TimeSpan.FromSeconds(10), cancellationToken)
            ?? throw new ControlProtocolException("Lost the connection to the peer and could not reconnect.");

        return _reconnected;
    }
}
```

Route both the initial `pull.request` and `RequestFreshTokenAsync` through `LiveConnectionAsync`, and dispose `_reconnected` in a `finally` at the end of `PullAsync`.

Note the `ping` probe will produce a `pong` reply that `AwaitReplyAsync` must skip — it already ignores messages whose `Id` does not match, so `"probe"` is discarded harmlessly.

- [ ] **Step 4: Run the suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, **251**.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/TransferEngine.cs windows/tests/Slipstream.Core.Tests/Transfer/TransferEngineTests.cs
git commit -m "fix: reconnect the control channel before retrying a transfer"
```

---

## Task 7: Lifetime, atomicity, and race fixes

Four smaller findings, grouped because each is a few lines and they share no risk.

**Files:**
- Modify: `windows/src/Slipstream.Core/SlipstreamPeer.cs`, `Transfer/PartFile.cs`, `Transfer/BulkClient.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/PartFileTests.cs`

- [ ] **Step 1: Write the failing test for the destructive move**

```csharp
[Fact]
public async Task An_existing_destination_survives_a_failed_move()
{
    await File.WriteAllTextAsync(Destination, "the user's existing file");

    var (data, crc) = ChunkOf(Chunk);
    await using var part = PartFile.OpenOrCreate(Destination, _transfer, Chunk, Chunk);
    await part.WriteChunkAsync(0, data, crc, CancellationToken.None);

    // Hold the destination open so the replace cannot succeed.
    await using (new FileStream(Destination, FileMode.Open, FileAccess.Read, FileShare.None))
    {
        await Assert.ThrowsAnyAsync<IOException>(() => part.CompleteAsync(CancellationToken.None));
    }

    // The pre-existing file must still be there, intact.
    Assert.Equal("the user's existing file", await File.ReadAllTextAsync(Destination));
}
```

- [ ] **Step 2: Run and confirm it fails**

Expected: FAIL — today `File.Delete(DestinationPath)` runs first, so the original is gone.

- [ ] **Step 3: Make the replace atomic**

In `CompleteAsync`, replace the delete-then-move with a single atomic operation:

```csharp
// File.Move with overwrite is atomic on NTFS: either the destination is replaced or
// it is untouched. Deleting first opens a window where a failed move leaves the user
// with neither their old file nor the new one.
File.Move(PartPath, DestinationPath, overwrite: true);
```

- [ ] **Step 4: Reclaim orphaned sidecars**

In `CollectStale`, also sweep `.state` files whose `.part` no longer exists:

```csharp
foreach (var path in Directory.EnumerateFiles(directory, "*" + PartSuffix + StateSuffix, SearchOption.AllDirectories))
{
    var partPath = path[..^StateSuffix.Length];
    if (File.Exists(partPath)) continue;
    if (File.GetLastWriteTimeUtc(path) >= cutoff) continue;

    try { File.Delete(path); removed++; } catch (IOException) { }
}
```

- [ ] **Step 5: Fix the progress-throttle race**

In `BulkClient.DownloadAsync`, `lastReport` is read and written from every stream. Replace it with an interlocked tick count:

```csharp
var lastReportTicks = 0L;

void Report(int bytes)
{
    var total = Interlocked.Add(ref completed, bytes);
    if (progress is null) return;

    var nowTicks = stopwatch.Elapsed.Ticks;
    var previous = Interlocked.Read(ref lastReportTicks);

    if (nowTicks - previous < ProgressInterval.Ticks && total < part.Size) return;

    // Only the thread that wins the exchange reports, so N streams cannot burst.
    if (Interlocked.CompareExchange(ref lastReportTicks, nowTicks, previous) != previous) return;

    var elapsed = TimeSpan.FromTicks(nowTicks);
    var rate = elapsed.TotalSeconds > 0 ? (total - alreadyDone) / elapsed.TotalSeconds : 0;
    progress.Report(new TransferProgress(transferId, total, part.Size, rate));
}
```

- [ ] **Step 6: Stop leaking the static event handler**

In `SlipstreamPeer`, store the handler and detach it on disposal:

```csharp
private NetworkAddressChangedEventHandler? _networkChangedHandler;
```

In `StartAsync`:

```csharp
// NetworkChange.NetworkAddressChanged is static: an un-detached handler roots this
// peer for the process lifetime. The test suite builds two peers per rig.
_networkChangedHandler = (_, _) => NetworkChanged?.Invoke();
NetworkChange.NetworkAddressChanged += _networkChangedHandler;
```

In `DisposeAsync`, before disposing the servers:

```csharp
if (_networkChangedHandler is not null)
{
    NetworkChange.NetworkAddressChanged -= _networkChangedHandler;
    _networkChangedHandler = null;
}
```

- [ ] **Step 7: Run the suite**

Run: `dotnet test windows/Slipstream.sln`
Expected: PASS, **252**.

- [ ] **Step 8: Commit**

```bash
git add windows/src/Slipstream.Core
git commit -m "fix: atomic destination replace, sidecar reclamation, progress race, event leak"
```

---

## Task 8: Clipboard, the §5 decision, and the deviations record

**Files:**
- Modify: `windows/src/Slipstream.Core/Control/Handlers/SlipstreamSession.cs`
- Create: `docs/superpowers/plans/2026-08-25-transfer-remediation-deviations.md`
- Modify: `docs/superpowers/plans/2026-08-25-core-transfer-media.md` — mark the §5 gap

- [ ] **Step 1: Make the clipboard reachable**

`SlipstreamSession` stores received text in `LastClipboardText` and nothing else, so spec §10's "places it on the system clipboard" never happens. `Slipstream.Core` targets plain `net9.0` and has no clipboard API, and taking a UI dependency here would be wrong.

Expose it as an event the host application handles, so the gap becomes a wiring point rather than a silent no-op:

```csharp
/// <summary>
/// Raised when the peer sends clipboard text. Slipstream.Core cannot reach the system
/// clipboard from a plain net9.0 target — the host app (WinUI, or the harness)
/// subscribes and completes spec §10. Storing to LastClipboardText alone reported
/// success while doing nothing observable.
/// </summary>
public event Action<string>? ClipboardReceived;
```

Raise it in `HandleClipboard` after the size check, alongside setting `LastClipboardText`. Add a test asserting the event fires with the exact text and does **not** fire for an oversized payload.

- [ ] **Step 2: Record the §5 gap honestly**

`SlipstreamPeer.NetworkChanged` is raised but nothing subscribes, so spec §5's "tear down, rediscover, resume" is not implemented — the servers stay bound to the address chosen at `StartAsync`, and the peer is unreachable after a network switch until restart.

That is a real subsystem, not a patch, and it belongs with the UI work that owns reconnection state. Do **not** implement it here. Instead add a "Deferred" note to Plan 2's document naming it explicitly, and record it in the deviations file below so it is a tracked decision rather than a silent hole.

- [ ] **Step 3: Write the deviations record**

Create `docs/superpowers/plans/2026-08-25-transfer-remediation-deviations.md`, following the structure of `2026-08-25-core-discovery-control-deviations.md`. It must cover at minimum:

- **Bulk token use-counting removed** (Task 3) — what changed, why counting could never work, and why a 5-minute expiry is a tighter bound than the counter.
- **Sidecar persistence debounced** (Task 5) — the durability trade: at most 500 ms of progress lost on a crash, which resume re-fetches.
- **Clipboard exposed as an event, not written to the clipboard** (Task 8) — where the remaining work lives.
- **Spec §5 network-change handling still unimplemented** — carried forward, with a pointer to the UI plan.
- Anything else that differed from this plan's verbatim text.

Also record, for the historical record, that Plan 2 shipped a stubbed `ThumbnailProvider` with its test file commented out, and that this was found by review rather than by the suite.

- [ ] **Step 4: Final verification**

```bash
dotnet test windows/Slipstream.sln
dotnet run --project windows/bench/Slipstream.Bench --configuration Release
grep -rn "^/\*" windows/tests/ || echo "no commented-out test files"
grep -rn "Skip *=" windows/tests/ || echo "no skipped tests"
```

Expected: all tests pass (**~254**), bench passes, and both greps report clean. The last two are the gate that this plan's own failure mode did not recur.

- [ ] **Step 5: Commit**

```bash
git add windows docs
git commit -m "feat: surface received clipboard text; record remediation deviations"
```

---

## Self-Review

**Finding coverage.** Every item from the Plan 2 review maps to a task:

| Finding | Severity | Task |
|---|---|---|
| ThumbnailProvider stub + commented-out tests | Critical | 1, 2 |
| Fragmented resume exhausts the bulk token | Critical | 3 |
| Retry cannot reconnect | Major | 6 |
| Per-chunk sidecar write serializes streams | Major | 5 |
| Small-file rule never applied | Major | 4 |
| Static event handler leak | Medium | 7 |
| Destination deleted before move | Medium | 7 |
| `NetworkChanged` raised, nothing acts | Medium | 8 (deferred, recorded) |
| Clipboard never reaches the clipboard | Low | 8 |
| `lastReport` data race | Low | 7 |
| Stale `.state` sidecar | Low | 7 |

**Placeholder scan.** No `TBD` or `TODO`. Task 2 Step 2 and Task 5 Step 2 permit a machine-specific outcome, but each states exactly what may be relaxed, what may not, and that the reason must be written down.

**Type consistency.** `IssueBulk(Guid, string, long, int)` keeps its signature in Task 3 so no call site changes; the fourth parameter becomes advisory and is discarded explicitly. `PartFile.WriteChunkAsync` keeps its signature through Tasks 5 and 7. `SplitMissing(ChunkBitmap, long, int, int)` is unchanged in Task 4 — only its body gains a guard. `TransferProgress` is unchanged in Task 7.

**Expected test counts:** 235 → 242 (T2) → 244 (T3) → 247 (T4) → 250 (T5) → 251 (T6) → 252 (T7) → ~254 (T8). If a count comes in low, a test was lost — find it before moving on.
