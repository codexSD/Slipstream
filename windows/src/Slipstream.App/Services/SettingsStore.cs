using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slipstream.App.Services;

/// <summary>Theme choice offered on the Settings page.</summary>
public enum AppTheme { System, Light, Dark }

/// <summary>The full set of persisted app preferences. Immutable record so callers update it
/// with <c>with</c>-expressions rather than mutating shared state.</summary>
public sealed record SettingsData
{
    public int StreamCount { get; init; } = 4;
    public string DownloadDirectory { get; init; } = SettingsStore.DefaultDownloadDirectory();
    public AppTheme Theme { get; init; } = AppTheme.System;
    public bool AutostartEnabled { get; init; }
}

/// <summary>
/// Persists app preferences to a single JSON file under the app's state directory
/// (`%LOCALAPPDATA%\Slipstream\settings.json` by default — see <see cref="DefaultPath"/>),
/// mirroring <see cref="HistoryStore"/>'s convention (Task 13): read-mutate-rewrite the whole
/// file on every <see cref="Save"/>, no database, no migration format, single-process only.
/// </summary>
public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    private readonly string _path;
    private readonly object _sync = new();

    public SettingsStore(string? path = null)
    {
        _path = path ?? DefaultPath();
    }

    /// <summary>`%LOCALAPPDATA%\Slipstream\settings.json` — same unpackaged-app convention as
    /// <see cref="HistoryStore.DefaultPath"/>.</summary>
    public static string DefaultPath()
    {
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(root, "Slipstream", "settings.json");
    }

    /// <summary>The fallback download folder used when a chosen path no longer exists on
    /// disk: the standard per-user Downloads folder. Environment.SpecialFolder has no
    /// dedicated Downloads entry, so this is built from UserProfile the same way Explorer's
    /// default download location is derived on an unpackaged app.</summary>
    public static string DefaultDownloadDirectory()
    {
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return Path.Combine(profile, "Downloads");
    }

    /// <summary>Reads the persisted settings, or the defaults if no file exists yet (first
    /// run) or the file is empty/unreadable.</summary>
    public SettingsData Load()
    {
        lock (_sync)
        {
            if (!File.Exists(_path)) return new SettingsData();

            var json = File.ReadAllText(_path);
            if (string.IsNullOrWhiteSpace(json)) return new SettingsData();

            try
            {
                return JsonSerializer.Deserialize<SettingsData>(json, JsonOptions) ?? new SettingsData();
            }
            catch (JsonException)
            {
                return new SettingsData();
            }
        }
    }

    /// <summary>Overwrites the persisted settings with <paramref name="data"/>.</summary>
    public void Save(SettingsData data)
    {
        ArgumentNullException.ThrowIfNull(data);

        lock (_sync)
        {
            var dir = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(dir))
                Directory.CreateDirectory(dir);

            var json = JsonSerializer.Serialize(data, JsonOptions);
            File.WriteAllText(_path, json);
        }
    }
}
