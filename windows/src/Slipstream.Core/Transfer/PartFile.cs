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

    private static readonly TimeSpan PersistInterval = TimeSpan.FromMilliseconds(500);

    private readonly FileStream _stream;
    private readonly Lock _bitmapGate = new();
    private DateTimeOffset _lastPersist = DateTimeOffset.MinValue;
    private bool _dirty;
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

        FileStream stream;
        if (!File.Exists(partPath))
        {
            // New file: use CreateNew with preallocation
            stream = new FileStream(partPath, new FileStreamOptions
            {
                Mode = FileMode.CreateNew,
                Access = FileAccess.ReadWrite,
                Share = FileShare.None,
                Options = FileOptions.Asynchronous,
                PreallocationSize = size,
            });
        }
        else
        {
            // Existing file: open without preallocation
            stream = new FileStream(partPath, new FileStreamOptions
            {
                Mode = FileMode.Open,
                Access = FileAccess.ReadWrite,
                Share = FileShare.None,
                Options = FileOptions.Asynchronous,
            });
        }

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

    public async Task<bool> CompleteAsync(CancellationToken cancellationToken)
    {
        if (!Bitmap.IsComplete) return false;

        await PersistStateAsync(cancellationToken);

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
}
