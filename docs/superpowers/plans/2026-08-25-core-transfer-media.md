# Slipstream Core: Transfer Engine & Media Server — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move files between paired peers at the maximum rate the link allows, resume across network switches, browse the peer's filesystem, and stream media over seekable HTTP.

**Architecture:** Bulk transfer is a purpose-built framed protocol on 53322 — separate from the TLS control channel, plaintext, and authenticated by single-use tokens the control channel issues. Files are split into disjoint byte ranges pulled by N concurrent streams; a persisted chunk bitmap makes any interruption resumable. Media streams over a separate HTTP/1.1 server on 53323 with `Range` support, so seeking is instant and nothing lands on the receiver's disk.

**Tech Stack:** .NET 9, C# 13, xUnit. `System.Runtime.Intrinsics` for hardware CRC32C. No third-party runtime packages.

**Spec:** [`docs/superpowers/specs/2026-08-25-slipstream-design.md`](../specs/2026-08-25-slipstream-design.md) — §6 (control messages), §7 (transfer engine), §8 (media streaming), §9 (thumbnails), §10 (clipboard), §16 (throughput expectations).

**Upstream plan:** [`2026-08-25-core-discovery-control.md`](2026-08-25-core-discovery-control.md) (Plan 1).

---

## Preconditions — interfaces assumed from Plan 1

Verify these exist with these signatures before starting. If Plan 1 landed differently, reconcile **here**, in this block, and adjust the affected tasks — do not patch names task by task.

```csharp
// Slipstream.Core
static class SlipstreamPorts { const int Discovery, Control, Bulk, Media; const int ProtocolVersion; static IPAddress MulticastGroup; }
sealed class SlipstreamPeer : IAsyncDisposable {
    DeviceIdentity Identity { get; }
    PairedPeerStore Peers { get; }
    ControlClient Client { get; }
    LocalNetwork? Network { get; }
    ControlServer Server { get; }
    Task StartAsync(CancellationToken);
    Task<DiscoveryResult?> FindPeerAsync(TimeSpan, CancellationToken);
}

// Slipstream.Core.Net
static class LanGuard { static bool IsLocal(IPAddress); static void EnsureLocal(IPAddress); }
sealed record LocalNetwork(IPAddress LocalAddress, IPAddress? Gateway, int PrefixLength, string Key);
interface INetworkInfo { LocalNetwork? Current(); }

// Slipstream.Core.Identity
sealed class DeviceIdentity { string DeviceId; string DisplayName; X509Certificate2 Certificate; string Fingerprint; }
sealed class PairedPeerStore { PairedPeer? Current; bool IsPaired; bool Trusts(string fingerprint); }

// Slipstream.Core.Control
sealed class ControlMessage {
    string Type { get; init; } string? Id { get; init; } JsonElement? Payload { get; init; }
    static ControlMessage Request(string type, string id, object? payload = null);
    static ControlMessage Response(string type, string id, object? payload = null);
    static ControlMessage Event(string type, object? payload = null);
    T? PayloadAs<T>();
}
sealed class ControlConnection : IAsyncDisposable {
    string PeerFingerprint { get; } IPEndPoint RemoteEndPoint { get; }
    Task SendAsync(ControlMessage, CancellationToken);
    Task<ControlMessage?> ReceiveAsync(CancellationToken);
}
sealed class ControlServer : IAsyncDisposable {
    IPEndPoint ListenEndPoint { get; }
    event Func<ControlConnection, CancellationToken, Task>? PeerConnected;
    Task RunAsync(CancellationToken);
}
sealed class ControlClient { Task<ControlConnection?> ConnectAsync(IPEndPoint, TimeSpan, CancellationToken); }
sealed class ControlProtocolException : Exception { ControlProtocolException(string message); }
```

Also assumed present: `protocol/protocol.md`, `protocol/vectors/pairing-codes.json`, `protocol/vectors/announcements.json`.

## Global Constraints

Inherited from Plan 1 and still binding. Values copied verbatim from the spec.

- **Ports:** 53322/TCP bulk (plaintext, token-authed), 53323/TCP HTTP media (token-authed).
- **Protocol version:** `1`.
- **Parallel streams:** default **4**, user-configurable **1–8**.
- **Chunk size:** 1 MB (`1_048_576` bytes).
- **Small-file threshold:** files below **4 MB** are assigned whole to one stream, never range-split.
- **Socket tuning:** `SO_SNDBUF` / `SO_RCVBUF` = 4 MB, `TCP_NODELAY` enabled.
- **Destination file is preallocated** to full size before the first byte. **One `fsync` at completion**, never per chunk.
- **Integrity:** CRC32C per chunk. A failing chunk is re-requested, not fatal. **No whole-file hash** — it would require a second full read for no additional guarantee.
- **Resume:** sparse chunk bitmap persisted beside a `.part` file. `.part` files older than **7 days** with no matching peer state are garbage-collected.
- **Bulk tokens:** single-use, scoped to one transfer ID, issued only over the TLS control channel.
- **Media tokens:** expire **12 hours** after issue **or on app restart**, whichever is first.
- **Thumbnails:** JPEG, **256px on the long edge**, cached by `hash(path, mtime, size)`. Listings carry a thumbnail *token*, never inline image data.
- **Directory listings cap at 5000 entries** with a `truncated` flag. No deeper paging.
- **Clipboard payload cap:** 64 KB UTF-8.
- **Transfer progress events are throttled to ~4/s.**
- **LAN-only:** `LanGuard` applies to every inbound and outbound socket on 53322 and 53323, same as 53321.
- **Unknown control message types are ignored, never fatal.**
- **All user-facing strings:** English, sentence case, no ALL CAPS, no apology (spec §15).

### One deliberate amendment to Plan 1's dependency rule

Plan 1 said "no third-party runtime dependencies". That stands. CRC32C is implemented in Task 3 using `System.Runtime.Intrinsics` (BCL, no package) rather than the `System.IO.Hashing` NuGet package — which in any case ships CRC-32, not CRC-32C. Hardware acceleration comes from the `Sse42` and `Arm.Crc32` intrinsics with a table-driven software fallback.

---

## File Structure

```
protocol/
  bulk-format.md                    # normative wire spec, frozen in Task 1
  vectors/bulk-headers.json
  vectors/chunk-bitmaps.json
  vectors/crc32c.json
windows/src/Slipstream.Core/
  Transfer/BulkFrameHeader.cs       # 64-byte header codec
  Transfer/Crc32C.cs                # hardware-accelerated, BCL only
  Transfer/TransferToken.cs         # single-use, transfer-scoped
  Transfer/TokenVault.cs            # issue + validate + expire
  Transfer/ChunkBitmap.cs           # sparse completed-chunk set
  Transfer/PartFile.cs              # .part sidecar + preallocation + fsync
  Transfer/TransferPlan.cs          # range splitting, small-file whole assignment
  Transfer/BulkServer.cs            # serves ranges on 53322
  Transfer/BulkClient.cs            # N parallel range streams
  Transfer/TransferEngine.cs        # orchestration, progress, resume
  Transfer/FolderExpander.cs        # tree -> flat relative-path list
  Files/FileBrowser.cs              # list/stat with the 5000 cap
  Media/MediaServer.cs              # HTTP/1.1 + Range on 53323
  Media/RangeHeader.cs              # RFC 7233 parsing
  Media/ThumbnailProvider.cs        # IShellItemImageFactory + cache
  Control/Handlers/BrowseHandler.cs
  Control/Handlers/TransferHandler.cs
  Control/Handlers/PlayHandler.cs
  Control/Handlers/ClipboardHandler.cs
  Platform/PlaylistLauncher.cs      # the ShellExecute-opens-a-browser fix
windows/tests/Slipstream.Core.Tests/…
windows/bench/Slipstream.Bench/     # throughput regression gate
```

---

## Task 1: Freeze the bulk wire format and publish its vectors

**No implementation in this task.** Its only output is a normative document and fixture files — which is precisely why it comes first: it unblocks the Android transfer engine (Plan 3b) without waiting for the remaining fifteen tasks here.

**Files:**
- Create: `protocol/bulk-format.md`
- Create: `protocol/vectors/bulk-headers.json`
- Create: `protocol/vectors/chunk-bitmaps.json`
- Create: `protocol/vectors/crc32c.json`
- Modify: `protocol/protocol.md` — replace the "implemented in Plan 2" placeholder for the bulk path with a link to `bulk-format.md`

**Interfaces:**
- Consumes: nothing.
- Produces: the normative byte layout every later task and Plan 3b implements against.

- [ ] **Step 1: Write the wire format document**

Create `protocol/bulk-format.md`:

````markdown
# Slipstream bulk transfer wire format

Version 1. Port 53322/TCP, plaintext. All multi-byte integers are **big-endian**.

## Stream header — exactly 64 bytes

Sent by the *initiating* side immediately after connecting, before any payload.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 4 | magic | ASCII `SLPS` = `0x53 0x4C 0x50 0x53` |
| 4 | 2 | version | `1` |
| 6 | 2 | streamIndex | 0-based index within this transfer |
| 8 | 16 | token | raw bytes of the single-use bulk token |
| 24 | 16 | transferId | raw bytes |
| 40 | 8 | rangeStart | inclusive byte offset within the file |
| 48 | 8 | rangeLength | number of bytes to transfer |
| 56 | 4 | chunkSize | bytes per chunk; `1048576` in version 1 |
| 60 | 4 | reserved | must be zero; receivers ignore |

A header failing magic, version, or token validation is answered by closing the
socket with no reply. There is no error frame — an unauthenticated peer learns nothing.

## Payload framing

The range is sent as consecutive chunks. For each chunk:

| Size | Field |
|---|---|
| 4 | chunkLength (bytes of data that follow; ≤ chunkSize) |
| n | chunk data |
| 4 | crc32c of the chunk data |

The final chunk of a range may be shorter than `chunkSize`. A chunk whose CRC does
not match is re-requested on a fresh stream; it does not fail the transfer.

`chunkIndex` is not transmitted — it is derived: `(rangeStart / chunkSize) + ordinal`.
Ranges always start on a chunk boundary, which makes this unambiguous.

## Chunk bitmap exchange

Sent over the **control** channel, not this port. A bitmap is a little-endian bit
array, one bit per chunk, bit set = chunk complete and CRC-verified. It is
base64-encoded for JSON transport. Bit *i* corresponds to chunk index *i*.
Trailing bits beyond the file's chunk count are zero and ignored.

Chunk count = `ceil(fileSize / chunkSize)`. Bitmap byte length = `ceil(chunkCount / 8)`.

## Range assignment

- Files **< 4 MB** are assigned whole to a single stream: one range covering `[0, size)`.
- Larger files are split into `min(streamCount, chunkCount)` contiguous ranges, each
  starting on a chunk boundary. Remainder chunks go to the earliest ranges, so range
  lengths differ by at most one chunk.
````

- [ ] **Step 2: Write the header vectors**

Create `protocol/vectors/bulk-headers.json`. `bytes` is the 64-byte header as lowercase hex:

```json
{
  "description": "Bulk stream headers. Big-endian. 64 bytes exactly.",
  "cases": [
    {
      "name": "first-stream-1mb-range",
      "fields": {
        "version": 1, "streamIndex": 0,
        "token": "000102030405060708090a0b0c0d0e0f",
        "transferId": "101112131415161718191a1b1c1d1e1f",
        "rangeStart": 0, "rangeLength": 1048576, "chunkSize": 1048576
      },
      "bytes": "534c5053000100000001020304050607_08090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f000000000000000000000000001000000010000000000000"
    },
    {
      "name": "third-stream-offset-range",
      "fields": {
        "version": 1, "streamIndex": 2,
        "token": "ffeeddccbbaa99887766554433221100",
        "transferId": "0f0e0d0c0b0a09080706050403020100",
        "rangeStart": 2097152, "rangeLength": 3145728, "chunkSize": 1048576
      },
      "bytes": "PENDING"
    }
  ]
}
```

The `bytes` values are filled in from the implementation in Task 2 Step 5. The
underscore in the first case is a readability separator that the loader strips —
see the test in Task 2. Keep it, it makes a 128-character hex string reviewable.

- [ ] **Step 3: Write the bitmap vectors**

Create `protocol/vectors/chunk-bitmaps.json`:

```json
{
  "description": "Chunk bitmaps. Little-endian bit order: bit i of byte n is chunk (n*8 + i).",
  "cases": [
    { "name": "empty-10-chunks", "chunkCount": 10, "complete": [], "base64": "AAA=" },
    { "name": "first-chunk-only", "chunkCount": 10, "complete": [0], "base64": "AQA=" },
    { "name": "chunks-0-and-9", "chunkCount": 10, "complete": [0, 9], "base64": "AQI=" },
    { "name": "all-10", "chunkCount": 10, "complete": [0,1,2,3,4,5,6,7,8,9], "base64": "/wM=" },
    { "name": "single-chunk-file", "chunkCount": 1, "complete": [0], "base64": "AQ==" }
  ]
}
```

- [ ] **Step 4: Write the CRC32C vectors**

Create `protocol/vectors/crc32c.json`. These are the standard Castagnoli test values, so a wrong polynomial is caught immediately rather than at integration:

```json
{
  "description": "CRC-32C (Castagnoli, polynomial 0x1EDC6F41), reflected, init 0xFFFFFFFF, final xor 0xFFFFFFFF.",
  "cases": [
    { "input_utf8": "", "crc_hex": "00000000" },
    { "input_utf8": "a", "crc_hex": "c1d04330" },
    { "input_utf8": "123456789", "crc_hex": "e3069283" },
    { "input_utf8": "The quick brown fox jumps over the lazy dog", "crc_hex": "22620404" }
  ]
}
```

- [ ] **Step 5: Update the protocol index**

In `protocol/protocol.md`, replace the line marking bulk transfer as "implemented in Plan 2" with:

```markdown
- **Bulk transfer (53322):** see [`bulk-format.md`](bulk-format.md). Normative.
```

- [ ] **Step 6: Commit and notify the Android track**

```bash
git add protocol
git commit -m "docs: freeze the bulk transfer wire format and publish vectors"
```

This commit is the signal that Plan 3b may start. Nothing in it depends on the rest of this plan.

---

## Task 2: The 64-byte stream header codec

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/BulkFrameHeader.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/BulkFrameHeaderTests.cs`

**Interfaces:**
- Consumes: `protocol/vectors/bulk-headers.json`.
- Produces:
  - `readonly record struct BulkFrameHeader(ushort Version, ushort StreamIndex, Guid Token, Guid TransferId, long RangeStart, long RangeLength, int ChunkSize)`
  - `const int Size = 64`
  - `void WriteTo(Span<byte> destination)`
  - `static bool TryRead(ReadOnlySpan<byte> source, out BulkFrameHeader header)` — false on bad magic, wrong version, non-positive chunk size, or negative range values.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/BulkFrameHeaderTests.cs`:

```csharp
using System.Text.Json;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class BulkFrameHeaderTests
{
    private static BulkFrameHeader Sample() => new(
        Version: 1,
        StreamIndex: 2,
        Token: new Guid("ffeeddcc-bbaa-9988-7766-554433221100"),
        TransferId: new Guid("0f0e0d0c-0b0a-0908-0706-050403020100"),
        RangeStart: 2 * 1024 * 1024,
        RangeLength: 3 * 1024 * 1024,
        ChunkSize: 1024 * 1024);

    [Fact]
    public void Occupies_exactly_64_bytes()
    {
        Assert.Equal(64, BulkFrameHeader.Size);

        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);
    }

    [Fact]
    public void Round_trips()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);

        Assert.True(BulkFrameHeader.TryRead(buffer, out var parsed));
        Assert.Equal(Sample(), parsed);
    }

    [Fact]
    public void Starts_with_the_SLPS_magic()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);

        Assert.Equal((byte)'S', buffer[0]);
        Assert.Equal((byte)'L', buffer[1]);
        Assert.Equal((byte)'P', buffer[2]);
        Assert.Equal((byte)'S', buffer[3]);
    }

    [Fact]
    public void Encodes_integers_big_endian()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);

        // version = 1 at offset 4
        Assert.Equal(0x00, buffer[4]);
        Assert.Equal(0x01, buffer[5]);
        // rangeStart = 2097152 = 0x200000 at offset 40, 8 bytes big-endian
        Assert.Equal(0x00, buffer[40]);
        Assert.Equal(0x20, buffer[45]);
        Assert.Equal(0x00, buffer[47]);
    }

    [Fact]
    public void Reserved_bytes_are_zero()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        buffer.Fill(0xAB);
        Sample().WriteTo(buffer);

        Assert.Equal(0, buffer[60]);
        Assert.Equal(0, buffer[63]);
    }

    [Fact]
    public void TryRead_rejects_bad_magic()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        Sample().WriteTo(buffer);
        buffer[0] = (byte)'X';

        Assert.False(BulkFrameHeader.TryRead(buffer, out _));
    }

    [Fact]
    public void TryRead_rejects_a_future_version()
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        (Sample() with { Version = 99 }).WriteTo(buffer);

        Assert.False(BulkFrameHeader.TryRead(buffer, out _));
    }

    [Fact]
    public void TryRead_rejects_a_short_buffer()
    {
        Assert.False(BulkFrameHeader.TryRead(new byte[63], out _));
    }

    [Theory]
    [InlineData(-1L, 100L, 1024)]
    [InlineData(0L, -5L, 1024)]
    [InlineData(0L, 100L, 0)]
    public void TryRead_rejects_nonsensical_ranges(long start, long length, int chunkSize)
    {
        Span<byte> buffer = stackalloc byte[BulkFrameHeader.Size];
        (Sample() with { RangeStart = start, RangeLength = length, ChunkSize = chunkSize }).WriteTo(buffer);

        Assert.False(BulkFrameHeader.TryRead(buffer, out _));
    }

    [Fact]
    public void Matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(VectorPaths.Root, "bulk-headers.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var fields = testCase.GetProperty("fields");
            var expectedHex = testCase.GetProperty("bytes").GetString()!.Replace("_", "");

            Assert.NotEqual("PENDING", expectedHex);

            var header = new BulkFrameHeader(
                (ushort)fields.GetProperty("version").GetInt32(),
                (ushort)fields.GetProperty("streamIndex").GetInt32(),
                GuidFromHex(fields.GetProperty("token").GetString()!),
                GuidFromHex(fields.GetProperty("transferId").GetString()!),
                fields.GetProperty("rangeStart").GetInt64(),
                fields.GetProperty("rangeLength").GetInt64(),
                fields.GetProperty("chunkSize").GetInt32());

            var buffer = new byte[BulkFrameHeader.Size];
            header.WriteTo(buffer);

            Assert.Equal(expectedHex, Convert.ToHexString(buffer).ToLowerInvariant());
        }
    }

    private static Guid GuidFromHex(string hex) => new(Convert.FromHexString(hex), bigEndian: true);
}
```

- [ ] **Step 2: Add the shared vector-path helper**

Create `windows/tests/Slipstream.Core.Tests/VectorPaths.cs`. Every vector-reading test uses it:

```csharp
namespace Slipstream.Core.Tests;

public static class VectorPaths
{
    public static string Root { get; } = Locate();

    private static string Locate()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "vectors")))
            dir = dir.Parent;

        return dir is null
            ? throw new DirectoryNotFoundException("Could not locate protocol/vectors from the test output directory.")
            : Path.Combine(dir.FullName, "protocol", "vectors");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter BulkFrameHeaderTests`
Expected: FAIL — `BulkFrameHeader` does not exist.

- [ ] **Step 4: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/BulkFrameHeader.cs`:

```csharp
using System.Buffers.Binary;

namespace Slipstream.Core.Transfer;

