using System.Text;
using Slipstream.Core.Control;

namespace Slipstream.Core.Tests.Control;

public class JsonLineCodecTests
{
    private static MemoryStream StreamOf(string text) =>
        new(Encoding.UTF8.GetBytes(text));

    [Fact]
    public async Task Round_trips_a_request_with_a_payload()
    {
        using var buffer = new MemoryStream();
        var writer = new JsonLineCodec(buffer);

        await writer.WriteAsync(
            ControlMessage.Request("list", "7f3a", new { path = "/DCIM", sort = "name" }),
            CancellationToken.None);

        buffer.Position = 0;
        var message = await new JsonLineCodec(buffer).ReadAsync(CancellationToken.None);

        Assert.NotNull(message);
        Assert.Equal("list", message.Type);
        Assert.Equal("7f3a", message.Id);
        Assert.Equal("/DCIM", message.Payload!.Value.GetProperty("path").GetString());
    }

    [Fact]
    public async Task Writes_one_message_per_line()
    {
        using var buffer = new MemoryStream();
        var codec = new JsonLineCodec(buffer);

        await codec.WriteAsync(ControlMessage.Event("ping"), CancellationToken.None);
        await codec.WriteAsync(ControlMessage.Event("pong"), CancellationToken.None);

        var text = Encoding.UTF8.GetString(buffer.ToArray());
        var lines = text.Split('\n', StringSplitOptions.RemoveEmptyEntries);

        Assert.Equal(2, lines.Length);
        Assert.DoesNotContain('\n', lines[0]);
    }

    [Fact]
    public async Task Reads_multiple_messages_in_sequence()
    {
        using var stream = StreamOf("{\"type\":\"ping\"}\n{\"type\":\"pong\"}\n");
        var codec = new JsonLineCodec(stream);

        Assert.Equal("ping", (await codec.ReadAsync(CancellationToken.None))!.Type);
        Assert.Equal("pong", (await codec.ReadAsync(CancellationToken.None))!.Type);
        Assert.Null(await codec.ReadAsync(CancellationToken.None));
    }

    [Fact]
    public async Task Events_carry_no_id()
    {
        using var buffer = new MemoryStream();
        await new JsonLineCodec(buffer).WriteAsync(
            ControlMessage.Event("transfer.progress", new { bytes = 42 }), CancellationToken.None);

        var text = Encoding.UTF8.GetString(buffer.ToArray());
        Assert.DoesNotContain("\"id\"", text);
    }

    [Fact]
    public async Task Skips_blank_lines()
    {
        using var stream = StreamOf("\n\n{\"type\":\"ping\"}\n");
        Assert.Equal("ping", (await new JsonLineCodec(stream).ReadAsync(CancellationToken.None))!.Type);
    }

    [Fact]
    public async Task Skips_a_malformed_line_rather_than_failing_the_connection()
    {
        using var stream = StreamOf("not json\n{\"type\":\"ping\"}\n");
        Assert.Equal("ping", (await new JsonLineCodec(stream).ReadAsync(CancellationToken.None))!.Type);
    }

    [Fact]
    public async Task Skips_a_line_with_no_type_field()
    {
        using var stream = StreamOf("{\"id\":\"a\"}\n{\"type\":\"ping\"}\n");
        Assert.Equal("ping", (await new JsonLineCodec(stream).ReadAsync(CancellationToken.None))!.Type);
    }

    [Fact]
    public async Task Rejects_an_over_long_line()
    {
        var oversized = new string('x', JsonLineCodec.MaxLineBytes + 10);
        using var stream = StreamOf($"{{\"type\":\"{oversized}\"}}\n");

        await Assert.ThrowsAsync<ControlProtocolException>(
            () => new JsonLineCodec(stream).ReadAsync(CancellationToken.None));
    }

    [Fact]
    public async Task PayloadAs_deserialises_into_a_typed_record()
    {
        using var buffer = new MemoryStream();
        await new JsonLineCodec(buffer).WriteAsync(
            ControlMessage.Request("hello", "1", new HelloPayload("abc", "Test PC", 1)),
            CancellationToken.None);

        buffer.Position = 0;
        var message = await new JsonLineCodec(buffer).ReadAsync(CancellationToken.None);

        var payload = message!.PayloadAs<HelloPayload>();
        Assert.Equal("abc", payload!.DeviceId);
        Assert.Equal(1, payload.Version);
    }

    private sealed record HelloPayload(string DeviceId, string Name, int Version);
}
