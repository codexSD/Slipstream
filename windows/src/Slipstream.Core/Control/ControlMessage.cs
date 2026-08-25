using System.Text.Json;
using System.Text.Json.Serialization;

namespace Slipstream.Core.Control;

public sealed class ControlProtocolException(string message) : Exception(message);

/// <summary>
/// Spec §6. Requests carry an id; responses echo it; events carry none.
/// </summary>
public sealed class ControlMessage
{
    internal static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    [JsonPropertyName("type")]
    public required string Type { get; init; }

    [JsonPropertyName("id")]
    public string? Id { get; init; }

    [JsonPropertyName("payload")]
    public JsonElement? Payload { get; init; }

    public static ControlMessage Request(string type, string id, object? payload = null) =>
        new() { Type = type, Id = id, Payload = ToElement(payload) };

    public static ControlMessage Response(string type, string id, object? payload = null) =>
        Request(type, id, payload);

    public static ControlMessage Event(string type, object? payload = null) =>
        new() { Type = type, Payload = ToElement(payload) };

    public T? PayloadAs<T>() =>
        Payload is null ? default : Payload.Value.Deserialize<T>(Json);

    private static JsonElement? ToElement(object? payload) =>
        payload is null ? null : JsonSerializer.SerializeToElement(payload, Json);
}
