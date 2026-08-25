using System.Net;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using Slipstream.Core;
using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

[SupportedOSPlatform("windows")]
public class TransferEngineTests : IAsyncLifetime
{
    private readonly string _root = Directory.CreateTempSubdirectory("slipstream-engine-").FullName;
    private readonly CancellationTokenSource _cts = new(TimeSpan.FromSeconds(60));

    private TwoPeers _peers = null!;
    private byte[] _payload = null!;
    private string _sourcePath = null!;

    public async Task InitializeAsync()
    {
        _payload = RandomNumberGenerator.GetBytes(12 * 1024 * 1024); // 12 MB: forces range splitting
        _sourcePath = Path.Combine(_root, "shared", "big.bin");
        Directory.CreateDirectory(Path.GetDirectoryName(_sourcePath)!);
        await File.WriteAllBytesAsync(_sourcePath, _payload, _cts.Token);

        _peers = await TwoPeers.StartAsync(_root, _cts.Token);
    }

    public async Task DisposeAsync()
    {
        await _cts.CancelAsync();
        await _peers.DisposeAsync();
        _cts.Dispose();
        Directory.Delete(_root, recursive: true);
    }

    [Fact]
    public async Task Pulls_a_file_byte_identically_across_two_peers()
    {
        var local = await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

        Assert.Equal(_payload, await File.ReadAllBytesAsync(local, _cts.Token));
    }

    [Fact]
    public async Task Reports_progress_reaching_the_full_size()
    {
        var reports = new List<TransferProgress>();
        var progress = new Progress<TransferProgress>(p => { lock (reports) reports.Add(p); });

        await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, progress, _cts.Token);

        await Task.Delay(200, _cts.Token);

        lock (reports)
        {
            Assert.NotEmpty(reports);
            Assert.Equal(_payload.Length, reports.Max(r => r.BytesCompleted));
            Assert.True(reports.Max(r => r.BytesPerSecond) > 0);
        }
    }

    [Fact]
    public async Task Lands_the_file_in_the_download_directory_under_its_own_name()
    {
        var local = await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

        Assert.Equal("big.bin", Path.GetFileName(local));
        Assert.StartsWith(_peers.Client.DownloadDirectory, local);
    }

    [Fact]
    public async Task Leaves_no_part_file_behind_on_success()
    {
        await _peers.Client.Engine.PullAsync(
            _peers.Connection, _peers.ServerEndPoint, _sourcePath, null, _cts.Token);

        Assert.Empty(Directory.GetFiles(_peers.Client.DownloadDirectory, "*.slipstream-part"));
    }
}
