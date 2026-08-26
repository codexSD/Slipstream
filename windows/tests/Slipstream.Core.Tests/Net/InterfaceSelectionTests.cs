using System.Net;
using System.Net.NetworkInformation;
using Slipstream.Core.Net;

namespace Slipstream.Core.Tests.Net;

/// <summary>
/// Ranking of candidate interfaces. The measured table in <see cref="Picks_the_wifi_adapter_from_the_measured_live_table"/>
/// is a real capture from the development machine, where the pre-ranking implementation
/// (first match wins) selected the Hyper-V default switch and starved every discovery
/// strategy: no gateway (S2 cannot run) and a /20 prefix (S4 refuses to sweep).
/// </summary>
public class InterfaceSelectionTests
{
    private static InterfaceCandidate Candidate(
        string name,
        string address,
        int prefix,
        string? gateway = null,
        NetworkInterfaceType type = NetworkInterfaceType.Ethernet,
        string? description = null,
        int index = 1,
        string? id = null) =>
        new(
            Id: id ?? "{" + name + "}",
            Name: name,
            Description: description ?? name,
            Type: type,
            Index: index,
            Address: IPAddress.Parse(address),
            PrefixLength: prefix,
            Gateway: gateway is null ? null : IPAddress.Parse(gateway));

    /// <summary>The exact adapter table measured on the live development machine.</summary>
    private static readonly InterfaceCandidate[] MeasuredTable =
    [
        Candidate("vEthernet (Default Switch)", "192.168.112.1", 20, index: 11,
            description: "Hyper-V Virtual Ethernet Adapter"),
        Candidate("Local Area Connection* 4", "169.254.178.75", 16, index: 14,
            type: NetworkInterfaceType.Wireless80211,
            description: "Microsoft Wi-Fi Direct Virtual Adapter #2"),
        Candidate("Ethernet 3", "10.212.134.200", 32, index: 21,
            type: NetworkInterfaceType.Ppp, description: "TAP-Windows Adapter V9"),
        Candidate("Ethernet", "192.168.43.1", 24, gateway: "192.168.43.0", index: 8),
        Candidate("Wi-Fi", "10.199.176.38", 24, gateway: "10.199.176.137", index: 17,
            type: NetworkInterfaceType.Wireless80211,
            description: "Intel(R) Wi-Fi 6 AX201 160MHz"),
    ];

    [Fact]
    public void Picks_the_wifi_adapter_from_the_measured_live_table()
    {
        var selected = NetworkInfo.Select(MeasuredTable);

        Assert.NotNull(selected);
        Assert.Equal(IPAddress.Parse("10.199.176.38"), selected!.LocalAddress);
        Assert.Equal(IPAddress.Parse("10.199.176.137"), selected.Gateway);
        Assert.Equal(24, selected.PrefixLength);
    }

    [Fact]
    public void Measured_table_selection_can_actually_sweep_and_probe()
    {
        var selected = NetworkInfo.Select(MeasuredTable)!;

        // The two consequences the old first-match behaviour destroyed.
        Assert.NotNull(selected.Gateway);                                          // S2 can run
        Assert.NotEmpty(SubnetMath.EnumerateHosts(selected.LocalAddress, selected.PrefixLength)); // S4 can run
    }

    [Fact]
    public void An_interface_with_a_gateway_beats_one_without()
    {
        var selected = NetworkInfo.Select(
        [
            Candidate("Ethernet 2", "192.168.5.10", 24, index: 3),
            Candidate("Ethernet 9", "192.168.9.10", 24, gateway: "192.168.9.1", index: 40),
        ]);

        Assert.Equal(IPAddress.Parse("192.168.9.10"), selected!.LocalAddress);
    }

    [Fact]
    public void A_physical_adapter_beats_a_virtual_one_when_both_have_gateways()
    {
        var selected = NetworkInfo.Select(
        [
            Candidate("vEthernet (WSL)", "172.20.0.1", 24, gateway: "172.20.0.254", index: 2,
                description: "Hyper-V Virtual Ethernet Adapter"),
            Candidate("Wi-Fi", "192.168.1.42", 24, gateway: "192.168.1.1", index: 30,
                type: NetworkInterfaceType.Wireless80211, description: "Intel(R) Wi-Fi 6 AX201"),
        ]);

        Assert.Equal(IPAddress.Parse("192.168.1.42"), selected!.LocalAddress);
    }

