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
