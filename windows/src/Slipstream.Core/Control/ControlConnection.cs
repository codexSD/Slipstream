using System.Net;

namespace Slipstream.Core.Control;

/// <summary>Spec §6: one persistent TLS connection carrying JSON lines.</summary>
public sealed class ControlConnection(Stream stream, string peerFingerprint, IPEndPoint remoteEndPoint)
    : IAsyncDisposable
{
    private readonly JsonLineCodec _codec = new(stream);

    public string PeerFingerprint { get; } = peerFingerprint;

    public IPEndPoint RemoteEndPoint { get; } = remoteEndPoint;

    public Task SendAsync(ControlMessage message, CancellationToken cancellationToken) =>
        _codec.WriteAsync(message, cancellationToken);

    public Task<ControlMessage?> ReceiveAsync(CancellationToken cancellationToken) =>
        _codec.ReadAsync(cancellationToken);

    public async ValueTask DisposeAsync() => await stream.DisposeAsync();
}
