using System.Net;
using System.Net.Sockets;

namespace Slipstream.Core.Net;

public sealed class NonLocalAddressException(IPAddress address)
    : Exception($"Refused non-local address {address}. Slipstream never leaves the local network.")
{
    public IPAddress Address { get; } = address;
}

/// <summary>
/// Spec §11 layer 2: the only addresses Slipstream will connect to or accept from.
/// Applied to both inbound and outbound connections.
/// </summary>
public static class LanGuard
{
    public static bool IsLocal(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6)
            address = address.MapToIPv4();

        return address.AddressFamily switch
        {
            AddressFamily.InterNetwork => IsLocalV4(address),
            AddressFamily.InterNetworkV6 => IsLocalV6(address),
            _ => false,
        };
    }

    private static bool IsLocalV4(IPAddress address)
    {
        Span<byte> b = stackalloc byte[4];
        if (!address.TryWriteBytes(b, out _)) return false;

        if (b[0] == 10) return true;                          // 10.0.0.0/8
        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true; // 172.16.0.0/12
        if (b[0] == 192 && b[1] == 168) return true;          // 192.168.0.0/16
        if (b[0] == 169 && b[1] == 254) return true;          // 169.254.0.0/16
        if (b[0] == 127) return true;                         // loopback, for tests
        return false;
    }

    private static bool IsLocalV6(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) return true;
        if (address.IsIPv6LinkLocal) return true;             // fe80::/10

        Span<byte> b = stackalloc byte[16];
        if (!address.TryWriteBytes(b, out _)) return false;
        return (b[0] & 0xFE) == 0xFC;                         // fc00::/7 unique-local
    }

    public static void EnsureLocal(IPAddress address)
    {
        if (!IsLocal(address)) throw new NonLocalAddressException(address);
    }
}
