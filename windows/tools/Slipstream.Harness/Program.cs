using System.Diagnostics;
using System.Net;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Control.Handlers;
using Slipstream.Core.Identity;
using Slipstream.Core.Transfer;

var command = args.Length > 0 ? args[0] : "help";
var stateDir = Path.Combine(Path.GetTempPath(), "slipstream-harness", args.Length > 1 ? args[1] : "default");

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

switch (command)
{
    case "identity":
    {
        var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        Console.WriteLine($"Device id:   {peer.Identity.DeviceId}");
        Console.WriteLine($"Fingerprint: {peer.Identity.Fingerprint}");
        Console.WriteLine($"Network:     {peer.Network?.LocalAddress} gw={peer.Network?.Gateway} key={peer.Network?.Key}");
        break;
    }

    case "pair":
    {
        // Manual pairing: paste the other device's id, name, and fingerprint.
        if (args.Length < 5)
        {
            Console.WriteLine("Usage: pair <state> <peerDeviceId> <peerName> <peerFingerprint>");
            return 1;
        }

        var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        peer.Peers.Pair(new PairedPeer(args[2], args[4], args[3], DateTimeOffset.UtcNow));

        Console.WriteLine($"Paired with {args[3]}.");
        Console.WriteLine($"Confirm this code matches on both devices: {PairingCode.Derive(peer.Identity.Fingerprint, args[4])}");
        break;
    }

    case "serve":
    {
        // SlipstreamPeer.StartAsync now wires every inbound connection through
        // SlipstreamSession itself (hello, list, stat, pull.request, stream.request,
        // play, clipboard) — a second reader on the same connection here would just
        // race it for messages, so this only logs, it no longer dispatches.
        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);

        peer.StartAsync(cts.Token).ContinueWith(_ => { }, TaskScheduler.Default).Forget();
        await Task.Delay(300, cts.Token);

        peer.Server.PeerConnected += (connection, _) =>
        {
            Console.WriteLine($"Peer connected from {connection.RemoteEndPoint}");
            return Task.CompletedTask;
        };

        Console.WriteLine($"Listening on {peer.Server.ListenEndPoint}. Ctrl+C to stop.");
        await Task.Delay(Timeout.Infinite, cts.Token);
        break;
    }

    case "find":
    {
        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);

        var stopwatch = Stopwatch.StartNew();
        var result = await peer.FindPeerAsync(TimeSpan.FromSeconds(10), cts.Token);
        stopwatch.Stop();

        if (result is null)
        {
            Console.WriteLine($"Peer not found after {stopwatch.ElapsedMilliseconds} ms.");
            return 1;
        }

        Console.WriteLine($"Found {result.Peer.Peer.DisplayName} at {result.Peer.Endpoint}");
        Console.WriteLine($"  strategy: {result.StrategyName}");
        Console.WriteLine($"  elapsed:  {stopwatch.ElapsedMilliseconds} ms");

        await using var connection = await peer.Client.ConnectAsync(
            result.Peer.Endpoint, TimeSpan.FromSeconds(5), cts.Token);

        if (connection is not null)
        {
            await connection.SendAsync(ControlMessage.Request("hello", "1", new HelloPayload(
                SlipstreamPorts.ProtocolVersion, peer.Identity.DeviceId,
                peer.Identity.DisplayName, peer.Identity.Fingerprint)), cts.Token);

            var reply = await connection.ReceiveAsync(cts.Token);
            Console.WriteLine($"  handshake: {reply?.Type}");
        }
        break;
    }

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

    case "send-text":
    {
        // send-text <state> <text> — spec §10 clipboard push
        if (args.Length < 3)
        {
            Console.WriteLine("Usage: send-text <state> <text>");
            return 1;
        }

        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        _ = peer.StartAsync(cts.Token);
        await Task.Delay(300, cts.Token);

        var found = await peer.FindPeerAsync(TimeSpan.FromSeconds(10), cts.Token);
        if (found is null) { Console.WriteLine("Phone not on this network."); return 1; }

        await using var connection = await peer.Client.ConnectAsync(
            found.Peer.Endpoint, TimeSpan.FromSeconds(5), cts.Token);

        await connection!.SendAsync(
            ControlMessage.Request("clipboard", "1", new ClipboardMessage(args[2])), cts.Token);

        var reply = await connection.ReceiveAsync(cts.Token);
        Console.WriteLine(reply?.Type == "clipboard.ok" ? "Sent." : $"The peer refused: {reply?.Type}");
        break;
    }

    default:
        Console.WriteLine("Commands: identity <state> | pair <state> <id> <name> <fingerprint> | serve <state> | find <state> | " +
                           "pull <state> <remotePath> | stream <state> <remotePath> | send-text <state> <text>");
        break;
}

return 0;

internal static class TaskExtensions
{
    public static void Forget(this Task task) => _ = task;
}
