using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slipstream.Core.Discovery;

[JsonConverter(typeof(JsonStringEnumConverter<AnnouncementKind>))]
public enum AnnouncementKind
{
    [JsonStringEnumMemberName("announce")] Announce,
    [JsonStringEnumMemberName("query")] Query,
}

/// <summary>
/// Spec §5 S3. Sent to the multicast group and, as a fallback, unicast back to a
/// querying peer for networks that deliver multicast in one direction only.
/// </summary>
public sealed record PeerAnnouncement(
    [property: JsonPropertyName("v")] int Version,
    [property: JsonPropertyName("deviceId")] string DeviceId,
    [property: JsonPropertyName("name")] string DisplayName,
    [property: JsonPropertyName("fingerprint")] string Fingerprint,
    [property: JsonPropertyName("control")] int ControlPort,
    [property: JsonPropertyName("kind")] AnnouncementKind Kind)
{
    private static readonly JsonSerializerOptions Options = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public byte[] ToBytes() => JsonSerializer.SerializeToUtf8Bytes(this, Options);

    /// <summary>Returns null on anything malformed — this parses untrusted network data.</summary>
    public static PeerAnnouncement? TryParse(ReadOnlySpan<byte> utf8)
    {
        try
        {
            var parsed = JsonSerializer.Deserialize<PeerAnnouncement>(utf8, Options);

            if (parsed is null) return null;
            if (parsed.Version != SlipstreamPorts.ProtocolVersion) return null;
            if (string.IsNullOrWhiteSpace(parsed.DeviceId)) return null;
            if (string.IsNullOrWhiteSpace(parsed.Fingerprint)) return null;
            if (parsed.ControlPort is <= 0 or > 65535) return null;

            return parsed;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