/// <summary>
/// The 64-byte stream header from protocol/bulk-format.md. Big-endian throughout.
/// </summary>
public readonly record struct BulkFrameHeader(
    ushort Version,
    ushort StreamIndex,
    Guid Token,
    Guid TransferId,
    long RangeStart,
    long RangeLength,
    int ChunkSize)
{
    public const int Size = 64;

    private static ReadOnlySpan<byte> Magic => "SLPS"u8;

    public void WriteTo(Span<byte> destination)
    {
        if (destination.Length < Size)
            throw new ArgumentException($"Header needs {Size} bytes.", nameof(destination));

        destination[..Size].Clear();

        Magic.CopyTo(destination);
        BinaryPrimitives.WriteUInt16BigEndian(destination[4..], Version);
        BinaryPrimitives.WriteUInt16BigEndian(destination[6..], StreamIndex);

        Token.TryWriteBytes(destination.Slice(8, 16), bigEndian: true, out _);
        TransferId.TryWriteBytes(destination.Slice(24, 16), bigEndian: true, out _);

        BinaryPrimitives.WriteInt64BigEndian(destination[40..], RangeStart);
        BinaryPrimitives.WriteInt64BigEndian(destination[48..], RangeLength);
        BinaryPrimitives.WriteInt32BigEndian(destination[56..], ChunkSize);
        // bytes 60..63 stay zero
    }

    public static bool TryRead(ReadOnlySpan<byte> source, out BulkFrameHeader header)
    {
        header = default;

        if (source.Length < Size) return false;
        if (!source[..4].SequenceEqual(Magic)) return false;

        var version = BinaryPrimitives.ReadUInt16BigEndian(source[4..]);
        if (version != SlipstreamPorts.ProtocolVersion) return false;

        var rangeStart = BinaryPrimitives.ReadInt64BigEndian(source[40..]);
        var rangeLength = BinaryPrimitives.ReadInt64BigEndian(source[48..]);
        var chunkSize = BinaryPrimitives.ReadInt32BigEndian(source[56..]);

        if (rangeStart < 0 || rangeLength < 0 || chunkSize <= 0) return false;

        header = new BulkFrameHeader(
            version,
            BinaryPrimitives.ReadUInt16BigEndian(source[6..]),
            new Guid(source.Slice(8, 16), bigEndian: true),
            new Guid(source.Slice(24, 16), bigEndian: true),
            rangeStart,
            rangeLength,
            chunkSize);

        return true;
    }
}
```

- [ ] **Step 5: Fill in the pending vector**

Add a temporary fact that prints the encoded header for the `third-stream-offset-range` case, run it with `dotnet test --filter Print_header -l "console;verbosity=detailed"`, paste the hex into `protocol/vectors/bulk-headers.json`, then delete the fact:

```csharp
[Fact]
public void Print_header()
{
    var buffer = new byte[BulkFrameHeader.Size];
    Sample().WriteTo(buffer);
    Console.WriteLine(Convert.ToHexString(buffer).ToLowerInvariant());
}
```

Also verify the first vector case, `first-stream-1mb-range`, matches what the implementation produces — if it does not, the *vector* is wrong and gets corrected, since the implementation is the one under test here and the document in Task 1 is the arbiter of intent.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter BulkFrameHeaderTests`
Expected: PASS, 13 tests.

- [ ] **Step 7: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/BulkFrameHeader.cs windows/tests/Slipstream.Core.Tests/Transfer windows/tests/Slipstream.Core.Tests/VectorPaths.cs protocol/vectors/bulk-headers.json
git commit -m "feat: add 64-byte bulk stream header codec"
```

---

## Task 3: Hardware-accelerated CRC32C

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/Crc32C.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/Crc32CTests.cs`

**Interfaces:**
- Consumes: `protocol/vectors/crc32c.json`.
- Produces:
  - `static class Crc32C { static uint Compute(ReadOnlySpan<byte> data); static uint Append(uint crc, ReadOnlySpan<byte> data); static bool IsHardwareAccelerated { get; } }`
  - `Compute(x)` is defined as `Append(0, x)` with the standard init/final xor applied internally.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/Crc32CTests.cs`:

```csharp
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class Crc32CTests
{
    [Fact]
    public void Matches_the_standard_castagnoli_check_value()
    {
        // The canonical CRC-32C check value for "123456789".
        Assert.Equal(0xE3069283u, Crc32C.Compute("123456789"u8));
    }

    [Fact]
    public void Empty_input_is_zero()
    {
        Assert.Equal(0u, Crc32C.Compute(ReadOnlySpan<byte>.Empty));
    }

    [Fact]
    public void Matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(VectorPaths.Root, "crc32c.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var input = Encoding.UTF8.GetBytes(testCase.GetProperty("input_utf8").GetString()!);
            var expected = Convert.ToUInt32(testCase.GetProperty("crc_hex").GetString()!, 16);

            Assert.Equal(expected, Crc32C.Compute(input));
        }
    }

    [Fact]
    public void Append_in_pieces_equals_a_single_pass()
    {
        var data = RandomNumberGenerator.GetBytes(100_000);

        var single = Crc32C.Compute(data);

        var running = 0u;
        for (var offset = 0; offset < data.Length; offset += 7777)
            running = Crc32C.Append(running, data.AsSpan(offset, Math.Min(7777, data.Length - offset)));

        Assert.Equal(single, running);
    }

    [Fact]
    public void Detects_a_single_flipped_bit()
    {
        var data = RandomNumberGenerator.GetBytes(1_048_576);
        var original = Crc32C.Compute(data);

        data[524_288] ^= 0x01;

        Assert.NotEqual(original, Crc32C.Compute(data));
    }

    [Fact]
    public void Handles_a_full_chunk_sized_buffer()
    {
        var chunk = RandomNumberGenerator.GetBytes(1_048_576);
        Assert.Equal(Crc32C.Compute(chunk), Crc32C.Compute(chunk));
    }
}
```

The "flipped bit" test is the one that matters operationally: chunk verification exists to catch exactly that, and a CRC implementation with a wrong polynomial can still round-trip its own values perfectly while catching far less.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter Crc32CTests`
Expected: FAIL — `Crc32C` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/Crc32C.cs`:

```csharp
using System.Runtime.Intrinsics.Arm;
using System.Runtime.Intrinsics.X86;

namespace Slipstream.Core.Transfer;

/// <summary>
/// CRC-32C (Castagnoli). Hardware-accelerated via SSE4.2 on x64 and the ARM64 CRC
/// extension, with a table-driven fallback. BCL only — no NuGet package.
/// </summary>
public static class Crc32C
{
    private const uint Polynomial = 0x82F63B78; // reflected 0x1EDC6F41

    private static readonly uint[] Table = BuildTable();

    public static bool IsHardwareAccelerated => Sse42.IsSupported || Crc32.IsSupported;

    public static uint Compute(ReadOnlySpan<byte> data) => Append(0, data);

    /// <summary>Continues a running CRC. Append(0, x) == Compute(x).</summary>
    public static uint Append(uint crc, ReadOnlySpan<byte> data)
    {
        var state = ~crc;

        if (Sse42.X64.IsSupported)
            state = AppendSse42(state, data);
        else if (Crc32.Arm64.IsSupported)
            state = AppendArm64(state, data);
        else
            state = AppendSoftware(state, data);

        return ~state;
    }

    private static uint AppendSse42(uint state, ReadOnlySpan<byte> data)
    {
        var index = 0;

        // Eight bytes at a time while the buffer allows.
        while (data.Length - index >= sizeof(ulong))
        {
            var block = BitConverter.ToUInt64(data.Slice(index, sizeof(ulong)));
            state = (uint)Sse42.X64.Crc32(state, block);
            index += sizeof(ulong);
        }

        for (; index < data.Length; index++)
            state = Sse42.Crc32(state, data[index]);

        return state;
    }

    private static uint AppendArm64(uint state, ReadOnlySpan<byte> data)
    {
        var index = 0;

        while (data.Length - index >= sizeof(ulong))
        {
            var block = BitConverter.ToUInt64(data.Slice(index, sizeof(ulong)));
            state = Crc32.Arm64.ComputeCrc32C(state, block);
            index += sizeof(ulong);
        }

        for (; index < data.Length; index++)
            state = Crc32.ComputeCrc32C(state, data[index]);

        return state;
    }

    private static uint AppendSoftware(uint state, ReadOnlySpan<byte> data)
    {
        foreach (var b in data)
            state = Table[(state ^ b) & 0xFF] ^ (state >> 8);

        return state;
    }

