using Slipstream.App.Services;

namespace Slipstream.App.Tests;

/// <summary>
/// Task 14: SettingsStore persists app preferences to a JSON file under the state directory,
/// following HistoryStore's convention (Task 13) — read-mutate-rewrite the whole file, no
/// database, single-process only.
/// </summary>
public class SettingsStoreTests : IDisposable
{
    private readonly string _dir;
    private readonly string _path;

    public SettingsStoreTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "slipstream-settings-tests-" + Guid.NewGuid());
        Directory.CreateDirectory(_dir);
        _path = Path.Combine(_dir, "settings.json");
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    [Fact]
    public void Load_returns_defaults_when_no_file_exists()
    {
        var store = new SettingsStore(_path);
        var data = store.Load();

        Assert.Equal(4, data.StreamCount);
        Assert.Equal(AppTheme.System, data.Theme);
        Assert.False(data.AutostartEnabled);
        Assert.False(string.IsNullOrWhiteSpace(data.DownloadDirectory));
    }

    [Fact]
    public void Settings_persist_across_instances()
    {
        var store1 = new SettingsStore(_path);
        store1.Save(store1.Load() with { StreamCount = 6, Theme = AppTheme.Dark, AutostartEnabled = true });

        var store2 = new SettingsStore(_path);
        var data = store2.Load();

        Assert.Equal(6, data.StreamCount);
        Assert.Equal(AppTheme.Dark, data.Theme);
        Assert.True(data.AutostartEnabled);
    }
}
