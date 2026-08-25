using System.Text.Json;

namespace Slipstream.Core.Identity;

/// <summary>
/// Spec §4: exactly one paired peer at a time. Re-pairing replaces it.
/// </summary>
public sealed class PairedPeerStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly string _path;
    private PairedPeer? _current;

    public PairedPeerStore(string directory)
    {
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "paired-peer.json");

        if (File.Exists(_path))
        {
            try
            {
                _current = JsonSerializer.Deserialize<PairedPeer>(File.ReadAllText(_path), Json);
            }
            catch (JsonException)
            {
                // A corrupt store means unpaired, not a crash. The user re-pairs.
                _current = null;
            }
        }
    }

    public PairedPeer? Current => _current;

    public bool IsPaired => _current is not null;

    public void Pair(PairedPeer peer)
    {
        _current = peer;
        File.WriteAllText(_path, JsonSerializer.Serialize(peer, Json));
    }

    public void Unpair()
    {
        _current = null;
        if (File.Exists(_path)) File.Delete(_path);
    }

    public bool Trusts(string fingerprint) =>
        _current is not null &&
        string.Equals(_current.Fingerprint, fingerprint, StringComparison.OrdinalIgnoreCase);
}