    private static uint[] BuildTable()
    {
        var table = new uint[256];

        for (uint i = 0; i < 256; i++)
        {
            var entry = i;
            for (var bit = 0; bit < 8; bit++)
                entry = (entry & 1) != 0 ? (entry >> 1) ^ Polynomial : entry >> 1;

            table[i] = entry;
        }

        return table;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter Crc32CTests`
Expected: PASS, 6 tests.

Note: the vector test exercises whichever path this CPU supports. To confirm the fallback also agrees, temporarily change `Append` to call `AppendSoftware` unconditionally, re-run, and revert. Do this once, now — a divergent fallback is invisible until it runs on a machine without the intrinsic.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/Crc32C.cs windows/tests/Slipstream.Core.Tests/Transfer/Crc32CTests.cs
git commit -m "feat: add hardware-accelerated CRC32C with software fallback"
```

---

## Task 4: Transfer tokens

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/TransferToken.cs`
- Create: `windows/src/Slipstream.Core/Transfer/TokenVault.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/TokenVaultTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed record TransferToken(Guid Value, Guid TransferId, string Path, long Size, DateTimeOffset ExpiresAt)`
  - `sealed class TokenVault { TokenVault(TimeProvider? time = null); TransferToken IssueBulk(Guid transferId, string path, long size, int expectedStreams); TransferToken? ValidateBulk(Guid token, Guid transferId); TransferToken IssueMedia(string path, long size); TransferToken? ValidateMedia(Guid token); void Revoke(Guid transferId); }`
  - Bulk tokens accept exactly `expectedStreams` validations, then stop — "single-use" scoped to one transfer means one use per stream, not one use total.
  - Media tokens expire after 12 hours; the vault is in-memory, so an app restart invalidates every token.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/TokenVaultTests.cs`:

```csharp
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class TokenVaultTests
{
    private static readonly Guid Transfer = Guid.Parse("11111111-1111-1111-1111-111111111111");

    [Fact]
    public void A_bulk_token_validates_once_per_expected_stream()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 4);

        for (var i = 0; i < 4; i++)
            Assert.NotNull(vault.ValidateBulk(token.Value, Transfer));

        Assert.Null(vault.ValidateBulk(token.Value, Transfer)); // fifth use refused
    }

    [Fact]
    public void A_bulk_token_is_scoped_to_its_transfer_id()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 1);

        Assert.Null(vault.ValidateBulk(token.Value, Guid.NewGuid()));
    }

    [Fact]
    public void An_unknown_bulk_token_is_refused()
    {
        Assert.Null(new TokenVault().ValidateBulk(Guid.NewGuid(), Transfer));
    }

    [Fact]
    public void Validation_returns_the_path_and_size_the_token_was_issued_for()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\movies\film.mkv", 42_000, expectedStreams: 1);

        var validated = vault.ValidateBulk(token.Value, Transfer);

        Assert.Equal(@"C:\movies\film.mkv", validated!.Path);
        Assert.Equal(42_000, validated.Size);
    }

    [Fact]
    public void Revoke_invalidates_every_token_for_a_transfer()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 8);

        vault.Revoke(Transfer);

        Assert.Null(vault.ValidateBulk(token.Value, Transfer));
    }

    [Fact]
    public void Issued_tokens_are_unpredictable()
    {
        var vault = new TokenVault();
        var issued = Enumerable.Range(0, 100)
            .Select(_ => vault.IssueBulk(Guid.NewGuid(), "x", 1, 1).Value)
            .ToHashSet();

        Assert.Equal(100, issued.Count);
    }

    [Fact]
    public void A_media_token_validates_repeatedly_within_its_lifetime()
    {
        var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-08-25T10:00:00Z"));
        var vault = new TokenVault(time);

        var token = vault.IssueMedia(@"C:\movies\film.mkv", 1_000_000);

        Assert.NotNull(vault.ValidateMedia(token.Value));
        time.Advance(TimeSpan.FromHours(11));
        Assert.NotNull(vault.ValidateMedia(token.Value)); // seeking hours into a film must still work
    }

    [Fact]
    public void A_media_token_expires_after_twelve_hours()
    {
        var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-08-25T10:00:00Z"));
        var vault = new TokenVault(time);

        var token = vault.IssueMedia(@"C:\movies\film.mkv", 1_000_000);
        time.Advance(TimeSpan.FromHours(12) + TimeSpan.FromMinutes(1));

        Assert.Null(vault.ValidateMedia(token.Value));
    }

    private sealed class FakeTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }
}
```

The "validates once per expected stream" behaviour is the subtle one. A literally single-use token would break the 4-stream design on the second stream's connection, so the vault counts down from the stream count instead.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter TokenVaultTests`
Expected: FAIL — `TokenVault` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/TransferToken.cs`:

```csharp
namespace Slipstream.Core.Transfer;

public sealed record TransferToken(
    Guid Value,
    Guid TransferId,
    string Path,
    long Size,
    DateTimeOffset ExpiresAt);
```

Create `windows/src/Slipstream.Core/Transfer/TokenVault.cs`:

```csharp
using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace Slipstream.Core.Transfer;

/// <summary>
/// Spec §7 and §8. Tokens are issued only over the TLS control channel and are what
/// authenticate the plaintext bulk and media paths. In-memory by design: an app
/// restart invalidates everything, which is half of the media-token expiry rule.
/// </summary>
public sealed class TokenVault(TimeProvider? time = null)
{
    private static readonly TimeSpan MediaLifetime = TimeSpan.FromHours(12);
    private static readonly TimeSpan BulkLifetime = TimeSpan.FromHours(24);

    private readonly TimeProvider _time = time ?? TimeProvider.System;
    private readonly ConcurrentDictionary<Guid, Entry> _entries = new();

    private sealed class Entry(TransferToken token, int remainingUses)
    {
        public TransferToken Token { get; } = token;
        public int RemainingUses = remainingUses;
    }

    public TransferToken IssueBulk(Guid transferId, string path, long size, int expectedStreams)
    {
        var token = new TransferToken(
            NewToken(), transferId, path, size, _time.GetUtcNow() + BulkLifetime);

        _entries[token.Value] = new Entry(token, Math.Max(1, expectedStreams));
        return token;
    }

    /// <summary>Consumes one use. Returns null when unknown, expired, exhausted, or mis-scoped.</summary>
    public TransferToken? ValidateBulk(Guid token, Guid transferId)
    {
        if (!_entries.TryGetValue(token, out var entry)) return null;
        if (entry.Token.TransferId != transferId) return null;
        if (_time.GetUtcNow() > entry.Token.ExpiresAt) return null;

        // One use per expected stream — a 4-stream transfer legitimately presents
        // the same token four times.
        if (Interlocked.Decrement(ref entry.RemainingUses) < 0)
        {
            Interlocked.Increment(ref entry.RemainingUses);
            return null;
        }

        return entry.Token;
    }

    public TransferToken IssueMedia(string path, long size)
    {
        var token = new TransferToken(
            NewToken(), Guid.Empty, path, size, _time.GetUtcNow() + MediaLifetime);

        // Media is seeked repeatedly; uses are effectively unlimited within the lifetime.
        _entries[token.Value] = new Entry(token, int.MaxValue);
        return token;
    }

    public TransferToken? ValidateMedia(Guid token)
    {
        if (!_entries.TryGetValue(token, out var entry)) return null;
        if (_time.GetUtcNow() > entry.Token.ExpiresAt)
        {
            _entries.TryRemove(token, out _);
            return null;
        }

        return entry.Token;
    }

    public void Revoke(Guid transferId)
    {
        foreach (var pair in _entries)
            if (pair.Value.Token.TransferId == transferId)
                _entries.TryRemove(pair.Key, out _);
    }

    private static Guid NewToken() => new(RandomNumberGenerator.GetBytes(16));
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter TokenVaultTests`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/TransferToken.cs windows/src/Slipstream.Core/Transfer/TokenVault.cs windows/tests/Slipstream.Core.Tests/Transfer/TokenVaultTests.cs
git commit -m "feat: add transfer and media token vault"
```

---

## Task 5: Chunk bitmap

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/ChunkBitmap.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/ChunkBitmapTests.cs`

**Interfaces:**
- Consumes: `protocol/vectors/chunk-bitmaps.json`.
- Produces:
  - `sealed class ChunkBitmap { ChunkBitmap(int chunkCount); int ChunkCount { get; } int CompletedCount { get; } bool IsComplete { get; } bool this[int index] { get; set; } IEnumerable<Range> MissingRanges(); string ToBase64(); static ChunkBitmap FromBase64(string base64, int chunkCount); static int ChunkCountFor(long fileSize, int chunkSize); }`
  - `MissingRanges()` yields contiguous runs of missing chunks as `Range` values in *chunk index* space, which is what resume needs.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/ChunkBitmapTests.cs`:

```csharp
using System.Text.Json;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class ChunkBitmapTests
{
    [Fact]
    public void A_new_bitmap_is_empty()
    {
        var bitmap = new ChunkBitmap(10);

        Assert.Equal(10, bitmap.ChunkCount);
        Assert.Equal(0, bitmap.CompletedCount);
        Assert.False(bitmap.IsComplete);
        Assert.False(bitmap[0]);
    }

    [Fact]
    public void Setting_and_reading_a_chunk_round_trips()
    {
        var bitmap = new ChunkBitmap(10) { [3] = true };

        Assert.True(bitmap[3]);
        Assert.False(bitmap[4]);
        Assert.Equal(1, bitmap.CompletedCount);
    }

    [Fact]
    public void Setting_the_same_chunk_twice_does_not_double_count()
    {
        var bitmap = new ChunkBitmap(10) { [3] = true };
        bitmap[3] = true;

        Assert.Equal(1, bitmap.CompletedCount);
    }

    [Fact]
    public void IsComplete_when_every_chunk_is_set()
    {
        var bitmap = new ChunkBitmap(3);
        for (var i = 0; i < 3; i++) bitmap[i] = true;

        Assert.True(bitmap.IsComplete);
    }

    [Fact]
    public void MissingRanges_yields_contiguous_runs()
    {
        var bitmap = new ChunkBitmap(10);
        bitmap[0] = true;
        bitmap[1] = true;
        bitmap[5] = true;

        var missing = bitmap.MissingRanges().ToList();

        Assert.Equal(2, missing.Count);
        Assert.Equal(2, missing[0].Start.Value);
        Assert.Equal(5, missing[0].End.Value);   // exclusive: chunks 2,3,4
        Assert.Equal(6, missing[1].Start.Value);
        Assert.Equal(10, missing[1].End.Value);  // chunks 6..9
    }

    [Fact]
    public void MissingRanges_is_empty_for_a_complete_bitmap()
    {
        var bitmap = new ChunkBitmap(4);
        for (var i = 0; i < 4; i++) bitmap[i] = true;

        Assert.Empty(bitmap.MissingRanges());
    }

    [Fact]
    public void MissingRanges_covers_everything_for_an_empty_bitmap()
    {
        var missing = new ChunkBitmap(7).MissingRanges().ToList();

        Assert.Single(missing);
        Assert.Equal(0, missing[0].Start.Value);
        Assert.Equal(7, missing[0].End.Value);
    }

    [Fact]
    public void Base64_round_trips()
    {
        var bitmap = new ChunkBitmap(20);
        bitmap[0] = true;
        bitmap[7] = true;
        bitmap[19] = true;

        var restored = ChunkBitmap.FromBase64(bitmap.ToBase64(), 20);

        Assert.Equal(3, restored.CompletedCount);
        Assert.True(restored[0]);
        Assert.True(restored[7]);
        Assert.True(restored[19]);
        Assert.False(restored[8]);
    }

    [Fact]
    public void Matches_the_shared_conformance_vectors()
    {
        var path = Path.Combine(VectorPaths.Root, "chunk-bitmaps.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));

        foreach (var testCase in doc.RootElement.GetProperty("cases").EnumerateArray())
        {
            var chunkCount = testCase.GetProperty("chunkCount").GetInt32();
            var bitmap = new ChunkBitmap(chunkCount);

            foreach (var index in testCase.GetProperty("complete").EnumerateArray())
                bitmap[index.GetInt32()] = true;

            Assert.Equal(testCase.GetProperty("base64").GetString(), bitmap.ToBase64());
        }
    }

    [Theory]
    [InlineData(0L, 1_048_576, 0)]
    [InlineData(1L, 1_048_576, 1)]
    [InlineData(1_048_576L, 1_048_576, 1)]
    [InlineData(1_048_577L, 1_048_576, 2)]
    [InlineData(10_485_760L, 1_048_576, 10)]
    public void ChunkCountFor_rounds_up(long fileSize, int chunkSize, int expected)
    {
        Assert.Equal(expected, ChunkBitmap.ChunkCountFor(fileSize, chunkSize));
    }

    [Fact]
    public void Rejects_an_out_of_range_index()
    {
        var bitmap = new ChunkBitmap(4);
        Assert.Throws<ArgumentOutOfRangeException>(() => bitmap[4] = true);
        Assert.Throws<ArgumentOutOfRangeException>(() => bitmap[-1] = true);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter ChunkBitmapTests`
Expected: FAIL — `ChunkBitmap` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/ChunkBitmap.cs`:

```csharp
namespace Slipstream.Core.Transfer;

/// <summary>
/// Spec §7 resume. One bit per chunk, set = complete and CRC-verified.
/// Little-endian bit order: bit i of byte n is chunk (n*8 + i).
/// </summary>
public sealed class ChunkBitmap
{
    private readonly byte[] _bits;

    public ChunkBitmap(int chunkCount)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(chunkCount);

        ChunkCount = chunkCount;
        _bits = new byte[(chunkCount + 7) / 8];
    }

    public int ChunkCount { get; }

    public int CompletedCount { get; private set; }

    public bool IsComplete => CompletedCount == ChunkCount;

    public bool this[int index]
    {
        get
        {
            Validate(index);
            return (_bits[index / 8] & (1 << (index % 8))) != 0;
        }
        set
        {
            Validate(index);

            var current = this[index];
            if (current == value) return;

            if (value)
            {
                _bits[index / 8] |= (byte)(1 << (index % 8));
                CompletedCount++;
            }
            else
            {
                _bits[index / 8] &= (byte)~(1 << (index % 8));
                CompletedCount--;
            }
        }
    }

    /// <summary>Contiguous runs of missing chunks, in chunk-index space, end-exclusive.</summary>
    public IEnumerable<Range> MissingRanges()
    {
        var start = -1;

        for (var i = 0; i < ChunkCount; i++)
        {
            if (!this[i])
            {
                if (start < 0) start = i;
            }
            else if (start >= 0)
            {
                yield return new Range(start, i);
                start = -1;
            }
        }

        if (start >= 0) yield return new Range(start, ChunkCount);
    }

    public string ToBase64() => Convert.ToBase64String(_bits);

    public static ChunkBitmap FromBase64(string base64, int chunkCount)
    {
        var bitmap = new ChunkBitmap(chunkCount);
        var bytes = Convert.FromBase64String(base64);

        for (var i = 0; i < chunkCount; i++)
        {
            var byteIndex = i / 8;
            if (byteIndex >= bytes.Length) break;

            if ((bytes[byteIndex] & (1 << (i % 8))) != 0) bitmap[i] = true;
        }

        return bitmap;
    }

    public static int ChunkCountFor(long fileSize, int chunkSize)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(fileSize);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(chunkSize);

        return (int)((fileSize + chunkSize - 1) / chunkSize);
    }

    private void Validate(int index)
    {
        if (index < 0 || index >= ChunkCount)
            throw new ArgumentOutOfRangeException(nameof(index), $"Chunk {index} is outside 0..{ChunkCount - 1}.");
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter ChunkBitmapTests`
Expected: PASS, 15 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/ChunkBitmap.cs windows/tests/Slipstream.Core.Tests/Transfer/ChunkBitmapTests.cs
git commit -m "feat: add sparse chunk bitmap with resume gap calculation"
```

---

## Task 6: Transfer plan — range splitting

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/TransferPlan.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/TransferPlanTests.cs`

**Interfaces:**
- Consumes: `ChunkBitmap`.
- Produces:
  - `readonly record struct ByteRange(long Start, long Length) { long EndExclusive => Start + Length; }`
  - `static class TransferPlan { const int SmallFileThreshold = 4 * 1024 * 1024; static IReadOnlyList<ByteRange> Split(long fileSize, int streamCount, int chunkSize); static IReadOnlyList<ByteRange> SplitMissing(ChunkBitmap bitmap, long fileSize, int streamCount, int chunkSize); }`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/TransferPlanTests.cs`:

```csharp
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class TransferPlanTests
{
    private const int Chunk = 1_048_576;

    [Fact]
    public void A_small_file_is_assigned_whole_to_one_stream()
    {
        var ranges = TransferPlan.Split(40_000, streamCount: 4, Chunk);

        Assert.Single(ranges);
        Assert.Equal(0, ranges[0].Start);
        Assert.Equal(40_000, ranges[0].Length);
    }

    [Fact]
    public void The_small_file_threshold_is_four_megabytes()
    {
        Assert.Single(TransferPlan.Split(TransferPlan.SmallFileThreshold - 1, 4, Chunk));
        Assert.True(TransferPlan.Split(TransferPlan.SmallFileThreshold + 1, 4, Chunk).Count > 1);
    }

    [Fact]
    public void A_large_file_splits_across_the_requested_streams()
    {
        var ranges = TransferPlan.Split(40 * Chunk, streamCount: 4, Chunk);

        Assert.Equal(4, ranges.Count);
        Assert.All(ranges, r => Assert.Equal(10 * Chunk, r.Length));
    }

    [Fact]
    public void Ranges_are_contiguous_and_cover_the_whole_file()
    {
        const long size = 37 * Chunk + 12_345;
        var ranges = TransferPlan.Split(size, streamCount: 4, Chunk);

        Assert.Equal(0, ranges[0].Start);
        Assert.Equal(size, ranges[^1].EndExclusive);

        for (var i = 1; i < ranges.Count; i++)
            Assert.Equal(ranges[i - 1].EndExclusive, ranges[i].Start);
    }

    [Fact]
    public void Every_range_except_the_last_starts_on_a_chunk_boundary()
    {
        var ranges = TransferPlan.Split(37 * Chunk + 999, streamCount: 4, Chunk);

        Assert.All(ranges, r => Assert.Equal(0, r.Start % Chunk));
    }

    [Fact]
    public void Remainder_chunks_go_to_the_earliest_ranges()
    {
        // 10 chunks over 4 streams: 3,3,2,2 — never 2,2,2,4.
        var ranges = TransferPlan.Split(10 * Chunk, streamCount: 4, Chunk);
        var chunkCounts = ranges.Select(r => r.Length / Chunk).ToList();

        Assert.Equal([3, 3, 2, 2], chunkCounts);
    }

    [Fact]
    public void Stream_count_is_capped_by_chunk_count()
    {
        // 5 MB is 5 chunks; 8 streams cannot each get one.
        var ranges = TransferPlan.Split(5 * Chunk, streamCount: 8, Chunk);
        Assert.Equal(5, ranges.Count);
    }

    [Fact]
    public void An_empty_file_produces_no_ranges()
    {
        Assert.Empty(TransferPlan.Split(0, 4, Chunk));
    }

    [Fact]
    public void SplitMissing_only_covers_the_gaps()
    {
        var bitmap = new ChunkBitmap(10);
        for (var i = 0; i < 6; i++) bitmap[i] = true; // first 6 chunks done

        var ranges = TransferPlan.SplitMissing(bitmap, 10 * Chunk, streamCount: 4, Chunk);

        Assert.Equal(6L * Chunk, ranges.Min(r => r.Start));
        Assert.Equal(4L * Chunk, ranges.Sum(r => r.Length));
    }

    [Fact]
    public void SplitMissing_handles_fragmented_gaps()
    {
        var bitmap = new ChunkBitmap(10);
        bitmap[0] = true;
        bitmap[5] = true;
        bitmap[9] = true;

        var ranges = TransferPlan.SplitMissing(bitmap, 10 * Chunk, streamCount: 4, Chunk);

        Assert.Equal(7L * Chunk, ranges.Sum(r => r.Length));
        Assert.All(ranges, r => Assert.Equal(0, r.Start % Chunk));
    }

    [Fact]
    public void SplitMissing_returns_nothing_for_a_complete_bitmap()
    {
        var bitmap = new ChunkBitmap(4);
        for (var i = 0; i < 4; i++) bitmap[i] = true;

        Assert.Empty(TransferPlan.SplitMissing(bitmap, 4 * Chunk, 4, Chunk));
    }

    [Fact]
    public void SplitMissing_clamps_the_final_range_to_the_file_size()
    {
        const long size = 3 * Chunk + 500;
        var bitmap = new ChunkBitmap(ChunkBitmap.ChunkCountFor(size, Chunk));

        var ranges = TransferPlan.SplitMissing(bitmap, size, 4, Chunk);

        Assert.Equal(size, ranges.Sum(r => r.Length));
        Assert.Equal(size, ranges.Max(r => r.EndExclusive));
    }
}
```

The remainder-distribution test pins a real decision: naive integer division gives the *last* stream all the remainder, so with 10 chunks over 4 streams one stream does double the work and the transfer finishes at that stream's pace.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter TransferPlanTests`
Expected: FAIL — `TransferPlan` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/TransferPlan.cs`:

```csharp
namespace Slipstream.Core.Transfer;

public readonly record struct ByteRange(long Start, long Length)
{
    public long EndExclusive => Start + Length;
}

/// <summary>
/// Spec §7 parallelism. Ranges always start on a chunk boundary so the receiver can
/// derive chunk indices without transmitting them.
/// </summary>
public static class TransferPlan
{
    public const int SmallFileThreshold = 4 * 1024 * 1024;

    public static IReadOnlyList<ByteRange> Split(long fileSize, int streamCount, int chunkSize)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(fileSize);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(chunkSize);

        if (fileSize == 0) return [];

        // Range-splitting a small file costs more than it saves.
        if (fileSize < SmallFileThreshold) return [new ByteRange(0, fileSize)];

        var chunkCount = ChunkBitmap.ChunkCountFor(fileSize, chunkSize);
        var streams = Math.Clamp(streamCount, 1, chunkCount);

        var chunksPerStream = chunkCount / streams;
        var remainder = chunkCount % streams;

        var ranges = new List<ByteRange>(streams);
        var chunkIndex = 0;

        for (var i = 0; i < streams; i++)
        {
            // Remainder chunks go to the earliest ranges, so lengths differ by at
            // most one chunk and no single stream becomes the long pole.
            var chunks = chunksPerStream + (i < remainder ? 1 : 0);

            var start = (long)chunkIndex * chunkSize;
            var length = Math.Min((long)chunks * chunkSize, fileSize - start);

            ranges.Add(new ByteRange(start, length));
            chunkIndex += chunks;
        }

        return ranges;
    }

    /// <summary>Ranges covering only the chunks the bitmap reports as missing.</summary>
    public static IReadOnlyList<ByteRange> SplitMissing(
        ChunkBitmap bitmap, long fileSize, int streamCount, int chunkSize)
    {
        var ranges = new List<ByteRange>();

        foreach (var gap in bitmap.MissingRanges())
        {
            var start = (long)gap.Start.Value * chunkSize;
            var end = Math.Min((long)gap.End.Value * chunkSize, fileSize);

            if (end <= start) continue;

            ranges.Add(new ByteRange(start, end - start));
        }

        if (ranges.Count == 0) return [];

        // Subdivide the largest gaps so all available streams stay busy.
        var streams = Math.Max(1, streamCount);
        while (ranges.Count < streams)
        {
            var largestIndex = 0;
            for (var i = 1; i < ranges.Count; i++)
                if (ranges[i].Length > ranges[largestIndex].Length) largestIndex = i;

            var largest = ranges[largestIndex];
            var halfChunks = largest.Length / chunkSize / 2;
            if (halfChunks == 0) break;

            var splitAt = largest.Start + halfChunks * chunkSize;

            ranges[largestIndex] = new ByteRange(largest.Start, splitAt - largest.Start);
            ranges.Insert(largestIndex + 1, new ByteRange(splitAt, largest.EndExclusive - splitAt));
        }

        return ranges;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter TransferPlanTests`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/TransferPlan.cs windows/tests/Slipstream.Core.Tests/Transfer/TransferPlanTests.cs
git commit -m "feat: add range splitting with balanced remainder distribution"
```

---

## Task 7: Part file — preallocation, positioned writes, single fsync

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/PartFile.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/PartFileTests.cs`

**Interfaces:**
- Consumes: `ChunkBitmap`, `Crc32C`.
- Produces:
  - `sealed class PartFile : IAsyncDisposable { static PartFile OpenOrCreate(string destinationPath, Guid transferId, long size, int chunkSize); string DestinationPath { get; } string PartPath { get; } long Size { get; } int ChunkSize { get; } ChunkBitmap Bitmap { get; } Task WriteChunkAsync(int chunkIndex, ReadOnlyMemory<byte> data, uint expectedCrc, CancellationToken ct); Task<bool> CompleteAsync(CancellationToken ct); static int CollectStale(string directory, TimeSpan olderThan); }`
  - `WriteChunkAsync` throws `ChunkVerificationException` on CRC mismatch and leaves the bitmap bit clear, so the chunk is simply re-requested.
  - `CompleteAsync` returns false when the bitmap is incomplete; on success it fsyncs once, closes, deletes the sidecar, and renames `.part` to the destination.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/PartFileTests.cs`:

```csharp
using System.Security.Cryptography;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class PartFileTests : IDisposable
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-part-").FullName;
    private readonly Guid _transfer = Guid.NewGuid();

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private string Destination => Path.Combine(_dir, "output.bin");

    private static (byte[] Data, uint Crc) ChunkOf(int size)
    {
        var data = RandomNumberGenerator.GetBytes(size);
        return (data, Crc32C.Compute(data));
    }

    [Fact]
    public async Task Preallocates_the_destination_to_full_size()
    {
        await using var part = PartFile.OpenOrCreate(Destination, _transfer, 3 * Chunk, Chunk);

        Assert.True(File.Exists(part.PartPath));
        Assert.Equal(3 * Chunk, new FileInfo(part.PartPath).Length);
    }

    [Fact]
    public async Task Writes_chunks_at_the_correct_offset()
    {
        var (first, firstCrc) = ChunkOf(Chunk);
        var (second, secondCrc) = ChunkOf(Chunk);

        await using (var part = PartFile.OpenOrCreate(Destination, _transfer, 2 * Chunk, Chunk))
        {
            await part.WriteChunkAsync(1, second, secondCrc, CancellationToken.None);
            await part.WriteChunkAsync(0, first, firstCrc, CancellationToken.None);

            Assert.True(await part.CompleteAsync(CancellationToken.None));
        }

        var written = await File.ReadAllBytesAsync(Destination);

        Assert.Equal(first, written[..Chunk]);
        Assert.Equal(second, written[Chunk..]);
    }

    [Fact]
    public async Task Rejects_a_chunk_whose_crc_does_not_match()
    {
        var (data, crc) = ChunkOf(Chunk);
        await using var part = PartFile.OpenOrCreate(Destination, _transfer, Chunk, Chunk);

        await Assert.ThrowsAsync<ChunkVerificationException>(
            () => part.WriteChunkAsync(0, data, crc ^ 0xFFFFFFFF, CancellationToken.None));

        Assert.False(part.Bitmap[0]); // still missing, so it will be re-requested
    }

    [Fact]
    public async Task CompleteAsync_refuses_an_incomplete_transfer()
    {
        var (data, crc) = ChunkOf(Chunk);
        await using var part = PartFile.OpenOrCreate(Destination, _transfer, 2 * Chunk, Chunk);

        await part.WriteChunkAsync(0, data, crc, CancellationToken.None);

        Assert.False(await part.CompleteAsync(CancellationToken.None));
        Assert.False(File.Exists(Destination));
    }

    [Fact]
    public async Task Reopening_restores_the_bitmap()
    {
        var (data, crc) = ChunkOf(Chunk);

        await using (var part = PartFile.OpenOrCreate(Destination, _transfer, 4 * Chunk, Chunk))
        {
            await part.WriteChunkAsync(0, data, crc, CancellationToken.None);
            await part.WriteChunkAsync(2, data, crc, CancellationToken.None);
        }

        await using var reopened = PartFile.OpenOrCreate(Destination, _transfer, 4 * Chunk, Chunk);

        Assert.Equal(2, reopened.Bitmap.CompletedCount);
        Assert.True(reopened.Bitmap[0]);
        Assert.True(reopened.Bitmap[2]);
        Assert.False(reopened.Bitmap[1]);
    }

    [Fact]
    public async Task A_short_final_chunk_is_handled()
    {
        const long size = Chunk + 100;
        var (full, fullCrc) = ChunkOf(Chunk);
        var (tail, tailCrc) = ChunkOf(100);

        await using (var part = PartFile.OpenOrCreate(Destination, _transfer, size, Chunk))
        {
            await part.WriteChunkAsync(0, full, fullCrc, CancellationToken.None);
            await part.WriteChunkAsync(1, tail, tailCrc, CancellationToken.None);

            Assert.True(await part.CompleteAsync(CancellationToken.None));
        }

        Assert.Equal(size, new FileInfo(Destination).Length);
    }

    [Fact]
    public async Task Completing_removes_the_part_file_and_its_sidecar()
    {
        var (data, crc) = ChunkOf(Chunk);
        string partPath;

        await using (var part = PartFile.OpenOrCreate(Destination, _transfer, Chunk, Chunk))
        {
            partPath = part.PartPath;
            await part.WriteChunkAsync(0, data, crc, CancellationToken.None);
            await part.CompleteAsync(CancellationToken.None);
        }

        Assert.True(File.Exists(Destination));
        Assert.False(File.Exists(partPath));
        Assert.False(File.Exists(partPath + ".state"));
    }

    [Fact]
    public async Task CollectStale_removes_old_part_files_only()
    {
        var (data, crc) = ChunkOf(Chunk);

        await using (var part = PartFile.OpenOrCreate(Destination, _transfer, 2 * Chunk, Chunk))
        {
            await part.WriteChunkAsync(0, data, crc, CancellationToken.None);
        }

        var stalePart = Path.Combine(_dir, "output.bin.slipstream-part");
        var old = DateTime.UtcNow - TimeSpan.FromDays(8);
        File.SetLastWriteTimeUtc(stalePart, old);
        File.SetLastWriteTimeUtc(stalePart + ".state", old);

        var keeper = Path.Combine(_dir, "keep.txt");
        await File.WriteAllTextAsync(keeper, "not a part file");
        File.SetLastWriteTimeUtc(keeper, old);

        Assert.Equal(1, PartFile.CollectStale(_dir, TimeSpan.FromDays(7)));
        Assert.False(File.Exists(stalePart));
        Assert.True(File.Exists(keeper));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PartFileTests`
Expected: FAIL — `PartFile` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/PartFile.cs`:

```csharp
using System.Text.Json;

namespace Slipstream.Core.Transfer;

public sealed class ChunkVerificationException(int chunkIndex)
    : Exception($"Chunk {chunkIndex} failed verification.")
{
    public int ChunkIndex { get; } = chunkIndex;
}

/// <summary>
/// Spec §7. The destination is preallocated so parallel streams can write at any
/// offset, and fsync happens exactly once at completion rather than per chunk —
/// per-chunk fsync would dominate the transfer time.
/// </summary>
public sealed class PartFile : IAsyncDisposable
{
    private const string PartSuffix = ".slipstream-part";
    private const string StateSuffix = ".state";

    private sealed record State(Guid TransferId, long Size, int ChunkSize, string Bitmap);

    private readonly FileStream _stream;
    private readonly SemaphoreSlim _bitmapLock = new(1, 1);
    private bool _completed;

    private PartFile(string destinationPath, string partPath, FileStream stream,
        Guid transferId, long size, int chunkSize, ChunkBitmap bitmap)
    {
        DestinationPath = destinationPath;
        PartPath = partPath;
        TransferId = transferId;
        Size = size;
        ChunkSize = chunkSize;
        Bitmap = bitmap;
        _stream = stream;
    }

    public string DestinationPath { get; }
    public string PartPath { get; }
    public Guid TransferId { get; }
    public long Size { get; }
    public int ChunkSize { get; }
    public ChunkBitmap Bitmap { get; }

    private string StatePath => PartPath + StateSuffix;

    public static PartFile OpenOrCreate(string destinationPath, Guid transferId, long size, int chunkSize)
    {
        var partPath = destinationPath + PartSuffix;
        var statePath = partPath + StateSuffix;

        Directory.CreateDirectory(Path.GetDirectoryName(destinationPath)!);

        var chunkCount = ChunkBitmap.ChunkCountFor(size, chunkSize);
        var bitmap = new ChunkBitmap(chunkCount);

        // Resume only when the sidecar describes this exact transfer and geometry.
        if (File.Exists(partPath) && File.Exists(statePath))
        {
            try
            {
                var state = JsonSerializer.Deserialize<State>(File.ReadAllText(statePath));

                if (state is not null &&
                    state.TransferId == transferId &&
                    state.Size == size &&
                    state.ChunkSize == chunkSize)
                {
                    bitmap = ChunkBitmap.FromBase64(state.Bitmap, chunkCount);
                }
                else
                {
                    File.Delete(partPath);
                }
            }
            catch (JsonException)
            {
                File.Delete(partPath);
            }
        }

        var stream = new FileStream(partPath, new FileStreamOptions
        {
            Mode = FileMode.OpenOrCreate,
            Access = FileAccess.ReadWrite,
            Share = FileShare.None,
            Options = FileOptions.Asynchronous,
            PreallocationSize = size,
        });

        // Preallocate: parallel streams write at arbitrary offsets from byte one.
        if (stream.Length != size) stream.SetLength(size);

        return new PartFile(destinationPath, partPath, stream, transferId, size, chunkSize, bitmap);
    }

    public async Task WriteChunkAsync(
        int chunkIndex, ReadOnlyMemory<byte> data, uint expectedCrc, CancellationToken cancellationToken)
    {
        if (Crc32C.Compute(data.Span) != expectedCrc)
            throw new ChunkVerificationException(chunkIndex);

        var offset = (long)chunkIndex * ChunkSize;
        await RandomAccess.WriteAsync(_stream.SafeFileHandle, data, offset, cancellationToken);

        await _bitmapLock.WaitAsync(cancellationToken);
        try
        {
            Bitmap[chunkIndex] = true;
            await PersistStateAsync(cancellationToken);
        }
        finally
        {
            _bitmapLock.Release();
        }
    }

    public async Task<bool> CompleteAsync(CancellationToken cancellationToken)
    {
        if (!Bitmap.IsComplete) return false;

        await _stream.FlushAsync(cancellationToken);
        _stream.Flush(flushToDisk: true); // the one and only fsync
        await _stream.DisposeAsync();
        _completed = true;

        if (File.Exists(DestinationPath)) File.Delete(DestinationPath);
        File.Move(PartPath, DestinationPath);

        if (File.Exists(StatePath)) File.Delete(StatePath);

        return true;
    }

    /// <summary>Spec §7: orphaned .part files older than the cutoff are removed.</summary>
    public static int CollectStale(string directory, TimeSpan olderThan)
    {
        if (!Directory.Exists(directory)) return 0;

        var cutoff = DateTime.UtcNow - olderThan;
        var removed = 0;

        foreach (var path in Directory.EnumerateFiles(directory, "*" + PartSuffix, SearchOption.AllDirectories))
        {
            if (File.GetLastWriteTimeUtc(path) >= cutoff) continue;

            try
            {
                File.Delete(path);
                if (File.Exists(path + StateSuffix)) File.Delete(path + StateSuffix);
                removed++;
            }
            catch (IOException)
            {
                // In use by a live transfer. Leave it.
            }
        }

        return removed;
    }

    private Task PersistStateAsync(CancellationToken cancellationToken) =>
        File.WriteAllTextAsync(
            StatePath,
            JsonSerializer.Serialize(new State(TransferId, Size, ChunkSize, Bitmap.ToBase64())),
            cancellationToken);

    public async ValueTask DisposeAsync()
    {
        if (!_completed) await _stream.DisposeAsync();
        _bitmapLock.Dispose();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PartFileTests`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/PartFile.cs windows/tests/Slipstream.Core.Tests/Transfer/PartFileTests.cs
git commit -m "feat: add resumable part file with preallocation and single fsync"
```

---

## Task 8: Bulk server — serve byte ranges on 53322

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/BulkServer.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/BulkServerTests.cs`

**Interfaces:**
- Consumes: `BulkFrameHeader`, `TokenVault`, `Crc32C`, `LanGuard`, `SlipstreamPorts`.
- Produces: `sealed class BulkServer : IAsyncDisposable { BulkServer(TokenVault vault, IPAddress bindAddress, int port); IPEndPoint ListenEndPoint { get; } Task RunAsync(CancellationToken ct); }`
- Wire behaviour: reads a 64-byte header, validates the token, then writes `[len][data][crc]` per chunk. An invalid header or token closes the socket silently.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/BulkServerTests.cs`:

```csharp
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class BulkServerTests : IAsyncLifetime
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-bulk-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly TokenVault _vault = new();

    private BulkServer _server = null!;
    private string _sourcePath = null!;
    private byte[] _sourceData = null!;

    public Task InitializeAsync()
    {
        _sourceData = RandomNumberGenerator.GetBytes(10 * Chunk + 777);
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

    private async Task<NetworkStream> ConnectAsync()
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(_server.ListenEndPoint, _cts.Token);
        return tcp.GetStream();
    }

    private async Task SendHeaderAsync(Stream stream, BulkFrameHeader header)
    {
        var buffer = new byte[BulkFrameHeader.Size];
        header.WriteTo(buffer);
        await stream.WriteAsync(buffer, _cts.Token);
        await stream.FlushAsync(_cts.Token);
    }

    /// <summary>Reads [len][data][crc] chunks until the range is consumed.</summary>
    private async Task<byte[]> ReadRangeAsync(Stream stream, long rangeLength)
    {
        var output = new MemoryStream();
        var lengthBuffer = new byte[4];

        while (output.Length < rangeLength)
        {
            await stream.ReadExactlyAsync(lengthBuffer, _cts.Token);
            var chunkLength = BinaryPrimitives.ReadInt32BigEndian(lengthBuffer);

            var data = new byte[chunkLength];
            await stream.ReadExactlyAsync(data, _cts.Token);

            await stream.ReadExactlyAsync(lengthBuffer, _cts.Token);
            var crc = BinaryPrimitives.ReadUInt32BigEndian(lengthBuffer);

            Assert.Equal(Crc32C.Compute(data), crc);

            output.Write(data);
        }

        return output.ToArray();
    }

    [Fact]
    public async Task Serves_a_whole_file_range()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 0, token.Value, transferId, 0, _sourceData.Length, Chunk));

        Assert.Equal(_sourceData, await ReadRangeAsync(stream, _sourceData.Length));
    }

    [Fact]
    public async Task Serves_a_partial_range_from_the_correct_offset()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        const long start = 3 * Chunk;
        const long length = 2 * Chunk;

        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 1, token.Value, transferId, start, length, Chunk));

        Assert.Equal(_sourceData[(int)start..(int)(start + length)], await ReadRangeAsync(stream, length));
    }

    [Fact]
    public async Task Serves_a_short_final_chunk()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        const long start = 10 * Chunk;
        var length = _sourceData.Length - start;

        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 0, token.Value, transferId, start, length, Chunk));

        var received = await ReadRangeAsync(stream, length);

        Assert.Equal(777, received.Length);
        Assert.Equal(_sourceData[(int)start..], received);
    }

    [Fact]
    public async Task Closes_the_socket_for_an_invalid_token()
    {
        await using var stream = await ConnectAsync();
        await SendHeaderAsync(stream, new BulkFrameHeader(1, 0, Guid.NewGuid(), Guid.NewGuid(), 0, 100, Chunk));

        var buffer = new byte[1];
        Assert.Equal(0, await stream.ReadAsync(buffer, _cts.Token)); // EOF, no error frame
    }

    [Fact]
    public async Task Closes_the_socket_for_a_bad_magic()
    {
        await using var stream = await ConnectAsync();

        await stream.WriteAsync(new byte[BulkFrameHeader.Size], _cts.Token);
        await stream.FlushAsync(_cts.Token);

        var buffer = new byte[1];
        Assert.Equal(0, await stream.ReadAsync(buffer, _cts.Token));
    }

    [Fact]
    public async Task Serves_multiple_concurrent_streams_from_one_token()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        var ranges = TransferPlan.Split(_sourceData.Length, 4, Chunk);

        var results = await Task.WhenAll(ranges.Select(async (range, index) =>
        {
            await using var stream = await ConnectAsync();
            await SendHeaderAsync(stream, new BulkFrameHeader(
                1, (ushort)index, token.Value, transferId, range.Start, range.Length, Chunk));

            return (range, data: await ReadRangeAsync(stream, range.Length));
        }));

        var reassembled = new byte[_sourceData.Length];
        foreach (var (range, data) in results)
            data.CopyTo(reassembled, (int)range.Start);

        Assert.Equal(_sourceData, reassembled);
    }
}
```

The last test is the one that proves the design works: four sockets, one token, disjoint ranges, reassembled byte-identical.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter BulkServerTests`
Expected: FAIL — `BulkServer` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/BulkServer.cs`:

```csharp
using System.Buffers;
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Net;

namespace Slipstream.Core.Transfer;

/// <summary>
/// Spec §7. Plaintext by design, authenticated by a token the TLS control channel
/// issued. An unauthenticated peer gets a closed socket and learns nothing —
/// there is deliberately no error frame.
/// </summary>
public sealed class BulkServer : IAsyncDisposable
{
    private const int SocketBufferBytes = 4 * 1024 * 1024;

    private readonly TokenVault _vault;
    private readonly TcpListener _listener;

    public BulkServer(TokenVault vault, IPAddress bindAddress, int port)
    {
        LanGuard.EnsureLocal(bindAddress);

        _vault = vault;
        _listener = new TcpListener(bindAddress, port);
        _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.SendBuffer, SocketBufferBytes);
        _listener.Start();
    }

    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.LocalEndpoint;

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (OperationCanceledException) { return; }
            catch (SocketException) { continue; }

            _ = ServeAsync(client, cancellationToken);
        }
    }

    private async Task ServeAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;
            client.SendBufferSize = SocketBufferBytes;

            var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
            if (!LanGuard.IsLocal(remote.Address)) return;

            await using var stream = client.GetStream();

            var headerBuffer = new byte[BulkFrameHeader.Size];
            await stream.ReadExactlyAsync(headerBuffer, cancellationToken);

            if (!BulkFrameHeader.TryRead(headerBuffer, out var header)) return;

            var token = _vault.ValidateBulk(header.Token, header.TransferId);
            if (token is null) return;

            await SendRangeAsync(stream, token.Path, header, cancellationToken);
        }
        catch (Exception)
        {
            // A dropped bulk stream is routine — the client resumes.
        }
        finally
        {
            client.Dispose();
        }
    }

    private static async Task SendRangeAsync(
        Stream stream, string path, BulkFrameHeader header, CancellationToken cancellationToken)
    {
        using var file = new FileStream(path, new FileStreamOptions
        {
            Mode = FileMode.Open,
            Access = FileAccess.Read,
            Share = FileShare.Read,
            Options = FileOptions.Asynchronous | FileOptions.SequentialScan,
        });

        var buffer = ArrayPool<byte>.Shared.Rent(header.ChunkSize);
        var framing = new byte[4];

        try
        {
            var offset = header.RangeStart;
            var remaining = header.RangeLength;

            while (remaining > 0)
            {
                var toRead = (int)Math.Min(header.ChunkSize, remaining);

                var read = await RandomAccess.ReadAsync(
                    file.SafeFileHandle, buffer.AsMemory(0, toRead), offset, cancellationToken);

                if (read == 0) return; // file shrank underneath us

                var chunk = buffer.AsMemory(0, read);

                BinaryPrimitives.WriteInt32BigEndian(framing, read);
                await stream.WriteAsync(framing, cancellationToken);
                await stream.WriteAsync(chunk, cancellationToken);

                BinaryPrimitives.WriteUInt32BigEndian(framing, Crc32C.Compute(chunk.Span));
                await stream.WriteAsync(framing, cancellationToken);

                offset += read;
                remaining -= read;
            }

            await stream.FlushAsync(cancellationToken);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    public ValueTask DisposeAsync()
    {
        _listener.Stop();
        return ValueTask.CompletedTask;
    }
}
```

**On zero-copy.** The spec calls for `TransmitFile`, but Windows' `TransmitFile` sends a whole file and has no range variant, so it cannot serve a byte range. Pooled `RandomAccess.ReadAsync` into a 1 MB buffer is the correct .NET path here, and the copy is not the bottleneck — the radio is (spec §16). If the throughput gate in Task 16 ever shows the copy mattering, `Socket.SendFileAsync` remains available for the small-file whole-assignment path only.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter BulkServerTests`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/BulkServer.cs windows/tests/Slipstream.Core.Tests/Transfer/BulkServerTests.cs
git commit -m "feat: add token-authenticated bulk range server"
```

---

## Task 9: Bulk client — parallel range streams

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/BulkClient.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/BulkClientTests.cs`

**Interfaces:**
- Consumes: `BulkFrameHeader`, `PartFile`, `TransferPlan`, `Crc32C`, `LanGuard`.
- Produces:
  - `sealed record TransferProgress(Guid TransferId, long BytesCompleted, long TotalBytes, double BytesPerSecond)`
  - `sealed class BulkClient { Task DownloadAsync(IPEndPoint endpoint, Guid transferId, Guid token, PartFile part, int streamCount, IProgress<TransferProgress>? progress, CancellationToken ct); }`
  - Downloads only the ranges the part file's bitmap reports missing, so calling it again after an interruption resumes automatically.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/BulkClientTests.cs`:

```csharp
using System.Net;
using System.Security.Cryptography;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class BulkClientTests : IAsyncLifetime
{
    private const int Chunk = 4096;

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-bulkclient-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));
    private readonly TokenVault _vault = new();

    private BulkServer _server = null!;
    private string _sourcePath = null!;
    private byte[] _sourceData = null!;

    public Task InitializeAsync()
    {
        _sourceData = RandomNumberGenerator.GetBytes(20 * Chunk + 321);
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

    [Fact]
    public async Task Downloads_a_file_byte_identically()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task A_single_stream_download_also_works()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 1);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 1, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task Reports_progress_that_reaches_the_total()
    {
        var transferId = Guid.NewGuid();
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        var reports = new List<TransferProgress>();
        var progress = new Progress<TransferProgress>(p => { lock (reports) reports.Add(p); });

        await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);
        await new BulkClient().DownloadAsync(
            _server.ListenEndPoint, transferId, token.Value, part, 4, progress, _cts.Token);

        await Task.Delay(200, _cts.Token); // Progress<T> posts asynchronously

        lock (reports)
        {
            Assert.NotEmpty(reports);
            Assert.Equal(_sourceData.Length, reports.Max(r => r.BytesCompleted));
            Assert.All(reports, r => Assert.Equal(_sourceData.Length, r.TotalBytes));
        }
    }

    [Fact]
    public async Task Resumes_from_a_partial_download()
    {
        var transferId = Guid.NewGuid();

        // First pass: complete only chunks 0..4 by hand.
        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            for (var i = 0; i < 5; i++)
            {
                var slice = _sourceData.AsMemory(i * Chunk, Chunk);
                await part.WriteChunkAsync(i, slice, Crc32C.Compute(slice.Span), _cts.Token);
            }
        }

        // Second pass: resume.
        var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            Assert.Equal(5, part.Bitmap.CompletedCount);

            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, token.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task Survives_an_interruption_and_completes_on_retry()
    {
        var transferId = Guid.NewGuid();

        // Interrupt aggressively part-way through.
        using (var interrupt = new CancellationTokenSource(TimeSpan.FromMilliseconds(30)))
        {
            var token = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);
            await using var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk);

            try
            {
                await new BulkClient().DownloadAsync(
                    _server.ListenEndPoint, transferId, token.Value, part, 4, null, interrupt.Token);
            }
            catch (OperationCanceledException) { }
        }

        // Retry with a fresh token, as the engine would after re-discovery.
        var retryToken = _vault.IssueBulk(transferId, _sourcePath, _sourceData.Length, expectedStreams: 4);

        await using (var part = PartFile.OpenOrCreate(Destination, transferId, _sourceData.Length, Chunk))
        {
            await new BulkClient().DownloadAsync(
                _server.ListenEndPoint, transferId, retryToken.Value, part, 4, null, _cts.Token);

            Assert.True(await part.CompleteAsync(_cts.Token));
        }

        Assert.Equal(_sourceData, await File.ReadAllBytesAsync(Destination));
    }

    [Fact]
    public async Task Refuses_a_non_local_endpoint()
    {
        var transferId = Guid.NewGuid();
        await using var part = PartFile.OpenOrCreate(Destination, transferId, 100, Chunk);

        await Assert.ThrowsAsync<NonLocalAddressException>(() =>
            new BulkClient().DownloadAsync(
                new IPEndPoint(IPAddress.Parse("8.8.8.8"), 53322),
                transferId, Guid.NewGuid(), part, 4, null, _cts.Token));
    }
}
```

The interruption test is the resume guarantee proven end to end, at a randomised cut point, rather than asserted in prose.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter BulkClientTests`
Expected: FAIL — `BulkClient` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/BulkClient.cs`:

```csharp
using System.Buffers;
using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Net;

namespace Slipstream.Core.Transfer;

public sealed record TransferProgress(
    Guid TransferId, long BytesCompleted, long TotalBytes, double BytesPerSecond);

/// <summary>
/// Spec §7. Opens N sockets, each pulling a disjoint byte range. Only ranges the
/// part file reports missing are requested, so a second call after an interruption
/// resumes rather than restarts.
/// </summary>
public sealed class BulkClient
{
    private const int SocketBufferBytes = 4 * 1024 * 1024;
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromMilliseconds(250); // ~4/s

    public async Task DownloadAsync(
        IPEndPoint endpoint,
        Guid transferId,
        Guid token,
        PartFile part,
        int streamCount,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        LanGuard.EnsureLocal(endpoint.Address);

        var streams = Math.Clamp(streamCount, 1, 8);
        var ranges = TransferPlan.SplitMissing(part.Bitmap, part.Size, streams, part.ChunkSize);

        if (ranges.Count == 0) return;

        var alreadyDone = (long)part.Bitmap.CompletedCount * part.ChunkSize;
        var completed = Math.Min(alreadyDone, part.Size);
        var stopwatch = Stopwatch.StartNew();
        var lastReport = TimeSpan.Zero;

        void Report(int bytes)
        {
            var total = Interlocked.Add(ref completed, bytes);
            if (progress is null) return;

            var elapsed = stopwatch.Elapsed;
            if (elapsed - lastReport < ProgressInterval && total < part.Size) return;

            lastReport = elapsed;
            var rate = elapsed.TotalSeconds > 0 ? (total - alreadyDone) / elapsed.TotalSeconds : 0;
            progress.Report(new TransferProgress(transferId, total, part.Size, rate));
        }

        await Task.WhenAll(ranges.Select((range, index) =>
            PullRangeAsync(endpoint, transferId, token, part, range, (ushort)index, Report, cancellationToken)));

        progress?.Report(new TransferProgress(
            transferId, Interlocked.Read(ref completed), part.Size,
            stopwatch.Elapsed.TotalSeconds > 0 ? (part.Size - alreadyDone) / stopwatch.Elapsed.TotalSeconds : 0));
    }

    private static async Task PullRangeAsync(
        IPEndPoint endpoint,
        Guid transferId,
        Guid token,
        PartFile part,
        ByteRange range,
        ushort streamIndex,
        Action<int> report,
        CancellationToken cancellationToken)
    {
        using var tcp = new TcpClient { NoDelay = true, ReceiveBufferSize = SocketBufferBytes };
        await tcp.ConnectAsync(endpoint, cancellationToken);

        await using var stream = tcp.GetStream();

        var headerBuffer = new byte[BulkFrameHeader.Size];
        new BulkFrameHeader(
            (ushort)SlipstreamPorts.ProtocolVersion, streamIndex, token, transferId,
            range.Start, range.Length, part.ChunkSize).WriteTo(headerBuffer);

        await stream.WriteAsync(headerBuffer, cancellationToken);
        await stream.FlushAsync(cancellationToken);

        var framing = new byte[4];
        var buffer = ArrayPool<byte>.Shared.Rent(part.ChunkSize);

        try
        {
            var received = 0L;
            var chunkIndex = (int)(range.Start / part.ChunkSize);

            while (received < range.Length)
            {
                await stream.ReadExactlyAsync(framing, cancellationToken);
                var chunkLength = BinaryPrimitives.ReadInt32BigEndian(framing);

                if (chunkLength <= 0 || chunkLength > part.ChunkSize)
                    throw new ControlProtocolException($"Peer sent an invalid chunk length of {chunkLength}.");

                var data = buffer.AsMemory(0, chunkLength);
                await stream.ReadExactlyAsync(data, cancellationToken);

                await stream.ReadExactlyAsync(framing, cancellationToken);
                var crc = BinaryPrimitives.ReadUInt32BigEndian(framing);

                // Throws ChunkVerificationException on mismatch; the bit stays clear,
                // so the chunk is simply re-requested on the next attempt.
                await part.WriteChunkAsync(chunkIndex, data, crc, cancellationToken);

                received += chunkLength;
                chunkIndex++;
                report(chunkLength);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }
}
```

Add `using Slipstream.Core.Control;` for `ControlProtocolException`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter BulkClientTests`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/BulkClient.cs windows/tests/Slipstream.Core.Tests/Transfer/BulkClientTests.cs
git commit -m "feat: add parallel-stream bulk client with automatic resume"
```

---

## Task 10: Folder expansion

**Files:**
- Create: `windows/src/Slipstream.Core/Transfer/FolderExpander.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/FolderExpanderTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed record TransferItem(string AbsolutePath, string RelativePath, long Size, bool IsDirectory)`
  - `static class FolderExpander { static IReadOnlyList<TransferItem> Expand(string rootPath); }`
  - Empty directories are preserved as items with `IsDirectory = true` and `Size = 0`. Relative paths always use `/` as the separator, so Windows and Android agree.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/FolderExpanderTests.cs`:

```csharp
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class FolderExpanderTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-folder-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private string Make(string relative, string content = "x")
    {
        var path = Path.Combine(_dir, relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, content);
        return path;
    }

    [Fact]
    public void Expands_a_flat_folder()
    {
        Make("a.txt");
        Make("b.txt");

        var items = FolderExpander.Expand(_dir);

        Assert.Equal(2, items.Count(i => !i.IsDirectory));
        Assert.Contains(items, i => i.RelativePath == "a.txt");
    }

    [Fact]
    public void Expands_nested_folders_with_forward_slash_relative_paths()
    {
        Make("photos/2026/holiday.jpg");

        var items = FolderExpander.Expand(_dir);
        var file = items.Single(i => !i.IsDirectory);

        Assert.Equal("photos/2026/holiday.jpg", file.RelativePath);
        Assert.DoesNotContain('\\', file.RelativePath);
    }

    [Fact]
    public void Records_file_sizes()
    {
        Make("sized.txt", new string('x', 1234));

        Assert.Equal(1234, FolderExpander.Expand(_dir).Single(i => !i.IsDirectory).Size);
    }

    [Fact]
    public void Preserves_empty_directories()
    {
        Directory.CreateDirectory(Path.Combine(_dir, "empty-one", "empty-two"));

        var directories = FolderExpander.Expand(_dir).Where(i => i.IsDirectory).ToList();

        Assert.Contains(directories, d => d.RelativePath == "empty-one/empty-two");
        Assert.All(directories, d => Assert.Equal(0, d.Size));
    }

    [Fact]
    public void A_single_file_path_expands_to_one_item()
    {
        var path = Make("solo.txt");

        var items = FolderExpander.Expand(path);

        Assert.Single(items);
        Assert.Equal("solo.txt", items[0].RelativePath);
        Assert.False(items[0].IsDirectory);
    }

    [Fact]
    public void Absolute_paths_point_at_the_real_files()
    {
        Make("real.txt", "content");

        var item = FolderExpander.Expand(_dir).Single(i => !i.IsDirectory);

        Assert.True(File.Exists(item.AbsolutePath));
        Assert.Equal("content", File.ReadAllText(item.AbsolutePath));
    }

    [Fact]
    public void An_empty_folder_yields_no_items()
    {
        Assert.Empty(FolderExpander.Expand(_dir));
    }

    [Fact]
    public void A_missing_path_throws()
    {
        Assert.Throws<DirectoryNotFoundException>(
            () => FolderExpander.Expand(Path.Combine(_dir, "nope")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter FolderExpanderTests`
Expected: FAIL — `FolderExpander` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Transfer/FolderExpander.cs`:

```csharp
namespace Slipstream.Core.Transfer;

public sealed record TransferItem(string AbsolutePath, string RelativePath, long Size, bool IsDirectory);

/// <summary>
/// Spec §7. Flattens a tree to relative paths so the receiver can recreate the
/// structure. Separators are normalised to '/' — Windows and Android must agree
/// on the wire representation.
/// </summary>
public static class FolderExpander
{
    public static IReadOnlyList<TransferItem> Expand(string rootPath)
    {
        if (File.Exists(rootPath))
        {
            var info = new FileInfo(rootPath);
            return [new TransferItem(info.FullName, info.Name, info.Length, IsDirectory: false)];
        }

        if (!Directory.Exists(rootPath))
            throw new DirectoryNotFoundException($"No file or folder at {rootPath}.");

        var root = new DirectoryInfo(rootPath).FullName.TrimEnd(Path.DirectorySeparatorChar);
        var items = new List<TransferItem>();

        foreach (var path in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            var info = new FileInfo(path);
            items.Add(new TransferItem(info.FullName, Relative(root, info.FullName), info.Length, false));
        }

        // Empty directories carry no files, so they must be listed explicitly or
        // they silently vanish on the receiving side.
        foreach (var path in Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories))
        {
            if (Directory.EnumerateFileSystemEntries(path).Any()) continue;
            items.Add(new TransferItem(path, Relative(root, path), 0, IsDirectory: true));
        }

        return items;
    }

    private static string Relative(string root, string fullPath) =>
        Path.GetRelativePath(root, fullPath).Replace(Path.DirectorySeparatorChar, '/');
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter FolderExpanderTests`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Transfer/FolderExpander.cs windows/tests/Slipstream.Core.Tests/Transfer/FolderExpanderTests.cs
git commit -m "feat: add folder tree expansion with normalised relative paths"
```

---

## Task 11: File browser — `list` and `stat`

**Files:**
- Create: `windows/src/Slipstream.Core/Files/FileBrowser.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Files/FileBrowserTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed record FileEntry(string Name, string Path, long Size, DateTimeOffset Modified, bool IsDirectory, string? Mime, string? ThumbnailToken)`
  - `sealed record ListResult(string Path, IReadOnlyList<FileEntry> Entries, bool Truncated)`
  - `sealed class FileBrowser { const int MaxEntries = 5000; ListResult List(string path, string sort = "name"); FileEntry? Stat(string path); IReadOnlyList<FileEntry> Roots(); }`

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Files/FileBrowserTests.cs`:

```csharp
using Slipstream.Core.Files;

namespace Slipstream.Core.Tests.Files;

public class FileBrowserTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-browse-").FullName;
    private readonly FileBrowser _browser = new();

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private void Make(string name, string content = "x") =>
        File.WriteAllText(Path.Combine(_dir, name), content);

    [Fact]
    public void Lists_files_and_directories()
    {
        Make("a.txt");
        Directory.CreateDirectory(Path.Combine(_dir, "sub"));

        var result = _browser.List(_dir);

        Assert.Equal(2, result.Entries.Count);
        Assert.Contains(result.Entries, e => e.Name == "a.txt" && !e.IsDirectory);
        Assert.Contains(result.Entries, e => e.Name == "sub" && e.IsDirectory);
    }

    [Fact]
    public void Directories_sort_before_files()
    {
        Make("aaa.txt");
        Directory.CreateDirectory(Path.Combine(_dir, "zzz"));

        var entries = _browser.List(_dir).Entries;

        Assert.True(entries[0].IsDirectory);
        Assert.Equal("zzz", entries[0].Name);
    }

    [Fact]
    public void Infers_mime_types_for_media()
    {
        Make("clip.mp4");
        Make("song.mp3");
        Make("photo.jpg");
        Make("mystery.zzz");

        var entries = _browser.List(_dir).Entries.ToDictionary(e => e.Name);

        Assert.Equal("video/mp4", entries["clip.mp4"].Mime);
        Assert.Equal("audio/mpeg", entries["song.mp3"].Mime);
        Assert.Equal("image/jpeg", entries["photo.jpg"].Mime);
        Assert.Equal("application/octet-stream", entries["mystery.zzz"].Mime);
    }

    [Fact]
    public void Directories_have_no_mime_type()
    {
        Directory.CreateDirectory(Path.Combine(_dir, "sub"));
        Assert.Null(_browser.List(_dir).Entries.Single().Mime);
    }

    [Fact]
    public void Sorts_by_size_and_by_modified_on_request()
    {
        Make("small.txt", "x");
        Make("large.txt", new string('x', 5000));

        Assert.Equal("large.txt", _browser.List(_dir, "size").Entries[0].Name);
        Assert.Equal(2, _browser.List(_dir, "modified").Entries.Count);
    }

    [Fact]
    public void Caps_at_five_thousand_entries_and_flags_truncation()
    {
        for (var i = 0; i < FileBrowser.MaxEntries + 10; i++)
            Make($"file-{i:D5}.txt");

        var result = _browser.List(_dir);

        Assert.Equal(FileBrowser.MaxEntries, result.Entries.Count);
        Assert.True(result.Truncated);
    }

    [Fact]
    public void Does_not_flag_truncation_below_the_cap()
    {
        Make("only.txt");
        Assert.False(_browser.List(_dir).Truncated);
    }

    [Fact]
    public void Stat_returns_metadata_for_a_file()
    {
        Make("target.mp4", new string('x', 999));

        var entry = _browser.Stat(Path.Combine(_dir, "target.mp4"));

        Assert.NotNull(entry);
        Assert.Equal(999, entry.Size);
        Assert.Equal("video/mp4", entry.Mime);
        Assert.False(entry.IsDirectory);
    }

    [Fact]
    public void Stat_returns_null_for_a_missing_path()
    {
        Assert.Null(_browser.Stat(Path.Combine(_dir, "nope.txt")));
    }

    [Fact]
    public void Listing_a_missing_directory_throws()
    {
        Assert.Throws<DirectoryNotFoundException>(() => _browser.List(Path.Combine(_dir, "nope")));
    }

    [Fact]
    public void Roots_returns_the_available_drives()
    {
        var roots = _browser.Roots();

        Assert.NotEmpty(roots);
        Assert.All(roots, r => Assert.True(r.IsDirectory));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter FileBrowserTests`
Expected: FAIL — `FileBrowser` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Files/FileBrowser.cs`:

```csharp
namespace Slipstream.Core.Files;

public sealed record FileEntry(
    string Name,
    string Path,
    long Size,
    DateTimeOffset Modified,
    bool IsDirectory,
    string? Mime,
    string? ThumbnailToken);

public sealed record ListResult(string Path, IReadOnlyList<FileEntry> Entries, bool Truncated);

/// <summary>
/// Spec §6. Listings cap at 5000 entries with an honest flag rather than silently
/// truncating — no real directory on either device approaches the cap.
/// </summary>
public sealed class FileBrowser
{
    public const int MaxEntries = 5000;

    private static readonly Dictionary<string, string> MimeByExtension = new(StringComparer.OrdinalIgnoreCase)
    {
        [".mp4"] = "video/mp4", [".mkv"] = "video/x-matroska", [".avi"] = "video/x-msvideo",
        [".mov"] = "video/quicktime", [".webm"] = "video/webm", [".m4v"] = "video/x-m4v",
        [".mp3"] = "audio/mpeg", [".flac"] = "audio/flac", [".wav"] = "audio/wav",
        [".m4a"] = "audio/mp4", [".ogg"] = "audio/ogg", [".opus"] = "audio/opus",
        [".jpg"] = "image/jpeg", [".jpeg"] = "image/jpeg", [".png"] = "image/png",
        [".gif"] = "image/gif", [".webp"] = "image/webp", [".heic"] = "image/heic",
        [".pdf"] = "application/pdf", [".txt"] = "text/plain", [".zip"] = "application/zip",
    };

    public ListResult List(string path, string sort = "name")
    {
        if (!Directory.Exists(path))
            throw new DirectoryNotFoundException($"No folder at {path}.");

        var entries = new List<FileEntry>();
        var truncated = false;

        foreach (var entryPath in Directory.EnumerateFileSystemEntries(path))
        {
            if (entries.Count >= MaxEntries) { truncated = true; break; }

            var entry = Describe(entryPath);
            if (entry is not null) entries.Add(entry);
        }

        return new ListResult(path, Sort(entries, sort), truncated);
    }

    public FileEntry? Stat(string path) =>
        File.Exists(path) || Directory.Exists(path) ? Describe(path) : null;

    public IReadOnlyList<FileEntry> Roots() =>
        DriveInfo.GetDrives()
            .Where(d => d.IsReady)
            .Select(d => new FileEntry(
                string.IsNullOrWhiteSpace(d.VolumeLabel) ? d.Name : $"{d.VolumeLabel} ({d.Name})",
                d.RootDirectory.FullName, 0, DateTimeOffset.MinValue, true, null, null))
            .ToList();

    private static FileEntry? Describe(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                var info = new DirectoryInfo(path);
                return new FileEntry(info.Name, info.FullName, 0,
                    new DateTimeOffset(info.LastWriteTimeUtc, TimeSpan.Zero), true, null, null);
            }

            var file = new FileInfo(path);
            return new FileEntry(file.Name, file.FullName, file.Length,
                new DateTimeOffset(file.LastWriteTimeUtc, TimeSpan.Zero), false,
                MimeFor(file.Extension), null);
        }
        catch (UnauthorizedAccessException)
        {
            return null; // Skip what we cannot read rather than failing the listing.
        }
        catch (IOException)
        {
            return null;
        }
    }

    private static string MimeFor(string extension) =>
        MimeByExtension.GetValueOrDefault(extension, "application/octet-stream");

    private static List<FileEntry> Sort(List<FileEntry> entries, string sort)
    {
        // Directories always lead, whatever the sort — that is how a file browser reads.
        var comparer = sort switch
        {
            "size" => (Comparison<FileEntry>)((a, b) => b.Size.CompareTo(a.Size)),
            "modified" => (a, b) => b.Modified.CompareTo(a.Modified),
            _ => (a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase),
        };

        entries.Sort((a, b) =>
            a.IsDirectory != b.IsDirectory ? (a.IsDirectory ? -1 : 1) : comparer(a, b));

        return entries;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter FileBrowserTests`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Files/FileBrowser.cs windows/tests/Slipstream.Core.Tests/Files/FileBrowserTests.cs
git commit -m "feat: add file browser with capped listings and mime inference"
```

---

## Task 12: Range header parsing and the media server

**Files:**
- Create: `windows/src/Slipstream.Core/Media/RangeHeader.cs`
- Create: `windows/src/Slipstream.Core/Media/MediaServer.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Media/RangeHeaderTests.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Media/MediaServerTests.cs`

**Interfaces:**
- Consumes: `TokenVault`, `LanGuard`, `FileBrowser`.
- Produces:
  - `readonly record struct ByteRangeSpec(long Start, long End) { long Length => End - Start + 1; }`
  - `static class RangeHeader { static bool TryParse(string? header, long fileSize, out ByteRangeSpec range); }`
  - `sealed class MediaServer : IAsyncDisposable { MediaServer(TokenVault vault, IPAddress bindAddress, int port); IPEndPoint ListenEndPoint { get; } string UrlFor(TransferToken token, IPAddress advertisedAddress); Task RunAsync(CancellationToken ct); Func<Guid, string?>? ThumbnailResolver { get; set; } }`
  - Routes: `GET /media/{token}` and `GET /thumb/{token}`.

- [ ] **Step 1: Write the range parser test**

Create `windows/tests/Slipstream.Core.Tests/Media/RangeHeaderTests.cs`:

```csharp
using Slipstream.Core.Media;

namespace Slipstream.Core.Tests.Media;

public class RangeHeaderTests
{
    [Fact]
    public void Parses_a_closed_range()
    {
        Assert.True(RangeHeader.TryParse("bytes=0-499", 1000, out var range));
        Assert.Equal(0, range.Start);
        Assert.Equal(499, range.End);
        Assert.Equal(500, range.Length);
    }

    [Fact]
    public void Parses_an_open_ended_range()
    {
        Assert.True(RangeHeader.TryParse("bytes=500-", 1000, out var range));
        Assert.Equal(500, range.Start);
        Assert.Equal(999, range.End);
    }

    [Fact]
    public void Parses_a_suffix_range()
    {
        Assert.True(RangeHeader.TryParse("bytes=-200", 1000, out var range));
        Assert.Equal(800, range.Start);
        Assert.Equal(999, range.End);
    }

    [Fact]
    public void Clamps_an_end_beyond_the_file()
    {
        Assert.True(RangeHeader.TryParse("bytes=900-5000", 1000, out var range));
        Assert.Equal(999, range.End);
    }

    [Fact]
    public void A_suffix_longer_than_the_file_yields_the_whole_file()
    {
        Assert.True(RangeHeader.TryParse("bytes=-5000", 1000, out var range));
        Assert.Equal(0, range.Start);
        Assert.Equal(999, range.End);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("items=0-100")]
    [InlineData("bytes=abc-def")]
    [InlineData("bytes=500-100")]   // inverted
    [InlineData("bytes=2000-3000")] // wholly past the end
    [InlineData("bytes=-")]
    public void Rejects_malformed_or_unsatisfiable_ranges(string? header)
    {
        Assert.False(RangeHeader.TryParse(header, 1000, out _));
    }

    [Fact]
    public void Takes_the_first_range_of_a_multi_range_request()
    {
        // Multipart responses are not supported; serving the first range is legal
        // and is what every media player actually sends anyway.
        Assert.True(RangeHeader.TryParse("bytes=0-99,200-299", 1000, out var range));
        Assert.Equal(0, range.Start);
        Assert.Equal(99, range.End);
    }
}
```

- [ ] **Step 2: Write the media server test**

Create `windows/tests/Slipstream.Core.Tests/Media/MediaServerTests.cs`:

```csharp
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Slipstream.Core.Media;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Media;

public class MediaServerTests : IAsyncLifetime
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-media-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly TokenVault _vault = new();
    private readonly HttpClient _http = new();

    private MediaServer _server = null!;
    private byte[] _data = null!;
    private TransferToken _token = null!;

    public Task InitializeAsync()
    {
        _data = RandomNumberGenerator.GetBytes(100_000);
        var path = Path.Combine(_dir, "movie.mp4");
        File.WriteAllBytes(path, _data);

        _token = _vault.IssueMedia(path, _data.Length);

        _server = new MediaServer(_vault, IPAddress.Loopback, port: 0);
        _ = _server.RunAsync(_cts.Token);

        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _server.DisposeAsync();
        _http.Dispose();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private string Url => $"http://{_server.ListenEndPoint}/media/{_token.Value:N}";

    [Fact]
    public async Task Serves_the_whole_file_with_a_200()
    {
        var response = await _http.GetAsync(Url, _cts.Token);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(_data, await response.Content.ReadAsByteArrayAsync(_cts.Token));
    }

    [Fact]
    public async Task Advertises_range_support()
    {
        var response = await _http.GetAsync(Url, _cts.Token);
        Assert.Contains("bytes", response.Headers.AcceptRanges);
    }

    [Fact]
    public async Task Serves_a_range_with_a_206_and_correct_bytes()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Url);
        request.Headers.Range = new RangeHeaderValue(1000, 1999);

        var response = await _http.SendAsync(request, _cts.Token);
        var body = await response.Content.ReadAsByteArrayAsync(_cts.Token);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal(1000, body.Length);
        Assert.Equal(_data[1000..2000], body);
        Assert.Equal(1000, response.Content.Headers.ContentRange!.From);
        Assert.Equal(1999, response.Content.Headers.ContentRange.To);
        Assert.Equal(_data.Length, response.Content.Headers.ContentRange.Length);
    }

    [Fact]
    public async Task Serves_an_open_ended_range()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Url);
        request.Headers.Range = new RangeHeaderValue(99_000, null);

        var response = await _http.SendAsync(request, _cts.Token);

        Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
        Assert.Equal(1000, (await response.Content.ReadAsByteArrayAsync(_cts.Token)).Length);
    }

    [Fact]
    public async Task Reports_the_content_type_from_the_extension()
    {
        var response = await _http.GetAsync(Url, _cts.Token);
        Assert.Equal("video/mp4", response.Content.Headers.ContentType!.MediaType);
    }

    [Fact]
    public async Task Rejects_an_unknown_token_with_404()
    {
        var response = await _http.GetAsync(
            $"http://{_server.ListenEndPoint}/media/{Guid.NewGuid():N}", _cts.Token);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Rejects_an_unsatisfiable_range_with_416()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Url);
        request.Headers.TryAddWithoutValidation("Range", "bytes=999999-1000000");

        var response = await _http.SendAsync(request, _cts.Token);

        Assert.Equal(HttpStatusCode.RequestedRangeNotSatisfiable, response.StatusCode);
    }

    [Fact]
    public async Task Rejects_an_unknown_route_with_404()
    {
        var response = await _http.GetAsync($"http://{_server.ListenEndPoint}/etc/passwd", _cts.Token);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public void UrlFor_builds_an_address_the_peer_can_reach()
    {
        var url = _server.UrlFor(_token, IPAddress.Parse("192.168.43.1"));

        Assert.StartsWith("http://192.168.43.1:", url);
        Assert.Contains($"/media/{_token.Value:N}", url);
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `dotnet test windows/Slipstream.sln --filter "RangeHeaderTests|MediaServerTests"`
Expected: FAIL — `RangeHeader` and `MediaServer` do not exist.

- [ ] **Step 4: Write the range parser**

Create `windows/src/Slipstream.Core/Media/RangeHeader.cs`:

```csharp
namespace Slipstream.Core.Media;

public readonly record struct ByteRangeSpec(long Start, long End)
{
    public long Length => End - Start + 1;
}

/// <summary>
/// RFC 7233 single-range parsing. Multipart ranges are not supported; the first
/// range is served, which is legal and is all any media player sends.
/// </summary>
public static class RangeHeader
{
    public static bool TryParse(string? header, long fileSize, out ByteRangeSpec range)
    {
        range = default;

        if (string.IsNullOrWhiteSpace(header)) return false;
        if (fileSize <= 0) return false;

        const string prefix = "bytes=";
        if (!header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;

        var first = header[prefix.Length..].Split(',')[0].Trim();
        var separator = first.IndexOf('-');
        if (separator < 0) return false;

        var startText = first[..separator].Trim();
        var endText = first[(separator + 1)..].Trim();

        long start;
        long end;

        if (startText.Length == 0)
        {
            // Suffix form: bytes=-N means the last N bytes.
            if (!long.TryParse(endText, out var suffixLength) || suffixLength <= 0) return false;

            start = Math.Max(0, fileSize - suffixLength);
            end = fileSize - 1;
        }
        else
        {
            if (!long.TryParse(startText, out start) || start < 0) return false;

            if (endText.Length == 0) end = fileSize - 1;
            else if (!long.TryParse(endText, out end)) return false;

            end = Math.Min(end, fileSize - 1);
        }

        if (start > end || start >= fileSize) return false;

        range = new ByteRangeSpec(start, end);
        return true;
    }
}
```

- [ ] **Step 5: Write the media server**

Create `windows/src/Slipstream.Core/Media/MediaServer.cs`:

```csharp
using System.Buffers;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Slipstream.Core.Net;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Media;

/// <summary>
/// Spec §8. A minimal HTTP/1.1 server with Range support. Hand-rolled rather than
/// hosted on Kestrel: the routes are two, and taking an ASP.NET Core dependency
/// for them would violate the no-extra-dependencies constraint.
/// </summary>
public sealed class MediaServer : IAsyncDisposable
{
    private const int StreamBufferBytes = 256 * 1024;

    private static readonly Dictionary<string, string> ContentTypes = new(StringComparer.OrdinalIgnoreCase)
    {
        [".mp4"] = "video/mp4", [".mkv"] = "video/x-matroska", [".webm"] = "video/webm",
        [".mov"] = "video/quicktime", [".avi"] = "video/x-msvideo", [".m4v"] = "video/x-m4v",
        [".mp3"] = "audio/mpeg", [".flac"] = "audio/flac", [".wav"] = "audio/wav",
        [".m4a"] = "audio/mp4", [".ogg"] = "audio/ogg", [".opus"] = "audio/opus",
        [".jpg"] = "image/jpeg", [".jpeg"] = "image/jpeg", [".png"] = "image/png",
    };

    private readonly TokenVault _vault;
    private readonly TcpListener _listener;

    public MediaServer(TokenVault vault, IPAddress bindAddress, int port)
    {
        LanGuard.EnsureLocal(bindAddress);

        _vault = vault;
        _listener = new TcpListener(bindAddress, port);
        _listener.Start();
    }

    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.LocalEndpoint;

    /// <summary>Resolves a thumbnail token to a cached JPEG path. Set by Task 13.</summary>
    public Func<Guid, string?>? ThumbnailResolver { get; set; }

    public string UrlFor(TransferToken token, IPAddress advertisedAddress) =>
        $"http://{advertisedAddress}:{ListenEndPoint.Port}/media/{token.Value:N}";

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (OperationCanceledException) { return; }
            catch (SocketException) { continue; }

            _ = HandleAsync(client, cancellationToken);
        }
    }

    private async Task HandleAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;

            var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
            if (!LanGuard.IsLocal(remote.Address)) return;

            await using var stream = client.GetStream();

            var (target, rangeHeader) = await ReadRequestAsync(stream, cancellationToken);
            if (target is null) { await WriteStatusAsync(stream, 400, "Bad request", cancellationToken); return; }

            var path = ResolveTarget(target);
            if (path is null || !File.Exists(path))
            {
                await WriteStatusAsync(stream, 404, "Not found", cancellationToken);
                return;
            }

            await ServeFileAsync(stream, path, rangeHeader, cancellationToken);
        }
        catch (Exception)
        {
            // Players close connections abruptly when seeking. Routine.
        }
        finally
        {
            client.Dispose();
        }
    }

    private string? ResolveTarget(string target)
    {
        if (target.StartsWith("/media/", StringComparison.Ordinal))
        {
            return Guid.TryParse(target["/media/".Length..], out var token)
                ? _vault.ValidateMedia(token)?.Path
                : null;
        }

        if (target.StartsWith("/thumb/", StringComparison.Ordinal))
        {
            return Guid.TryParse(target["/thumb/".Length..], out var token)
                ? ThumbnailResolver?.Invoke(token)
                : null;
        }

        return null;
    }

    private static async Task<(string? Target, string? Range)> ReadRequestAsync(
        Stream stream, CancellationToken cancellationToken)
    {
        var reader = new StreamReader(stream, Encoding.ASCII, false, 1024, leaveOpen: true);

        var requestLine = await reader.ReadLineAsync(cancellationToken);
        if (requestLine is null) return (null, null);

        var parts = requestLine.Split(' ');
        if (parts.Length < 2 || parts[0] != "GET") return (null, null);

        string? range = null;

        while (await reader.ReadLineAsync(cancellationToken) is { Length: > 0 } line)
        {
            if (line.StartsWith("Range:", StringComparison.OrdinalIgnoreCase))
                range = line["Range:".Length..].Trim();
        }

        return (parts[1], range);
    }

    private static async Task ServeFileAsync(
        Stream stream, string path, string? rangeHeader, CancellationToken cancellationToken)
    {
        var info = new FileInfo(path);
        var contentType = ContentTypes.GetValueOrDefault(info.Extension, "application/octet-stream");

        long start = 0;
        var length = info.Length;
        var status = 200;
        string? contentRange = null;

        if (rangeHeader is not null)
        {
            if (!RangeHeader.TryParse(rangeHeader, info.Length, out var requested))
            {
                await WriteStatusAsync(stream, 416, "Requested range not satisfiable", cancellationToken,
                    extraHeaders: $"Content-Range: bytes */{info.Length}\r\n");
                return;
            }

            start = requested.Start;
            length = requested.Length;
            status = 206;
            contentRange = $"bytes {requested.Start}-{requested.End}/{info.Length}";
        }

        var headers = new StringBuilder()
            .Append($"HTTP/1.1 {status} {(status == 206 ? "Partial Content" : "OK")}\r\n")
            .Append($"Content-Type: {contentType}\r\n")
            .Append($"Content-Length: {length}\r\n")
            .Append("Accept-Ranges: bytes\r\n")
            .Append("Cache-Control: no-store\r\n");

        if (contentRange is not null) headers.Append($"Content-Range: {contentRange}\r\n");
        headers.Append("Connection: close\r\n\r\n");

        await stream.WriteAsync(Encoding.ASCII.GetBytes(headers.ToString()), cancellationToken);

        using var file = new FileStream(path, new FileStreamOptions
        {
            Mode = FileMode.Open,
            Access = FileAccess.Read,
            Share = FileShare.Read,
            Options = FileOptions.Asynchronous | FileOptions.SequentialScan,
        });

        var buffer = ArrayPool<byte>.Shared.Rent(StreamBufferBytes);
        try
        {
            var offset = start;
            var remaining = length;

            while (remaining > 0)
            {
                var toRead = (int)Math.Min(buffer.Length, remaining);
                var read = await RandomAccess.ReadAsync(
                    file.SafeFileHandle, buffer.AsMemory(0, toRead), offset, cancellationToken);

                if (read == 0) break;

                await stream.WriteAsync(buffer.AsMemory(0, read), cancellationToken);

                offset += read;
                remaining -= read;
            }

            await stream.FlushAsync(cancellationToken);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static async Task WriteStatusAsync(
        Stream stream, int code, string reason, CancellationToken cancellationToken, string extraHeaders = "")
    {
        var response = $"HTTP/1.1 {code} {reason}\r\nContent-Length: 0\r\n{extraHeaders}Connection: close\r\n\r\n";
        await stream.WriteAsync(Encoding.ASCII.GetBytes(response), cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }

    public ValueTask DisposeAsync()
    {
        _listener.Stop();
        return ValueTask.CompletedTask;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter "RangeHeaderTests|MediaServerTests"`
Expected: PASS, 22 tests.

- [ ] **Step 7: Commit**

```bash
git add windows/src/Slipstream.Core/Media windows/tests/Slipstream.Core.Tests/Media
git commit -m "feat: add Range-capable media server"
```

---

## Task 13: Thumbnails

**Files:**
- Create: `windows/src/Slipstream.Core/Media/ThumbnailProvider.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Media/ThumbnailProviderTests.cs`

**Interfaces:**
- Consumes: `TokenVault`.
- Produces: `sealed class ThumbnailProvider { ThumbnailProvider(string cacheDirectory, TokenVault vault); const int LongEdgePixels = 256; string? Generate(string path); Guid? TokenFor(string path); string? Resolve(Guid token); }`
- `Generate` returns the cached JPEG path, or `null` when the shell has no thumbnail for that file.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Media/ThumbnailProviderTests.cs`:

```csharp
using System.Runtime.InteropServices;
using Slipstream.Core.Media;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Media;

public class ThumbnailProviderTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-thumb-").FullName;
    private readonly string _cache;
    private readonly ThumbnailProvider _provider;

    public ThumbnailProviderTests()
    {
        _cache = Path.Combine(_dir, "cache");
        _provider = new ThumbnailProvider(_cache, new TokenVault());
    }

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    /// <summary>A 2x2 PNG — the shell reliably produces a thumbnail for a real image.</summary>
    private string MakeImage(string name = "pic.png")
    {
        var path = Path.Combine(_dir, name);
        File.WriteAllBytes(path, Convert.FromBase64String(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP8z" +
            "8Dwn4GBgYERRAAAGgcCAaqqZaEAAAAASUVORK5CYII="));
        return path;
    }

    [Fact]
    public void Generates_a_thumbnail_for_an_image()
    {
        if (!OperatingSystem.IsWindows()) return;

        var thumbnail = _provider.Generate(MakeImage());

        Assert.NotNull(thumbnail);
        Assert.True(File.Exists(thumbnail));
        Assert.True(new FileInfo(thumbnail).Length > 0);
    }

    [Fact]
    public void Caches_by_path_size_and_mtime()
    {
        if (!OperatingSystem.IsWindows()) return;

        var path = MakeImage();

        var first = _provider.Generate(path);
        var firstWritten = File.GetLastWriteTimeUtc(first!);

        var second = _provider.Generate(path);

        Assert.Equal(first, second);
        Assert.Equal(firstWritten, File.GetLastWriteTimeUtc(second!)); // not regenerated
    }

    [Fact]
    public void A_modified_file_gets_a_new_cache_entry()
    {
        if (!OperatingSystem.IsWindows()) return;

        var path = MakeImage();
        var first = _provider.Generate(path);

        File.AppendAllText(path, "changed");
        var second = _provider.Generate(path);

        Assert.NotEqual(first, second);
    }

    [Fact]
    public void Returns_null_for_a_file_with_no_thumbnail()
    {
        var path = Path.Combine(_dir, "empty.zzz");
        File.WriteAllBytes(path, []);

        Assert.Null(_provider.Generate(path));
    }

    [Fact]
    public void Returns_null_for_a_missing_file()
    {
        Assert.Null(_provider.Generate(Path.Combine(_dir, "nope.png")));
    }

    [Fact]
    public void TokenFor_then_Resolve_round_trips()
    {
        if (!OperatingSystem.IsWindows()) return;

        var token = _provider.TokenFor(MakeImage());

        Assert.NotNull(token);
        Assert.True(File.Exists(_provider.Resolve(token.Value)));
    }

    [Fact]
    public void Resolve_returns_null_for_an_unknown_token()
    {
        Assert.Null(_provider.Resolve(Guid.NewGuid()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter ThumbnailProviderTests`
Expected: FAIL — `ThumbnailProvider` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Media/ThumbnailProvider.cs`:

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
/// Spec §9. Generated on the owning device via the Windows shell thumbnail
/// provider, which covers video, documents, and images uniformly — anything with a
/// registered handler. Cached by (path, mtime, size).
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

            bitmap.Save(cached, ImageFormat.Jpeg);
            return cached;
        }
        catch (Exception)
        {
            // No registered handler, a corrupt file, or a COM failure. No thumbnail
            // is a normal outcome — the UI shows a neutral placeholder.
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
                // Copy out of the shell's bitmap before releasing it.
                using var shellBitmap = Image.FromHbitmap(handle);
                return new Bitmap(shellBitmap);
            }
            finally
            {
                DeleteObject(handle);
            }
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

Add `<UseWindowsForms>false</UseWindowsForms>` is not needed, but `System.Drawing.Common` is required. It is a Microsoft first-party package shipped as part of the Windows Desktop workload — add it to `Slipstream.Core.csproj`:

```xml
<ItemGroup>
  <PackageReference Include="System.Drawing.Common" Version="9.0.0" />
</ItemGroup>
```

This is a first-party Microsoft package, consistent with the Task-3 amendment: the constraint bars *third-party* runtime dependencies, not the BCL's out-of-band packages.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter ThumbnailProviderTests`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Media/ThumbnailProvider.cs windows/src/Slipstream.Core/Slipstream.Core.csproj windows/tests/Slipstream.Core.Tests/Media/ThumbnailProviderTests.cs
git commit -m "feat: add shell-backed thumbnail generation with content-keyed cache"
```

---

## Task 14: Playlist launcher and the play handler

The spec's §8 Windows gotcha, made executable.

**Files:**
- Create: `windows/src/Slipstream.Core/Platform/PlaylistLauncher.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Platform/PlaylistLauncherTests.cs`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed record PlayRequest(string Url, string Title, string? Mime)`
  - `sealed class PlaylistLauncher { PlaylistLauncher(string tempDirectory); string WritePlaylist(PlayRequest request); LaunchStrategy Choose(); void Launch(PlayRequest request); }`
  - `enum LaunchStrategy { Playlist, KnownPlayer, Url }`
  - `IReadOnlyList<string> KnownPlayerPaths { get; init; }` — injectable so the strategy choice is testable without installing players.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Platform/PlaylistLauncherTests.cs`:

```csharp
using Slipstream.Core.Platform;

namespace Slipstream.Core.Tests.Platform;

public class PlaylistLauncherTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-play-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private static PlayRequest Request() =>
        new("http://192.168.43.1:53323/media/abc123", "Holiday video", "video/mp4");

    [Fact]
    public void Writes_an_m3u_containing_the_url()
    {
        var path = new PlaylistLauncher(_dir).WritePlaylist(Request());

        Assert.EndsWith(".m3u", path);
        Assert.Contains("http://192.168.43.1:53323/media/abc123", File.ReadAllText(path));
    }

    [Fact]
    public void The_playlist_carries_the_title_as_extinf()
    {
        var content = File.ReadAllText(new PlaylistLauncher(_dir).WritePlaylist(Request()));

        Assert.StartsWith("#EXTM3U", content);
        Assert.Contains("#EXTINF:-1,Holiday video", content);
    }

    [Fact]
    public void Each_playlist_gets_a_distinct_filename()
    {
        var launcher = new PlaylistLauncher(_dir);

        Assert.NotEqual(launcher.WritePlaylist(Request()), launcher.WritePlaylist(Request()));
    }

    [Fact]
    public void A_title_with_newlines_cannot_corrupt_the_playlist()
    {
        var request = Request() with { Title = "Bad\r\nhttp://evil.example/x" };
        var content = File.ReadAllText(new PlaylistLauncher(_dir).WritePlaylist(request));

        Assert.DoesNotContain("evil.example", content.Split('\n')[2]);
        Assert.Equal(3, content.Split('\n', StringSplitOptions.RemoveEmptyEntries).Length);
    }

    [Fact]
    public void Falls_back_to_a_known_player_when_no_playlist_handler_exists()
    {
        var fakePlayer = Path.Combine(_dir, "vlc.exe");
        File.WriteAllText(fakePlayer, "");

        var launcher = new PlaylistLauncher(_dir)
        {
            KnownPlayerPaths = [fakePlayer],
            HasPlaylistHandler = () => false,
        };

        Assert.Equal(LaunchStrategy.KnownPlayer, launcher.Choose());
    }

    [Fact]
    public void Prefers_the_playlist_handler_when_one_is_registered()
    {
        var launcher = new PlaylistLauncher(_dir) { HasPlaylistHandler = () => true };

        Assert.Equal(LaunchStrategy.Playlist, launcher.Choose());
    }

    [Fact]
    public void Falls_back_to_the_url_when_nothing_else_is_available()
    {
        var launcher = new PlaylistLauncher(_dir)
        {
            KnownPlayerPaths = [Path.Combine(_dir, "not-installed.exe")],
            HasPlaylistHandler = () => false,
        };

        Assert.Equal(LaunchStrategy.Url, launcher.Choose());
    }
}
```

The newline test is a genuine injection guard: the title arrives from the peer, and an unescaped newline would let it inject an extra entry into the playlist.

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter PlaylistLauncherTests`
Expected: FAIL — `PlaylistLauncher` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Platform/PlaylistLauncher.cs`:

```csharp
using System.Diagnostics;
using System.Runtime.Versioning;
using System.Text;
using Microsoft.Win32;

namespace Slipstream.Core.Platform;

public sealed record PlayRequest(string Url, string Title, string? Mime);

public enum LaunchStrategy
{
    Playlist,
    KnownPlayer,
    Url,
}

/// <summary>
/// Spec §8. Handing a bare http:// URL to ShellExecute opens the default *browser*,
/// not the default media player. Writing a one-line .m3u and launching that resolves
/// through the playlist handler instead, which is a media player.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class PlaylistLauncher(string tempDirectory)
{
    public IReadOnlyList<string> KnownPlayerPaths { get; init; } =
    [
        @"C:\Program Files\VideoLAN\VLC\vlc.exe",
        @"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
        @"C:\Program Files\MPC-HC\mpc-hc64.exe",
        @"C:\Program Files\DAUM\PotPlayer\PotPlayerMini64.exe",
    ];

    /// <summary>Injectable so the strategy choice is testable without installing a player.</summary>
    public Func<bool> HasPlaylistHandler { get; init; } = DefaultHasPlaylistHandler;

    public string WritePlaylist(PlayRequest request)
    {
        Directory.CreateDirectory(tempDirectory);

        var path = Path.Combine(tempDirectory, $"slipstream-{Guid.NewGuid():N}.m3u");

        // The title arrives from the peer: strip anything that could inject a line.
        var safeTitle = request.Title.ReplaceLineEndings(" ").Trim();

        var content = new StringBuilder()
            .Append("#EXTM3U\n")
            .Append($"#EXTINF:-1,{safeTitle}\n")
            .Append(request.Url)
            .Append('\n');

        File.WriteAllText(path, content.ToString(), Encoding.UTF8);
        return path;
    }

    public LaunchStrategy Choose()
    {
        if (HasPlaylistHandler()) return LaunchStrategy.Playlist;
        if (KnownPlayerPaths.Any(File.Exists)) return LaunchStrategy.KnownPlayer;

        return LaunchStrategy.Url;
    }

    public void Launch(PlayRequest request)
    {
        switch (Choose())
        {
            case LaunchStrategy.Playlist:
                Start(WritePlaylist(request), arguments: null);
                break;

            case LaunchStrategy.KnownPlayer:
                Start(KnownPlayerPaths.First(File.Exists), $"\"{request.Url}\"");
                break;

            default:
                Start(request.Url, arguments: null);
                break;
        }
    }

    private static void Start(string target, string? arguments) =>
        Process.Start(new ProcessStartInfo(target)
        {
            Arguments = arguments ?? string.Empty,
            UseShellExecute = true,
        });

    private static bool DefaultHasPlaylistHandler()
    {
        try
        {
            using var key = Registry.ClassesRoot.OpenSubKey(".m3u");
            var handler = key?.GetValue(null) as string;

            if (string.IsNullOrWhiteSpace(handler)) return false;

            using var command = Registry.ClassesRoot.OpenSubKey($@"{handler}\shell\open\command");
            return command?.GetValue(null) is string { Length: > 0 };
        }
        catch (Exception)
        {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter PlaylistLauncherTests`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Platform/PlaylistLauncher.cs windows/tests/Slipstream.Core.Tests/Platform/PlaylistLauncherTests.cs
git commit -m "feat: launch streams through a playlist handler, not the browser"
```

---

## Task 15: Control handlers — browse, transfer, play, clipboard

**Files:**
- Create: `windows/src/Slipstream.Core/Control/Handlers/SlipstreamSession.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Control/SlipstreamSessionTests.cs`

**Interfaces:**
- Consumes: everything above, plus Plan 1's `ControlConnection` and `ControlMessage`.
- Produces:
  - Payload records: `ListRequest(string Path, string? Sort)`, `ListResponse(string Path, IReadOnlyList<FileEntry> Entries, bool Truncated)`, `StatRequest(string Path)`, `PullRequest(string Path)`, `PullResponse(string TransferId, string Token, long Size, int ChunkSize, int Streams, string Name)`, `PlayMessage(string Url, string Title, string? Mime)`, `ClipboardMessage(string Text)`
  - `sealed class SlipstreamSession { SlipstreamSession(DeviceIdentity identity, FileBrowser browser, TokenVault vault, MediaServer media, ThumbnailProvider thumbnails, PlaylistLauncher launcher, IPAddress advertisedAddress, int streamCount); Task<ControlMessage?> HandleAsync(ControlMessage message, CancellationToken ct); string? LastClipboardText { get; } }`
  - `HandleAsync` returns `null` for messages it does not recognise — the Global Constraint that unknown types are ignored.
  - `const int ClipboardMaxBytes = 65_536`.
  - **`hello` must be handled here.** Plan 1's harness answered `hello` inline in its own `PeerConnected` handler. Once this session owns the connection (Task 16), that handler is replaced — so without a `hello` case the handshake silently stops being answered, with no error anywhere. `HelloPayload` already exists in `ControlServer.cs`; do not redeclare it.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Control/SlipstreamSessionTests.cs`:

```csharp
using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;
using Slipstream.Core.Files;
using Slipstream.Core.Media;
using Slipstream.Core.Platform;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Control;

public class SlipstreamSessionTests : IAsyncLifetime
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-session-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(30));
    private readonly TokenVault _vault = new();

    private MediaServer _media = null!;
    private DeviceIdentity _identity = null!;
    private SlipstreamSession _session = null!;

    public Task InitializeAsync()
    {
        File.WriteAllText(Path.Combine(_dir, "notes.txt"), "hello");
        File.WriteAllBytes(Path.Combine(_dir, "movie.mp4"), new byte[50_000]);

        _media = new MediaServer(_vault, IPAddress.Loopback, port: 0);
        _ = _media.RunAsync(_cts.Token);

        _identity = DeviceIdentity.CreateNew("Test PC");

        _session = new SlipstreamSession(
            _identity, new FileBrowser(), _vault, _media,
            new ThumbnailProvider(Path.Combine(_dir, "thumbs"), _vault),
            new PlaylistLauncher(Path.Combine(_dir, "temp")) { HasPlaylistHandler = () => true },
            IPAddress.Loopback, streamCount: 4);

        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _media.DisposeAsync();
        _cts.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public async Task List_returns_the_directory_contents()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("list", "1", new ListRequest(_dir, null)), _cts.Token);

        Assert.Equal("list.ok", reply!.Type);
        Assert.Equal("1", reply.Id);

        var payload = reply.PayloadAs<ListResponse>()!;
        Assert.Equal(2, payload.Entries.Count);
        Assert.False(payload.Truncated);
    }

    [Fact]
    public async Task List_of_a_missing_folder_returns_an_error_message()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("list", "1", new ListRequest(Path.Combine(_dir, "nope"), null)), _cts.Token);

        Assert.Equal("list.error", reply!.Type);
    }

    [Fact]
    public async Task Stat_returns_metadata()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("stat", "2", new StatRequest(Path.Combine(_dir, "movie.mp4"))), _cts.Token);

        Assert.Equal("stat.ok", reply!.Type);
        Assert.Equal(50_000, reply.PayloadAs<FileEntry>()!.Size);
    }

    [Fact]
    public async Task Pull_request_issues_a_usable_bulk_token()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("pull.request", "3", new PullRequest(Path.Combine(_dir, "movie.mp4"))), _cts.Token);

        Assert.Equal("pull.ok", reply!.Type);

        var payload = reply.PayloadAs<PullResponse>()!;
        Assert.Equal(50_000, payload.Size);
        Assert.Equal(4, payload.Streams);
        Assert.Equal("movie.mp4", payload.Name);

        Assert.NotNull(_vault.ValidateBulk(Guid.Parse(payload.Token), Guid.Parse(payload.TransferId)));
    }

    [Fact]
    public async Task Pull_request_for_a_missing_file_returns_an_error()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("pull.request", "3", new PullRequest(Path.Combine(_dir, "nope.bin"))), _cts.Token);

        Assert.Equal("pull.error", reply!.Type);
    }

    [Fact]
    public async Task Stream_request_returns_a_reachable_media_url()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("stream.request", "4", new StatRequest(Path.Combine(_dir, "movie.mp4"))), _cts.Token);

        Assert.Equal("stream.ok", reply!.Type);

        var url = reply.PayloadAs<PlayMessage>()!.Url;
        Assert.StartsWith($"http://127.0.0.1:{_media.ListenEndPoint.Port}/media/", url);

        using var http = new HttpClient();
        Assert.True((await http.GetAsync(url, _cts.Token)).IsSuccessStatusCode);
    }

    [Fact]
    public async Task Clipboard_stores_the_text_and_acknowledges()
    {
        var reply = await _session.HandleAsync(
            ControlMessage.Request("clipboard", "5", new ClipboardMessage("copied text")), _cts.Token);

        Assert.Equal("clipboard.ok", reply!.Type);
        Assert.Equal("copied text", _session.LastClipboardText);
    }

    [Fact]
    public async Task Clipboard_rejects_an_oversized_payload()
    {
        var oversized = new string('x', SlipstreamSession.ClipboardMaxBytes + 1);

        var reply = await _session.HandleAsync(
            ControlMessage.Request("clipboard", "6", new ClipboardMessage(oversized)), _cts.Token);

        Assert.Equal("clipboard.error", reply!.Type);
        Assert.Null(_session.LastClipboardText);
    }

    [Fact]
    public async Task Hello_is_answered_with_this_devices_identity()
    {
        // Plan 1's harness answered hello inline. Once the session owns the
        // connection, this case is the only thing keeping the handshake alive.
        var reply = await _session.HandleAsync(
            ControlMessage.Request("hello", "0", new HelloPayload(
                SlipstreamPorts.ProtocolVersion, "peer-device", "Test Phone", "deadbeef")),
            _cts.Token);

        Assert.Equal("hello.ok", reply!.Type);
        Assert.Equal("0", reply.Id);

        var payload = reply.PayloadAs<HelloPayload>()!;
        Assert.Equal(SlipstreamPorts.ProtocolVersion, payload.Version);
        Assert.Equal(_identity.DeviceId, payload.DeviceId);
        Assert.Equal(_identity.Fingerprint, payload.Fingerprint);
    }

    [Fact]
    public async Task Ping_is_answered_with_pong()
    {
        var reply = await _session.HandleAsync(ControlMessage.Request("ping", "7"), _cts.Token);

        Assert.Equal("pong", reply!.Type);
        Assert.Equal("7", reply.Id);
    }

    [Fact]
    public async Task An_unknown_message_type_is_ignored()
    {
        Assert.Null(await _session.HandleAsync(
            ControlMessage.Request("something.from.the.future", "8"), _cts.Token));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter SlipstreamSessionTests`
Expected: FAIL — `SlipstreamSession` does not exist.

- [ ] **Step 3: Write the implementation**

Create `windows/src/Slipstream.Core/Control/Handlers/SlipstreamSession.cs`:

```csharp
using System.Net;
using System.Runtime.Versioning;
using System.Text;
using Slipstream.Core.Files;
using Slipstream.Core.Media;
using Slipstream.Core.Platform;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Control.Handlers;

public sealed record ListRequest(string Path, string? Sort);
public sealed record ListResponse(string Path, IReadOnlyList<FileEntry> Entries, bool Truncated);
public sealed record StatRequest(string Path);
public sealed record PullRequest(string Path);
public sealed record PullResponse(string TransferId, string Token, long Size, int ChunkSize, int Streams, string Name);
public sealed record ErrorResponse(string Message);

/// <summary>
/// Spec §6, §8, §10. Dispatches control messages for one connected peer.
/// Unknown types return null and are ignored, never fatal.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class SlipstreamSession(
    DeviceIdentity identity,
    FileBrowser browser,
    TokenVault vault,
    MediaServer media,
    ThumbnailProvider thumbnails,
    PlaylistLauncher launcher,
    IPAddress advertisedAddress,
    int streamCount)
{
    public const int ClipboardMaxBytes = 65_536;
    public const int ChunkSize = 1_048_576;

    public string? LastClipboardText { get; private set; }

    public Task<ControlMessage?> HandleAsync(ControlMessage message, CancellationToken cancellationToken) =>
        Task.FromResult(Dispatch(message));

    private ControlMessage? Dispatch(ControlMessage message) => message.Type switch
    {
        "hello" => HandleHello(message),
        "ping" => ControlMessage.Response("pong", message.Id!),
        "list" => HandleList(message),
        "stat" => HandleStat(message),
        "pull.request" => HandlePull(message),
        "stream.request" => HandleStream(message),
        "play" => HandlePlay(message),
        "clipboard" => HandleClipboard(message),
        _ => null, // A peer on a newer protocol version degrades, it does not break.
    };

    /// <summary>
    /// Version negotiation and device info. `HelloPayload` is declared in
    /// `ControlServer.cs` (Plan 1) — do not redeclare it here.
    /// </summary>
    private ControlMessage HandleHello(ControlMessage message) =>
        ControlMessage.Response("hello.ok", message.Id ?? "0", new HelloPayload(
            SlipstreamPorts.ProtocolVersion,
            identity.DeviceId,
            identity.DisplayName,
            identity.Fingerprint));

    private ControlMessage HandleList(ControlMessage message)
    {
        var request = message.PayloadAs<ListRequest>();
        if (request is null) return Error(message, "list.error", "Missing request payload.");

        try
        {
            var result = browser.List(request.Path, request.Sort ?? "name");

            // Thumbnail tokens, never inline image data — listings stay small.
            var entries = result.Entries
                .Select(e => e.IsDirectory
                    ? e
                    : e with { ThumbnailToken = thumbnails.TokenFor(e.Path)?.ToString("N") })
                .ToList();

            return ControlMessage.Response("list.ok", message.Id!,
                new ListResponse(result.Path, entries, result.Truncated));
        }
        catch (DirectoryNotFoundException)
        {
            return Error(message, "list.error", "That folder is no longer there.");
        }
        catch (UnauthorizedAccessException)
        {
            return Error(message, "list.error", "Slipstream cannot read that folder.");
        }
    }

    private ControlMessage HandleStat(ControlMessage message)
    {
        var request = message.PayloadAs<StatRequest>();
        if (request is null) return Error(message, "stat.error", "Missing request payload.");

        var entry = browser.Stat(request.Path);

        return entry is null
            ? Error(message, "stat.error", "That file is no longer there.")
            : ControlMessage.Response("stat.ok", message.Id!, entry);
    }

    private ControlMessage HandlePull(ControlMessage message)
    {
        var request = message.PayloadAs<PullRequest>();
        if (request is null) return Error(message, "pull.error", "Missing request payload.");
        if (!File.Exists(request.Path)) return Error(message, "pull.error", "That file is no longer there.");

        var info = new FileInfo(request.Path);
        var transferId = Guid.NewGuid();
        var streams = Math.Clamp(streamCount, 1, 8);

        var token = vault.IssueBulk(transferId, info.FullName, info.Length, streams);

        return ControlMessage.Response("pull.ok", message.Id!, new PullResponse(
            transferId.ToString("N"), token.Value.ToString("N"),
            info.Length, ChunkSize, streams, info.Name));
    }

    private ControlMessage HandleStream(ControlMessage message)
    {
        var request = message.PayloadAs<StatRequest>();
        if (request is null) return Error(message, "stream.error", "Missing request payload.");
        if (!File.Exists(request.Path)) return Error(message, "stream.error", "That file is no longer there.");

        var info = new FileInfo(request.Path);
        var token = vault.IssueMedia(info.FullName, info.Length);

        return ControlMessage.Response("stream.ok", message.Id!, new PlayMessage(
            media.UrlFor(token, advertisedAddress), info.Name, browser.Stat(info.FullName)?.Mime));
    }

    private ControlMessage? HandlePlay(ControlMessage message)
    {
        var request = message.PayloadAs<PlayMessage>();
        if (request is null) return null;

        // Fire and forget, per spec §8 — no remote control, no position sync.
        launcher.Launch(new PlayRequest(request.Url, request.Title, request.Mime));

        return message.Id is null ? null : ControlMessage.Response("play.ok", message.Id);
    }

    private ControlMessage HandleClipboard(ControlMessage message)
    {
        var request = message.PayloadAs<ClipboardMessage>();
        if (request is null) return Error(message, "clipboard.error", "Missing request payload.");

        if (Encoding.UTF8.GetByteCount(request.Text) > ClipboardMaxBytes)
            return Error(message, "clipboard.error", "That text is too large to send.");

        LastClipboardText = request.Text;

        return ControlMessage.Response("clipboard.ok", message.Id!);
    }

    private static ControlMessage Error(ControlMessage request, string type, string message) =>
        ControlMessage.Response(type, request.Id ?? "0", new ErrorResponse(message));
}

public sealed record PlayMessage(string Url, string Title, string? Mime);
public sealed record ClipboardMessage(string Text);
```

Add `using Slipstream.Core.Identity;` for `DeviceIdentity`.

Every error string follows spec §15: direct, no apology, names what happened.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter SlipstreamSessionTests`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add windows/src/Slipstream.Core/Control/Handlers windows/tests/Slipstream.Core.Tests/Control/SlipstreamSessionTests.cs
git commit -m "feat: add control handlers for browse, pull, stream, play, and clipboard"
```

---

## Task 16: Wire-up, network-change resume, throughput gate, and real-machine verification

**Files:**
- Modify: `windows/src/Slipstream.Core/SlipstreamPeer.cs` — host the bulk and media servers, run the session dispatcher, rediscover on network change
- Create: `windows/src/Slipstream.Core/Transfer/TransferEngine.cs`
- Create: `windows/bench/Slipstream.Bench/Slipstream.Bench.csproj`
- Create: `windows/bench/Slipstream.Bench/Program.cs`
- Test: `windows/tests/Slipstream.Core.Tests/Transfer/TransferEngineTests.cs`
- Modify: `windows/tools/Slipstream.Harness/Program.cs` — add `pull`, `stream`, `send-text`

**Interfaces:**
- Produces:
  - `sealed class TransferEngine { TransferEngine(ControlClient client, BulkClient bulk, string downloadDirectory, int streamCount); Task<string> PullAsync(ControlConnection control, IPEndPoint peerEndpoint, string remotePath, IProgress<TransferProgress>? progress, CancellationToken ct); }`
  - `PullAsync` sends `pull.request`, opens a `PartFile`, downloads, completes, and returns the local path. Retries once through a reconnect if the connection drops mid-transfer.

- [ ] **Step 1: Write the failing test**

Create `windows/tests/Slipstream.Core.Tests/Transfer/TransferEngineTests.cs`. This is the full round trip through two real peers:

```csharp
using System.Net;
using System.Security.Cryptography;
using Slipstream.Core;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class TransferEngineTests : IAsyncLifetime
{
    private readonly string _root = Directory.CreateTempSubdirectory("slipstream-engine-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));

    private TwoPeers _peers = null!;
    private byte[] _payload = null!;
    private string _sourcePath = null!;

    public async Task InitializeAsync()
    {
        _payload = RandomNumberGenerator.GetBytes(12 * 1024 * 1024); // 12 MB: forces range splitting
        _sourcePath = Path.Combine(_root, "shared", "big.bin");
        Directory.CreateDirectory(Path.GetDirectoryName(_sourcePath)!);
        await File.WriteAllBytesAsync(_sourcePath, _payload, _cts.Token);

        _peers = await TwoPeers.StartAsync(_root, _cts.Token);
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _peers.DisposeAsync();
        _cts.Dispose();
        Directory.Delete(_root, recursive: true);
    }

    [Fact]
    public async Task Pulls_a_file_byte_identically_across_two_peers()
    {
        var local = await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

        Assert.Equal(_payload, await File.ReadAllBytesAsync(local, _cts.Token));
    }

    [Fact]
    public async Task Reports_progress_reaching_the_full_size()
    {
        var reports = new List<TransferProgress>();
        var progress = new Progress<TransferProgress>(p => { lock (reports) reports.Add(p); });

        await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, progress, _cts.Token);

        await Task.Delay(200, _cts.Token);

        lock (reports)
        {
            Assert.NotEmpty(reports);
            Assert.Equal(_payload.Length, reports.Max(r => r.BytesCompleted));
            Assert.True(reports.Max(r => r.BytesPerSecond) > 0);
        }
    }

    [Fact]
    public async Task Lands_the_file_in_the_download_directory_under_its_own_name()
    {
        var local = await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

        Assert.Equal("big.bin", Path.GetFileName(local));
        Assert.StartsWith(_peers.Client.DownloadDirectory, local);
    }

    [Fact]
    public async Task Leaves_no_part_file_behind_on_success()
    {
        await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

        Assert.Empty(Directory.GetFiles(_peers.Client.DownloadDirectory, "*.slipstream-part"));
    }
}
```

- [ ] **Step 2: Write the two-peer test harness**

Create `windows/tests/Slipstream.Core.Tests/TwoPeers.cs`. Every integration test from here on uses it:

```csharp
using System.Net;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

namespace Slipstream.Core.Tests;

/// <summary>
/// Two fully wired peers on loopback, already paired with each other and connected.
/// </summary>
public sealed class TwoPeers : IAsyncDisposable
{
    public required SlipstreamPeer Server { get; init; }
    public required SlipstreamPeer Client { get; init; }
    public required ControlConnection Connection { get; init; }
    public required IPEndPoint ServerEndPoint { get; init; }

    public static async Task<TwoPeers> StartAsync(string rootDirectory, CancellationToken cancellationToken)
    {
        var server = new SlipstreamPeer(Path.Combine(rootDirectory, "server-state"), "Server PC")
        {
            BindAddress = IPAddress.Loopback,
            DownloadDirectory = Path.Combine(rootDirectory, "server-downloads"),
            UseEphemeralPorts = true,
        };

        var client = new SlipstreamPeer(Path.Combine(rootDirectory, "client-state"), "Client Phone")
        {
            BindAddress = IPAddress.Loopback,
            DownloadDirectory = Path.Combine(rootDirectory, "client-downloads"),
            UseEphemeralPorts = true,
        };

        server.Peers.Pair(new PairedPeer(client.Identity.DeviceId, client.Identity.Fingerprint, "Client Phone", DateTimeOffset.UtcNow));
        client.Peers.Pair(new PairedPeer(server.Identity.DeviceId, server.Identity.Fingerprint, "Server PC", DateTimeOffset.UtcNow));

        _ = server.StartAsync(cancellationToken);
        _ = client.StartAsync(cancellationToken);

        await Task.Delay(400, cancellationToken);

        var connection = await client.Client.ConnectAsync(
            server.Server.ListenEndPoint, TimeSpan.FromSeconds(10), cancellationToken)
            ?? throw new InvalidOperationException("The two peers failed to connect.");

        return new TwoPeers
        {
            Server = server,
            Client = client,
            Connection = connection,
            ServerEndPoint = server.Server.ListenEndPoint,
        };
    }

    public async ValueTask DisposeAsync()
    {
        await Connection.DisposeAsync();
        await Client.DisposeAsync();
        await Server.DisposeAsync();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `dotnet test windows/Slipstream.sln --filter TransferEngineTests`
Expected: FAIL — `TransferEngine`, and `SlipstreamPeer`'s new members, do not exist.

- [ ] **Step 4: Write the transfer engine**

Create `windows/src/Slipstream.Core/Transfer/TransferEngine.cs`:

```csharp
using System.Net;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;

namespace Slipstream.Core.Transfer;

/// <summary>
/// Spec §7 orchestration: ask over the control channel, pull over the bulk channel,
/// verify, complete. Resume is inherited from PartFile, so a retry after any
/// interruption continues rather than restarts.
/// </summary>
public sealed class TransferEngine(
    ControlClient client, BulkClient bulk, string downloadDirectory, int streamCount)
{
    public async Task<string> PullAsync(
        ControlConnection control,
        IPEndPoint peerEndpoint,
        string remotePath,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        var requestId = Guid.NewGuid().ToString("N")[..8];
        await control.SendAsync(ControlMessage.Request("pull.request", requestId, new PullRequest(remotePath)), cancellationToken);

        var reply = await AwaitReplyAsync(control, requestId, cancellationToken);

        if (reply.Type != "pull.ok")
            throw new ControlProtocolException(
                reply.PayloadAs<ErrorResponse>()?.Message ?? "The peer refused the transfer.");

        var response = reply.PayloadAs<PullResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed transfer response.");

        Directory.CreateDirectory(downloadDirectory);

        var transferId = Guid.Parse(response.TransferId);
        var token = Guid.Parse(response.Token);
        var destination = Path.Combine(downloadDirectory, response.Name);

        await using var part = PartFile.OpenOrCreate(destination, transferId, response.Size, response.ChunkSize);

        try
        {
            await bulk.DownloadAsync(
                peerEndpoint, transferId, token, part,
                Math.Min(streamCount, response.Streams), progress, cancellationToken);
        }
        catch (Exception) when (!cancellationToken.IsCancellationRequested)
        {
            // One reconnect-and-resume attempt. The bitmap means we continue from
            // where we stopped rather than starting over.
            var retryToken = await RequestFreshTokenAsync(control, remotePath, cancellationToken);

            await bulk.DownloadAsync(
                peerEndpoint, transferId, retryToken, part,
                Math.Min(streamCount, response.Streams), progress, cancellationToken);
        }

        if (!await part.CompleteAsync(cancellationToken))
            throw new ControlProtocolException("The transfer finished with chunks still missing.");

        return destination;
    }

    private async Task<Guid> RequestFreshTokenAsync(
        ControlConnection control, string remotePath, CancellationToken cancellationToken)
    {
        var requestId = Guid.NewGuid().ToString("N")[..8];
        await control.SendAsync(ControlMessage.Request("pull.request", requestId, new PullRequest(remotePath)), cancellationToken);

        var reply = await AwaitReplyAsync(control, requestId, cancellationToken);
        var response = reply.PayloadAs<PullResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed transfer response.");

        return Guid.Parse(response.Token);
    }

    private static async Task<ControlMessage> AwaitReplyAsync(
        ControlConnection control, string requestId, CancellationToken cancellationToken)
    {
        while (await control.ReceiveAsync(cancellationToken) is { } message)
        {
            if (message.Id == requestId) return message;
            // Events and other replies interleave freely; keep reading.
        }

        throw new ControlProtocolException("The peer closed the connection before replying.");
    }
}
```

- [ ] **Step 5: Extend `SlipstreamPeer`**

Modify `windows/src/Slipstream.Core/SlipstreamPeer.cs`. Add these members and wire the new servers into `StartAsync`:

```csharp
// New init-only configuration, defaulted so existing callers are unaffected.
public IPAddress? BindAddress { get; init; }
public string DownloadDirectory { get; init; } = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Slipstream");
public bool UseEphemeralPorts { get; init; }
public int StreamCount { get; init; } = 4;

public TokenVault Tokens { get; } = new();
public BulkServer BulkServer => _bulkServer ?? throw new InvalidOperationException("Call StartAsync first.");
public MediaServer MediaServer => _mediaServer ?? throw new InvalidOperationException("Call StartAsync first.");
public TransferEngine Engine => _engine ?? throw new InvalidOperationException("Call StartAsync first.");

/// <summary>Raised whenever discovery must run again — spec §5 network-change handling.</summary>
public event Action? NetworkChanged;
```

In `StartAsync`, after creating the control server:

```csharp
var bind = BindAddress ?? network.LocalAddress;

_bulkServer = new BulkServer(Tokens, bind, UseEphemeralPorts ? 0 : SlipstreamPorts.Bulk);
_mediaServer = new MediaServer(Tokens, bind, UseEphemeralPorts ? 0 : SlipstreamPorts.Media);

var thumbnails = new ThumbnailProvider(Path.Combine(_stateDirectory, "thumbnails"), Tokens);
_mediaServer.ThumbnailResolver = thumbnails.Resolve;

var session = new SlipstreamSession(
    Identity, new FileBrowser(), Tokens, _mediaServer, thumbnails,
    new PlaylistLauncher(Path.Combine(Path.GetTempPath(), "slipstream")),
    bind, StreamCount);

_engine = new TransferEngine(Client, new BulkClient(), DownloadDirectory, StreamCount);

// Every inbound control connection is pumped through the session dispatcher.
_server.PeerConnected += async (connection, token) =>
{
    while (await connection.ReceiveAsync(token) is { } message)
    {
        var reply = await session.HandleAsync(message, token);
        if (reply is not null) await connection.SendAsync(reply, token);
    }
};

// Spec §5: a network switch is routine. Tear down, rediscover, resume.
NetworkInterface.NetworkAddressChanged += (_, _) => NetworkChanged?.Invoke();

// Spec §7: sweep orphaned .part files on start.
PartFile.CollectStale(DownloadDirectory, TimeSpan.FromDays(7));

return Task.WhenAll(
    _server.RunAsync(cancellationToken),
    _bulkServer.RunAsync(cancellationToken),
    _mediaServer.RunAsync(cancellationToken),
    _multicast.RespondToQueriesAsync(cancellationToken));
```

Add the corresponding `_bulkServer`, `_mediaServer`, `_engine`, and `_stateDirectory` fields, dispose the new servers in `DisposeAsync`, and add `using System.Net.NetworkInformation;`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `dotnet test windows/Slipstream.sln --filter TransferEngineTests`
Expected: PASS, 4 tests.

- [ ] **Step 7: Add the throughput regression gate**

```bash
cd windows
dotnet new console -n Slipstream.Bench -o bench/Slipstream.Bench -f net9.0
dotnet sln add bench/Slipstream.Bench/Slipstream.Bench.csproj
dotnet add bench/Slipstream.Bench/Slipstream.Bench.csproj reference src/Slipstream.Core/Slipstream.Core.csproj
```

Replace `windows/bench/Slipstream.Bench/Program.cs`:

```csharp
using System.Diagnostics;
using System.Security.Cryptography;
using Slipstream.Core.Tests; // TwoPeers

// Loopback throughput floor. Not a network measurement — a guard against an
// accidental buffer copy or a serialised stream silently halving performance.
const int FloorMegabytesPerSecond = 150;
const int PayloadMegabytes = 128;

var root = Directory.CreateTempSubdirectory("slipstream-bench-").FullName;
using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(5));

try
{
    var payload = RandomNumberGenerator.GetBytes(PayloadMegabytes * 1024 * 1024);
    var sourcePath = Path.Combine(root, "shared", "bench.bin");
    Directory.CreateDirectory(Path.GetDirectoryName(sourcePath)!);
    await File.WriteAllBytesAsync(sourcePath, payload, cts.Token);

    await using var peers = await TwoPeers.StartAsync(root, cts.Token);

    var stopwatch = Stopwatch.StartNew();
    await peers.Client.Engine.PullAsync(peers.Connection, peers.ServerEndPoint, sourcePath, null, cts.Token);
    stopwatch.Stop();

    var rate = PayloadMegabytes / stopwatch.Elapsed.TotalSeconds;

    Console.WriteLine($"Transferred {PayloadMegabytes} MB in {stopwatch.Elapsed.TotalSeconds:F2}s");
    Console.WriteLine($"Rate: {rate:F1} MB/s (floor {FloorMegabytesPerSecond} MB/s)");

    if (rate < FloorMegabytesPerSecond)
    {
        Console.Error.WriteLine($"FAIL: throughput regressed below the {FloorMegabytesPerSecond} MB/s floor.");
        return 1;
    }

    Console.WriteLine("PASS");
    return 0;
}
finally
{
    Directory.Delete(root, recursive: true);
}
```

Make `TwoPeers` reachable from the bench project by adding an `InternalsVisibleTo`-free project reference:

```bash
dotnet add bench/Slipstream.Bench/Slipstream.Bench.csproj reference tests/Slipstream.Core.Tests/Slipstream.Core.Tests.csproj
```

Add the bench to CI in `.github/workflows/windows-core.yml`, after the test step:

```yaml
      - run: dotnet run --project windows/bench/Slipstream.Bench --configuration Release
```

- [ ] **Step 8: Extend the harness**

Add these cases to `windows/tools/Slipstream.Harness/Program.cs`'s switch, alongside Plan 1's `identity` / `pair` / `serve` / `find`:

```csharp
case "pull":
{
    // pull <state> <remotePath>
    await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
    _ = peer.StartAsync(cts.Token);
    await Task.Delay(300, cts.Token);

    var found = await peer.FindPeerAsync(TimeSpan.FromSeconds(10), cts.Token);
    if (found is null) { Console.WriteLine("Phone not on this network."); return 1; }

    await using var connection = await peer.Client.ConnectAsync(
        found.Peer.Endpoint, TimeSpan.FromSeconds(5), cts.Token);

    var stopwatch = Stopwatch.StartNew();
    var progress = new Progress<TransferProgress>(p =>
        Console.Write($"\r{p.BytesCompleted / 1048576.0:F1} / {p.TotalBytes / 1048576.0:F1} MB " +
                      $"at {p.BytesPerSecond / 1048576.0:F1} MB/s   "));

    var local = await peer.Engine.PullAsync(
        connection!, new IPEndPoint(found.Peer.Endpoint.Address, SlipstreamPorts.Bulk),
        args[2], progress, cts.Token);

    stopwatch.Stop();
    Console.WriteLine($"\nSaved to {local} in {stopwatch.Elapsed.TotalSeconds:F1}s");
    break;
}

case "stream":
{
    // stream <state> <remotePath> — asks the peer to play it here
    await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
    _ = peer.StartAsync(cts.Token);
    await Task.Delay(300, cts.Token);

    var found = await peer.FindPeerAsync(TimeSpan.FromSeconds(10), cts.Token);
    if (found is null) { Console.WriteLine("Phone not on this network."); return 1; }

    await using var connection = await peer.Client.ConnectAsync(
        found.Peer.Endpoint, TimeSpan.FromSeconds(5), cts.Token);

    await connection!.SendAsync(
        ControlMessage.Request("stream.request", "1", new StatRequest(args[2])), cts.Token);

    var reply = await connection.ReceiveAsync(cts.Token);
    var play = reply?.PayloadAs<PlayMessage>();

    Console.WriteLine(play is null ? "The peer refused the stream." : $"Stream URL: {play.Url}");
    break;
}
```

- [ ] **Step 9: Verify on two real machines**

With `serve` running on one PC and the harness on another, walk the spec's §16 matrix and record actual numbers:

| Check | Expected |
|---|---|
| `pull` a 1 GB file over external WiFi | Completes; note MB/s |
| Same file over an Android hotspot | Completes; MB/s will be far lower — that is the radio, not a bug |
| Disable WiFi mid-transfer, re-enable | Resumes from the byte it stopped at, no restart |
| Kill the receiver mid-transfer, re-run `pull` | Resumes from the persisted bitmap |
| `stream` a 4 GB MKV, then seek to the end | Playback starts in seconds; seek is near-instant; nothing written to disk |
| Compare `certutil -hashfile` on both sides | Identical |

If any transfer completes but the hashes differ, stop — that is a CRC or offset bug, and it is the one class of defect this design must never ship with.

- [ ] **Step 10: Run the whole suite and the bench**

```bash
dotnet test windows/Slipstream.sln
dotnet run --project windows/bench/Slipstream.Bench --configuration Release
```

Expected: all tests pass; the bench prints `PASS`.

- [ ] **Step 11: Commit**

```bash
git add windows protocol
git commit -m "feat: wire transfer engine, media, and thumbnails into the peer"
```

---

## Self-Review

**Spec coverage.**

| Spec requirement | Task |
|---|---|
| §6 `list` / `stat`, 5000 cap + truncated flag | 11, 15 |
| §6 `pull.request` / `pull.ok`, bulk token | 4, 15 |
| §6 `hello` / `hello.ok` still answered once the session owns the connection | 15 |
| §6 unknown types ignored | 15 |
| §7 purpose-built framing, not HTTP | 1, 2 |
| §7 64-byte header, big-endian | 1, 2 |
| §7 4 parallel streams, 1–8 configurable | 6, 9 |
| §7 small files (<4 MB) assigned whole | 6 |
| §7 4 MB socket buffers, `TCP_NODELAY` | 8, 9 |
| §7 1 MB chunks, preallocation, single fsync | 7 |
| §7 plaintext bulk, token-authenticated | 4, 8 |
| §7 chunk bitmap resume, gaps only | 5, 7, 9 |
| §7 CRC32C per chunk, re-request on failure | 3, 7, 9 |
| §7 no whole-file hash | 3 (documented) |
| §7 folder trees, relative paths, empty dirs | 10 |
| §7 `.part` GC after 7 days | 7 |
| §8 HTTP/1.1 Range, 206, `Accept-Ranges` | 12 |
| §8 media tokens, 12h + restart expiry | 4 |
| §8 push-to-play, `.m3u` not ShellExecute-URL | 14, 15 |
| §9 shell thumbnails, 256px JPEG, content-keyed cache | 13 |
| §9 listings carry tokens, not image data | 15 |
| §10 clipboard, 64 KB cap, explicit send | 15 |
| §11 `LanGuard` on 53322 and 53323 | 8, 9, 12 |
| §15 error voice: direct, no apology | 15 |
| §16 throughput floor enforced in CI | 16 |

**Deferred to Plan 3 or later:** `push.offer` (the reverse direction reuses the same machinery and is a UI-driven flow, planned with the UI), transfer queue persistence across app restarts, and Android's `Network`-object binding (§11 layer 3).

**Placeholder scan.** No `TBD`, `TODO`, or "similar to Task N". The two `PENDING` values — one in `bulk-headers.json`, one carried from Task 1 — are filled by Task 2 Step 5, and Task 2's vector test asserts they are no longer `PENDING`.

**Type consistency.** `ByteRange(Start, Length)` from Task 6 is used unchanged in Tasks 9 and 16; `ByteRangeSpec(Start, End)` in Task 12 is deliberately a *different* type, because HTTP ranges are end-inclusive and byte ranges are length-based — conflating them is a classic off-by-one, so they are kept distinct. `PartFile.WriteChunkAsync(int, ReadOnlyMemory<byte>, uint, CancellationToken)` matches its callers in Tasks 9 and 16. `TransferProgress(TransferId, BytesCompleted, TotalBytes, BytesPerSecond)` is constructed identically in Tasks 9 and 16. `TokenVault.IssueBulk(Guid, string, long, int)` and `ValidateBulk(Guid, Guid)` match across Tasks 4, 8, and 15. `PlayMessage` and `ClipboardMessage` are declared once, in Task 15's `SlipstreamSession.cs`, and Task 14's `PlayRequest` is a distinct platform-layer type by design.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-25-core-transfer-media.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, with review between tasks and fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batching with checkpoints for review.

Which approach?

