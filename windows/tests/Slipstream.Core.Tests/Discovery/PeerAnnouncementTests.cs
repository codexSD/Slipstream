using System.Text;
using Slipstream.Core;
using Slipstream.Core.Discovery;

namespace Slipstream.Core.Tests.Discovery;

public class PeerAnnouncementTests
{
    private static PeerAnnouncement Sample() => new(
        Version: SlipstreamPorts.ProtocolVersion,
        DeviceId: "abc123",
        DisplayName: "Test PC",
        Fingerprint: "deadbeef",
        ControlPort: SlipstreamPorts.Control,
        Kind: AnnouncementKind.Announce);

    [Fact]
    public void Round_trips_through_bytes()
    {
        var parsed = PeerAnnouncement.TryParse(Sample().ToBytes());

        Assert.NotNull(parsed);
        Assert.Equal(Sample(), parsed);
    }

    [Fact]
    public void Serialises_kind_as_a_lowercase_string()
    {
        var json = Encoding.UTF8.GetString(Sample().ToBytes());
        Assert.Contains("\"kind\":\"announce\"", json);
        Assert.Contains("\"v\":1", json);
    }

    [Theory]
    [InlineData("")]
    [InlineData("not json at all")]
    [InlineData("{}")]
    [InlineData("{\"v\":1}")]
    [InlineData("{\"v\":1,\"deviceId\":\"a\"}")]
    public void TryParse_returns_null_for_malformed_input(string payload)
    {
        Assert.Null(PeerAnnouncement.TryParse(Encoding.UTF8.GetBytes(payload)));
    }

    [Fact]
    public void TryParse_returns_null_for_a_future_protocol_version()
    {
        var future = Sample() with { Version = 99 };
        Assert.Null(PeerAnnouncement.TryParse(future.ToBytes()));
    }

    [Fact]
    public void Payload_stays_within_a_single_datagram()
    {
        var large = Sample() with { DisplayName = new string('x', 200) };
        Assert.True(large.ToBytes().Length < 1024);
    }
}
