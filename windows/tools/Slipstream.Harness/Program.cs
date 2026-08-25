using System.Diagnostics;
using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;

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
        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);

        peer.StartAsync(cts.Token).ContinueWith(_ => { }, TaskScheduler.Default).Forget();
        await Task.Delay(300, cts.Token);

        peer.Server.PeerConnected += async (connection, token) =>
        {
            Console.WriteLine($"Peer connected from {connection.RemoteEndPoint}");

            while (await connection.ReceiveAsync(token) is { } message)
            {
                Console.WriteLine($"  <- {message.Type} {message.Id}");

                if (message.Type == "hello")
                {
                    await connection.SendAsync(ControlMessage.Response("hello.ok", message.Id!, new HelloPayload(
                        SlipstreamPorts.ProtocolVersion, peer.Identity.DeviceId,
                        peer.Identity.DisplayName, peer.Identity.Fingerprint)), token);
                }
                else if (message.Type == "ping")
                {
                    await connection.SendAsync(ControlMessage.Response("pong", message.Id!), token);
                }
            }
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

    case "pair-mode":
    {
        // pair-mode <state> — opens a 120s window and pairs with whoever answers.
        await using var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        peer.StartAsync(cts.Token).ContinueWith(_ => { }, TaskScheduler.Default).Forget();
        await Task.Delay(300, cts.Token);

        Console.WriteLine($"This device: {peer.Identity.DisplayName}  {peer.Identity.Fingerprint[..16]}…");
        Console.WriteLine("Pairing window open for 120 seconds. Run 'pair-mode' on the other device too.");

        var result = await peer.PairAsync(
            confirmCode: (code, _) =>
            {
                Console.WriteLine();
                Console.WriteLine($"    Pairing code:  {code}");
                Console.WriteLine();
                Console.Write("Does this match the code on the other device? [y/N] ");
                var answer = Console.ReadLine();
                return Task.FromResult(answer?.Trim().Equals("y", StringComparison.OrdinalIgnoreCase) == true);
            },
            timeout: TimeSpan.FromSeconds(120),
            cts.Token);

        Console.WriteLine(result is null
            ? "Pairing did not complete."
            : $"Paired with {result.DisplayName}.");

        return result is null ? 1 : 0;
    }

    case "paired":
    {
        var peer = new SlipstreamPeer(stateDir, Environment.MachineName);
        Console.WriteLine(peer.Peers.Current is { } current
            ? $"Paired with {current.DisplayName} ({current.DeviceId}) since {current.PairedAt:u}"
            : "Not paired.");
        break;
    }

    default:
        Console.WriteLine("Commands: identity <state> | pair <state> <id> <name> <fingerprint> | serve <state> | find <state> | pair-mode <state> | paired <state>");
        break;
}

return 0;

internal static class TaskExtensions
{
    public static void Forget(this Task task) => _ = task;
}
