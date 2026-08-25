using System.Net;

namespace Slipstream.Core;

/// <summary>Fixed ports and groups. See spec §3.</summary>
public static class SlipstreamPorts
{
    public const int Discovery = 53320;
    public const int Control = 53321;
    public const int Bulk = 53322;
    public const int Media = 53323;

    /// <summary>
    /// Constrained to 224.0.0.0/24 — some Android devices reject other groups.
    /// </summary>
    public static readonly IPAddress MulticastGroup = IPAddress.Parse("224.0.0.167");

    public const int ProtocolVersion = 1;
}
