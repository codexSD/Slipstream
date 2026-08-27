using System.Net;
using Slipstream.Core.Diagnostics;

namespace Slipstream.Core.Control;

/// <summary>Spec §6: one persistent TLS connection carrying JSON lines.</summary>
public sealed class ControlConnection(Stream stream, string peerFingerprint, IPEndPoint remoteEndPoint)
    : IAsyncDisposable
{
    private readonly JsonLineCodec _codec = new(stream);

    public string PeerFingerprint { get; } = peerFingerprint;

    public IPEndPoint RemoteEndPoint { get; } = remoteEndPoint;

    public Task SendAsync(ControlMessage message, CancellationToken cancellationToken)
    {
        Trace("->", message);
        return _codec.WriteAsync(message, cancellationToken);
    }

    public async Task<ControlMessage?> ReceiveAsync(CancellationToken cancellationToken)
    {
        var message = await _codec.ReadAsync(cancellationToken);

        if (message is null) SlipstreamLog.Info("wire", "<- (peer closed the connection)");
        else Trace("<-", message);

        return message;
    }

    /// <summary>
    /// The heartbeat is not logged. At one round trip every 750ms it buries everything that
    /// actually happened — the connect, the transfer, the failure under investigation — under
    /// its own noise. A log that drowns the interesting events in order to record that nothing
    /// is wrong is worse than no log.
    /// </summary>
    private static void Trace(string direction, ControlMessage message)
    {
        if (message.Type is "ping" or "pong") return;
        SlipstreamLog.Info("wire", $"{direction} {message.Type} id={message.Id}");
    }

    public async ValueTask DisposeAsync() => await stream.DisposeAsync();
}
