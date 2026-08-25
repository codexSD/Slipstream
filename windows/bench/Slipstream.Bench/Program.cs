using System.Diagnostics;
using System.Security.Cryptography;
using Slipstream.Core.Tests; // TwoPeers

// Loopback throughput floor. Not a network measurement — a guard against an
// accidental buffer copy or a serialised stream silently halving performance.
const int FloorMegabytesPerSecond = 150;
const int PayloadMegabytes = 128;

var root = Directory.CreateTempSubdirectory("slipstream-bench-").FullName;
using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(5));

try
{
    var payload = RandomNumberGenerator.GetBytes(PayloadMegabytes * 1024 * 1024);
    var sourcePath = Path.Combine(root, "shared", "bench.bin");
    Directory.CreateDirectory(Path.GetDirectoryName(sourcePath)!);
    await File.WriteAllBytesAsync(sourcePath, payload, cts.Token);

    await using var peers = await TwoPeers.StartAsync(root, cts.Token);

    var stopwatch = Stopwatch.StartNew();
    await peers.Client.Engine.PullAsync(peers.Connection, peers.ServerEndPoint, sourcePath, null, cts.Token);
    stopwatch.Stop();

    var rate = PayloadMegabytes / stopwatch.Elapsed.TotalSeconds;

    Console.WriteLine($"Transferred {PayloadMegabytes} MB in {stopwatch.Elapsed.TotalSeconds:F2}s");
    Console.WriteLine($"Rate: {rate:F1} MB/s (floor {FloorMegabytesPerSecond} MB/s)");

    if (rate < FloorMegabytesPerSecond)
    {
        Console.Error.WriteLine($"FAIL: throughput regressed below the {FloorMegabytesPerSecond} MB/s floor.");
        return 1;
    }

    Console.WriteLine("PASS");
    return 0;
}
finally
{
    Directory.Delete(root, recursive: true);
}
