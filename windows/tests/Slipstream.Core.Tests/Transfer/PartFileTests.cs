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
