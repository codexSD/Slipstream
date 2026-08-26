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
        SlipstreamLog.Info("wire", $"-> {message.Type} id={message.Id}");
        return _codec.WriteAsync(message, cancellationToken);
    }

    public async Task<ControlMessage?> ReceiveAsync(CancellationToken cancellationToken)
    {
        var message = await _codec.ReadAsync(cancellationToken);
        SlipstreamLog.Info("wire", message is null
            ? "<- (peer closed the connection)"
            : $"<- {message.Type} id={message.Id}");
        return message;
    }

    public async ValueTask DisposeAsync() => await stream.DisposeAsync();
}
