using System.Collections.Concurrent;
using System.Net;
using System.Text.Json;

namespace Slipstream.Core.Discovery;

/// <summary>
/// Spec §5 S1. Keyed per network so the hotspot address and the home-WiFi address
/// do not overwrite each other.
/// </summary>
public sealed class EndpointCache
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly string _path;
    private readonly ConcurrentDictionary<string, string> _entries;

    public EndpointCache(string directory)
    {
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "endpoint-cache.json");

        Dictionary<string, string>? loaded = null;
        if (File.Exists(_path))
        {
            try
            {
                loaded = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(_path), Json);
            }
            catch (JsonException)
            {
                loaded = null; // A corrupt cache is a cold cache, not a crash.
            }
        }

        _entries = new ConcurrentDictionary<string, string>(
            loaded ?? new Dictionary<string, string>(), StringComparer.OrdinalIgnoreCase);
    }

    public IPEndPoint? Get(string networkKey) =>
        _entries.TryGetValue(networkKey, out var value) && IPEndPoint.TryParse(value, out var endpoint)
            ? endpoint
            : null;

    public void Set(string networkKey, IPEndPoint endpoint)
    {
        _entries[networkKey] = endpoint.ToString();
        Save();
    }

    public void Clear()
    {
        _entries.Clear();
        Save();
    }

    private void Save() =>
        File.WriteAllText(_path, JsonSerializer.Serialize(
            new Dictionary<string, string>(_entries), Json));
}
