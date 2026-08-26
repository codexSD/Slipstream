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
    // Also the size of each chunk handed to a single stream.WriteAsync call in
    // ServeFileAsync. Keep this modest: a response body is written as a sequence of
    // WriteAsync calls sized to this buffer, and each call leaves that much data
    // in flight (handed to the OS, not yet acknowledged by the peer) at once. A very
    // large single write racing the "Connection: close" teardown right behind it
    // (see HandleAsync) gives the OS far more unacknowledged data to lose if that
    // teardown aborts the socket rather than closing it gracefully. The teardown
    // itself is the real fix for the "existing connection was forcibly closed"
    // truncation; chunking is defence in depth, keeping the in-flight window
    // bounded regardless of file size.
    private const int StreamBufferBytes = 64 * 1024;

    // Teardown drain (see HandleAsync's finally). Small: nothing useful is
    // expected here, only the client's own close. Bounded so a client that never
    // hangs up cannot pin a handler open - DisposeAsync's drain window is longer
    // than this, so a handler waiting here still finishes ahead of it.
    private const int DrainBufferBytes = 1024;
    private static readonly TimeSpan DrainTimeout = TimeSpan.FromSeconds(3);

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

    // Both RunAsync and each HandleAsync are started fire-and-forget (`_ =`) by
    // their callers - RunAsync by whoever owns the server, HandleAsync by the
    // accept loop below. Nothing about that is wrong on its own, but it means
    // *nobody* has a handle to know when the server has actually finished
    // shutting down: DisposeAsync used to just call _listener.Stop() and return,
    // while the accept loop and any in-flight handlers kept running in the
    // background on borrowed time. A caller that disposes a MediaServer (e.g. a
    // test's teardown, moving straight on to spin up the next one) had no way to
    // wait for that background work to actually stop, so it could still be
    // running - competing for ThreadPool workers, sockets, and file handles -
    // while the next MediaServer's accept loop and requests are getting started.
    // Tracking the loop and every handler here lets DisposeAsync wait for a
    // clean stop instead of leaving zombies behind.
    private readonly List<Task> _inFlight = [];
    private readonly object _inFlightLock = new();
    private Task? _acceptLoop;

    public MediaServer(TokenVault vault, IPAddress bindAddress, int port)
    {
        LanGuard.EnsureLocal(bindAddress);

        _vault = vault;
        var endpoint = new IPEndPoint(bindAddress, port);
        _listener = new TcpListener(endpoint);
        _listener.Start();
    }

    public IPEndPoint ListenEndPoint => (IPEndPoint)_listener.LocalEndpoint;

    /// <summary>Resolves a thumbnail token to a cached JPEG path. Set by Task 13.</summary>
    public Func<Guid, string?>? ThumbnailResolver { get; set; }

    public string UrlFor(TransferToken token, IPAddress advertisedAddress) =>
        $"http://{advertisedAddress}:{ListenEndPoint.Port}/media/{token.Value:N}";

    public Task RunAsync(CancellationToken cancellationToken)
    {
        var loop = AcceptLoopAsync(cancellationToken);
        _acceptLoop = loop;
        return loop;
    }

    private async Task AcceptLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (OperationCanceledException) { return; }
            // Stop()/Dispose() racing a pending accept can surface as either of
            // these depending on exactly where the accept was when the listener
            // went away, rather than as the OperationCanceledException above -
            // treat both as "we're shutting down", not as a transient error to
            // retry. Looping back to AcceptTcpClientAsync on an already-disposed
            // listener would just throw the same exception again immediately,
            // spinning until something else broke the loop.
            catch (ObjectDisposedException) { return; }
            catch (SocketException) when (cancellationToken.IsCancellationRequested) { return; }
            catch (SocketException) { continue; }

            var handler = HandleAsync(client, cancellationToken);
            Track(handler);
        }
    }

    private void Track(Task handler)
    {
        lock (_inFlightLock)
        {
            _inFlight.Add(handler);
            _inFlight.RemoveAll(t => t.IsCompleted);
        }
    }

    private async Task HandleAsync(TcpClient client, CancellationToken cancellationToken)
    {
        try
        {
            client.NoDelay = true;

            var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
            if (!LanGuard.IsLocal(remote.Address)) return;

            var stream = client.GetStream();

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
            // Let the *client* close first, then follow. This used to be
            // DisconnectAsync(reuseSocket: false) immediately followed by
            // Dispose(), on the theory that DisconnectAsync would let the OS
            // finish delivering the send buffer. It does not. Writing to a socket
            // only queues bytes; a teardown the server initiates while the tail of
            // the response is still in flight loses that tail, and the peer sees
            // the body stop partway through and then, once the stack gives up
            // retransmitting, "An existing connection was forcibly closed by the
            // remote host". Same error string as the bug the comment above
            // describes, because it was never actually a different bug - that fix
            // narrowed the window instead of closing it.
            //
            // Measured on a 100 KB body over loopback: ~20% of responses lost
            // their tail (consistently the last few hundred bytes) and failed
            // ~19 s later. Shutdown(SocketShutdown.Send) here is *not* a fix -
            // it is the same server-initiated close and reproduces at the same
            // rate. Not touching the send side at all reproduces zero times.
            //
            // So: send nothing, read until the client closes its half, then
            // dispose. Every response carries Content-Length, so the client knows
            // where the body ends without needing a FIN from us and hangs up on
            // its own; by the time its FIN arrives the body is provably delivered
            // and Dispose() has nothing left to discard. Draining also empties the
            // receive buffer, which matters independently - unread bytes at close
            // time are themselves enough to turn a close into an RST.
            try
            {
                await DrainUntilPeerClosesAsync(client.Client);
            }
            catch (Exception)
            {
                // Peer already gone, or the socket was never fully connected
                // (e.g. LanGuard rejected it above), or a client that never hangs
                // up hit the drain timeout. None of those should surface an error
                // on a response that was served correctly.
            }

            client.Dispose();
        }
    }

    /// <summary>
    /// Reads and discards until the client closes its half of the connection. That
    /// close is the server's evidence that the response was fully delivered, and it
    /// leaves the receive buffer empty so the following Dispose() is a clean close
    /// rather than an RST.
    ///
    /// Bounded, because a client that never hangs up must not pin a handler open.
    /// The window is sized so that even a stalled tail is through before it expires
    /// (measured: a server-initiated close was still lossy at 500 ms after the last
    /// write, and never lossy at 3 s), while staying under DisposeAsync's own drain
    /// window so a waiting handler still finishes ahead of a server shutdown.
    ///
    /// Deliberately not tied to the server's cancellation token: during shutdown
    /// that token is already cancelled, and skipping the drain is exactly the
    /// truncation this exists to prevent.
    /// </summary>
    private static async Task DrainUntilPeerClosesAsync(Socket socket)
    {
        using var timeout = new CancellationTokenSource(DrainTimeout);

        var buffer = ArrayPool<byte>.Shared.Rent(DrainBufferBytes);
        try
        {
            while (await socket.ReceiveAsync(
                       buffer.AsMemory(0, DrainBufferBytes), SocketFlags.None, timeout.Token) > 0)
            {
                // Anything still arriving is a pipelined request we will not
                // serve (every response says "Connection: close"). Discard it.
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
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
        using var reader = new StreamReader(stream, Encoding.ASCII, detectEncodingFromByteOrderMarks: false, bufferSize: 1024, leaveOpen: true);

        var requestLine = await reader.ReadLineAsync(cancellationToken);
        if (string.IsNullOrEmpty(requestLine)) return (null, null);

        var parts = requestLine.Split(' ');
        if (parts.Length < 2 || parts[0] != "GET") return (null, null);

        string? range = null;

        string? line;
        while ((line = await reader.ReadLineAsync(cancellationToken)) != null && !string.IsNullOrEmpty(line))
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

    public async ValueTask DisposeAsync()
    {
        _listener.Stop();

        // Previously this just stopped the listener and returned, leaving the
        // accept loop and any handlers it had already spun up to finish
        // whenever they got around to it - see the comment on _inFlight above
        // for why that matters. Wait (briefly - this is a graceful drain, not a
        // hang guard) for the loop to notice the stop and every handler it
        // handed off to actually finish, so a disposed MediaServer is genuinely
        // done using the network/thread pool before the caller moves on.
        List<Task> pending;
        lock (_inFlightLock)
        {
            pending = [.. _inFlight];
        }

        if (_acceptLoop is not null) pending.Add(_acceptLoop);

        try
        {
            await Task.WhenAll(pending).WaitAsync(TimeSpan.FromSeconds(5));
        }
        catch (Exception)
        {
            // Best-effort drain. A handler wedged past the grace period
            // shouldn't stop the server from being considered disposed.
        }
    }
}
