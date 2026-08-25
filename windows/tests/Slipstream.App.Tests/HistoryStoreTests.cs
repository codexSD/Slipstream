using Slipstream.App.Services;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 13: HistoryStore persists completed transfers to a JSON file under the state
/// directory. No database — 500 rows does not warrant one (per the task brief).
/// </summary>
public class HistoryStoreTests : IDisposable
{
    private readonly string _dir;
    private readonly string _path;

    public HistoryStoreTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "slipstream-history-tests-" + Guid.NewGuid());
        Directory.CreateDirectory(_dir);
        _path = Path.Combine(_dir, "history.json");
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    private static HistoryEntry MakeEntry(string remotePath, DateTimeOffset completedAt) =>
        new(remotePath, remotePath, 1024, TransferStatus.Complete, completedAt);

    [Fact]
    public void Entries_persist_across_instances()
    {
        var store1 = new HistoryStore(_path);
        store1.Add(MakeEntry("/DCIM/100APPLE/IMG_0001.HEIC", DateTimeOffset.UtcNow));

        var store2 = new HistoryStore(_path);
        var all = store2.GetAll();

        Assert.Single(all);
        Assert.Equal("/DCIM/100APPLE/IMG_0001.HEIC", all[0].RemotePath);
    }

    [Fact]
    public void GetAll_returns_newest_first()
    {
        var store = new HistoryStore(_path);
        var now = DateTimeOffset.UtcNow;
        store.Add(MakeEntry("/a.bin", now));
        store.Add(MakeEntry("/b.bin", now.AddSeconds(1)));
        store.Add(MakeEntry("/c.bin", now.AddSeconds(2)));

        var all = store.GetAll();

        Assert.Equal(["/c.bin", "/b.bin", "/a.bin"], all.Select(e => e.RemotePath));
    }

    [Fact]
    public void Capped_at_500_with_oldest_evicted()
    {
        var store = new HistoryStore(_path);
        var now = DateTimeOffset.UtcNow;
        for (var i = 0; i < 501; i++)
            store.Add(MakeEntry($"/file{i}.bin", now.AddSeconds(i)));

        var all = store.GetAll();

        Assert.Equal(500, all.Count);
        // Newest first: the very last one added (index 500) should be present at the front...
        Assert.Equal("/file500.bin", all[0].RemotePath);
        // ...and the oldest (index 0) should have been evicted, not the newest.
        Assert.DoesNotContain(all, e => e.RemotePath == "/file0.bin");
        Assert.Contains(all, e => e.RemotePath == "/file1.bin");
    }
}
