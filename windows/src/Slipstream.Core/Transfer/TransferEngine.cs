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
    private ControlConnection? _reconnected;

    public async Task<string> PullAsync(
        ControlConnection control,
        IPEndPoint peerEndpoint,
        string remotePath,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        try
        {
            return await PullCoreAsync(control, peerEndpoint, remotePath, progress, cancellationToken);
        }
        finally
        {
            if (_reconnected is not null)
                await _reconnected.DisposeAsync();
        }
    }

    private async Task<string> PullCoreAsync(
        ControlConnection control,
        IPEndPoint peerEndpoint,
        string remotePath,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        var live = await LiveConnectionAsync(control, peerEndpoint, cancellationToken);

        var requestId = Guid.NewGuid().ToString("N")[..8];
        await live.SendAsync(ControlMessage.Request("pull.request", requestId, new PullRequest(remotePath)), cancellationToken);

        var reply = await AwaitReplyAsync(live, requestId, cancellationToken);

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
            // where we stopped rather than starting over. A fresh pull.request mints
            // a new transfer id alongside its token — the id on the wire exists only
            // to authenticate the bulk socket against the token the server just
            // issued, so both must come from the same reply. PartFile's bitmap is
            // already open and keeps tracking progress from the byte it stopped at
            // regardless of which transfer id labels the retry.
            var (retryTransferId, retryToken) = await RequestFreshTokenAsync(control, peerEndpoint, remotePath, cancellationToken);

            await bulk.DownloadAsync(
                peerEndpoint, retryTransferId, retryToken, part,
                Math.Min(streamCount, response.Streams), progress, cancellationToken);
        }

        if (!await part.CompleteAsync(cancellationToken))
            throw new ControlProtocolException("The transfer finished with chunks still missing.");

        return destination;
    }

    private async Task<(Guid TransferId, Guid Token)> RequestFreshTokenAsync(
        ControlConnection control, IPEndPoint peerEndpoint, string remotePath, CancellationToken cancellationToken)
    {
        var live = await LiveConnectionAsync(control, peerEndpoint, cancellationToken);

        var requestId = Guid.NewGuid().ToString("N")[..8];
        await live.SendAsync(ControlMessage.Request("pull.request", requestId, new PullRequest(remotePath)), cancellationToken);

        var reply = await AwaitReplyAsync(live, requestId, cancellationToken);
        var response = reply.PayloadAs<PullResponse>()
            ?? throw new ControlProtocolException("The peer sent a malformed transfer response.");

        return (Guid.Parse(response.TransferId), Guid.Parse(response.Token));
    }

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
            // Reconnect to the control endpoint the dead connection was actually
            // talking to (not a fixed port derived from the bulk endpoint) — this is
            // the only address guaranteed correct under both fixed and ephemeral
            // port configurations.
            var controlEndpoint = supplied.RemoteEndPoint;

            _reconnected = await client.ConnectAsync(controlEndpoint, TimeSpan.FromSeconds(10), cancellationToken)
                ?? throw new ControlProtocolException("Lost the connection to the peer and could not reconnect.");

            return _reconnected;
        }
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