    [Fact]
    public void An_apipa_only_interface_loses_to_any_real_one()
    {
        var selected = NetworkInfo.Select(
        [
            Candidate("Ethernet 5", "169.254.10.20", 16, index: 1),
            Candidate("Ethernet 6", "192.168.8.20", 24, index: 50),
        ]);

        Assert.Equal(IPAddress.Parse("192.168.8.20"), selected!.LocalAddress);
    }

    [Fact]
    public void An_apipa_interface_with_a_gateway_still_loses_to_a_routable_one()
    {
        var selected = NetworkInfo.Select(
        [
            Candidate("Ethernet 5", "169.254.10.20", 16, gateway: "169.254.0.1", index: 1),
            Candidate("Ethernet 6", "192.168.8.20", 24, gateway: "192.168.8.1", index: 50),
        ]);

        Assert.Equal(IPAddress.Parse("192.168.8.20"), selected!.LocalAddress);
    }

    [Fact]
    public void Ties_resolve_to_the_lowest_interface_index_not_enumeration_order()
    {
        InterfaceCandidate[] candidates =
        [
            Candidate("Ethernet A", "192.168.4.10", 24, gateway: "192.168.4.1", index: 22),
            Candidate("Ethernet B", "192.168.6.10", 24, gateway: "192.168.6.1", index: 7),
        ];

        Assert.Equal(IPAddress.Parse("192.168.6.10"), NetworkInfo.Select(candidates)!.LocalAddress);
        // Reversing the enumeration must not change the answer.
        Assert.Equal(IPAddress.Parse("192.168.6.10"), NetworkInfo.Select(candidates.Reverse())!.LocalAddress);
    }

    [Fact]
    public void Ties_on_the_index_too_resolve_to_the_ordinal_interface_id()
    {
        InterfaceCandidate[] candidates =
        [
            Candidate("Ethernet A", "192.168.4.10", 24, gateway: "192.168.4.1", index: 5, id: "{BBB}"),
            Candidate("Ethernet B", "192.168.6.10", 24, gateway: "192.168.6.1", index: 5, id: "{AAA}"),
        ];

        Assert.Equal(IPAddress.Parse("192.168.6.10"), NetworkInfo.Select(candidates)!.LocalAddress);
        Assert.Equal(IPAddress.Parse("192.168.6.10"), NetworkInfo.Select(candidates.Reverse())!.LocalAddress);
    }

    /// <summary>
    /// The mirror image of the Android AP-binding fix: when this PC hosts the hotspot, its AP
    /// interface has no default gateway at all. Ranking gateways first must not make that
    /// address unreachable — it still has to beat every virtual adapter and every APIPA one.
    /// </summary>
    [Fact]
    public void The_hosted_hotspot_interface_still_wins_when_nothing_has_a_gateway()
    {
        var selected = NetworkInfo.Select(
        [
            Candidate("vEthernet (Default Switch)", "192.168.112.1", 20, index: 11,
                description: "Hyper-V Virtual Ethernet Adapter"),
            Candidate("Local Area Connection* 4", "192.168.137.1", 24, index: 14,
                type: NetworkInterfaceType.Wireless80211,
                description: "Microsoft Wi-Fi Direct Virtual Adapter #2"),
            Candidate("Bluetooth Network Connection", "169.254.3.4", 16, index: 6,
                description: "Bluetooth Device (Personal Area Network)"),
        ]);

        Assert.Equal(IPAddress.Parse("192.168.137.1"), selected!.LocalAddress);
        Assert.Null(selected.Gateway);
        Assert.Equal(24, selected.PrefixLength);
    }

    [Fact]
    public void Returns_null_when_there_is_no_candidate_at_all()
    {
        Assert.Null(NetworkInfo.Select([]));
    }

    [Fact]
    public void The_key_is_stable_for_a_candidate_and_distinct_across_subnets()
    {
        var a = NetworkInfo.Select([Candidate("Wi-Fi", "10.199.176.38", 24, gateway: "10.199.176.137")])!;
        var again = NetworkInfo.Select([Candidate("Wi-Fi", "10.199.176.99", 24, gateway: "10.199.176.137")])!;
        var elsewhere = NetworkInfo.Select([Candidate("Wi-Fi", "192.168.1.5", 24, gateway: "192.168.1.1")])!;

        Assert.Equal(a.Key, again.Key);          // same adapter, same subnet, new lease
        Assert.NotEqual(a.Key, elsewhere.Key);   // same adapter, different network
    }
}
