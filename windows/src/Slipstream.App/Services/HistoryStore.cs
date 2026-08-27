using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slipstream.App.Services;

/// <summary>
/// Persists completed transfers to a single JSON file under the app's state directory
/// (`%LOCALAPPDATA%\Slipstream\history.json` by default — see <see cref="DefaultPath"/>).
/// No database: the brief caps history at 500 rows, which does not warrant one. Every
/// <see cref="Add"/> call reads, mutates, and rewrites the whole file — fine at this scale,
/// and it means the file is always a complete, valid snapshot (no separate migration/compaction
/// step, no partial-write format to reason about). Not designed for concurrent writers from
/// multiple processes; this app has exactly one.
/// </summary>
public sealed class HistoryStore
{
    /// <summary>Newest entries are kept; anything past this count is evicted oldest-first.</summary>
    public const int MaxEntries = 500;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    private readonly string _path;
    private readonly object _sync = new();

    public HistoryStore(string? path = null)
    {
        _path = path ?? DefaultPath();
    }

    /// <summary>`%LOCALAPPDATA%\Slipstream\history.json` — the standard per-user state location
    /// for an unpackaged WinUI 3 desktop app (this project does not use MSIX packaging, so
    /// Windows.Storage.ApplicationData is unavailable; Environment.SpecialFolder is the
    /// equivalent unpackaged convention).</summary>
    public static string DefaultPath()
    {
        return Path.Combine(Slipstream.Core.SlipstreamPaths.StateDirectory, "history.json");
    }

    /// <summary>Appends one completed transfer, evicting the oldest entry if the store is now
    /// over <see cref="MaxEntries"/>.</summary>
    public void Add(HistoryEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);

        lock (_sync)
        {
            var entries = ReadAll();
            entries.Add(entry);

            // Oldest-first eviction: sort ascending by completion time, drop from the front.
            if (entries.Count > MaxEntries)
            {
                entries.Sort((a, b) => a.CompletedAtUtc.CompareTo(b.CompletedAtUtc));
                entries.RemoveRange(0, entries.Count - MaxEntries);
            }

            WriteAll(entries);
        }
    }

    /// <summary>Every stored entry, newest first.</summary>
    public IReadOnlyList<HistoryEntry> GetAll()
    {
        lock (_sync)
        {
            var entries = ReadAll();
            entries.Sort((a, b) => b.CompletedAtUtc.CompareTo(a.CompletedAtUtc));
            return entries;
        }
    }

    [System.Diagnostics.CodeAnalysis.UnconditionalSuppressMessage("Trimming", "IL2026",
        Justification = "HistoryEntry is a closed, non-polymorphic record with only primitive/enum members; nothing here needs reflection metadata that trimming would remove.")]
    private List<HistoryEntry> ReadAll()
    {
        if (!File.Exists(_path)) return [];

        var json = File.ReadAllText(_path);
        if (string.IsNullOrWhiteSpace(json)) return [];

        return JsonSerializer.Deserialize<List<HistoryEntry>>(json, JsonOptions) ?? [];
    }

    [System.Diagnostics.CodeAnalysis.UnconditionalSuppressMessage("Trimming", "IL2026",
        Justification = "HistoryEntry is a closed, non-polymorphic record with only primitive/enum members; nothing here needs reflection metadata that trimming would remove.")]
    private void WriteAll(List<HistoryEntry> entries)
    {
        var dir = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        var json = JsonSerializer.Serialize(entries, JsonOptions);
        File.WriteAllText(_path, json);
    }
}
