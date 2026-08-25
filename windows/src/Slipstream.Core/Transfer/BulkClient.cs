using System.Buffers;
using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Control;
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
