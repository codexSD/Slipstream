using Slipstream.Core;
using Slipstream.Core.Control;
using Slipstream.Core.Identity;
using Slipstream.Core.Pairing;

namespace Slipstream.Core.Tests.Pairing;

public class PairingSessionTests
{
    private readonly DeviceIdentity _local = DeviceIdentity.CreateNew("Local PC");
    private readonly DeviceIdentity _remote = DeviceIdentity.CreateNew("Remote Phone");

    private PairOfferPayload RemoteOffer() => new(
        SlipstreamPorts.ProtocolVersion, _remote.DeviceId, _remote.DisplayName, _remote.Fingerprint);

    private PairingSession Started()
    {
        var session = new PairingSession(_local);
        session.ReceiveOffer(RemoteOffer(), _remote.Fingerprint);
        return session;
    }

    [Fact]
    public void Starts_awaiting_an_offer_with_no_code()
    {
        var session = new PairingSession(_local);

        Assert.Equal(PairingState.AwaitingOffer, session.State);
        Assert.Null(session.Code);
        Assert.Null(session.Result);
    }

    [Fact]
    public void Receiving_an_offer_derives_the_code()
    {
        var session = Started();

        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
        Assert.Equal(PairingCode.Derive(_local.Fingerprint, _remote.Fingerprint), session.Code);
    }

    [Fact]
    public void Both_devices_derive_the_same_code()
    {
        // The order-independence that makes "compare these two numbers" work at all.
        var here = new PairingSession(_local);
        here.ReceiveOffer(RemoteOffer(), _remote.Fingerprint);

        var there = new PairingSession(_remote);
        there.ReceiveOffer(
            new PairOfferPayload(SlipstreamPorts.ProtocolVersion, _local.DeviceId, _local.DisplayName, _local.Fingerprint),
            _local.Fingerprint);

        Assert.Equal(here.Code, there.Code);
    }

    [Fact]
    public void An_offer_whose_claimed_fingerprint_differs_from_the_certificate_is_rejected()
    {
        // The payload is peer-supplied text; the certificate is proof. Only the
        // certificate may drive the code, or a MITM could forge a matching one.
        var session = new PairingSession(_local);
        var lying = new PairOfferPayload(
            SlipstreamPorts.ProtocolVersion, _remote.DeviceId, _remote.DisplayName, "a-fingerprint-it-does-not-hold");

        session.ReceiveOffer(lying, verifiedFingerprint: _remote.Fingerprint);

        Assert.Equal(PairingState.Cancelled, session.State);
        Assert.Null(session.Code);
    }

    [Fact]
    public void A_local_confirm_alone_does_not_pair()
    {
        var session = Started();
        session.ConfirmLocally();

        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void A_remote_confirm_alone_does_not_pair()
    {
        var session = Started();
        session.ReceiveRemoteConfirm();

        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void Both_confirmations_pair_regardless_of_order()
    {
        var localFirst = Started();
        localFirst.ConfirmLocally();
        localFirst.ReceiveRemoteConfirm();

        var remoteFirst = Started();
        remoteFirst.ReceiveRemoteConfirm();
        remoteFirst.ConfirmLocally();

        Assert.Equal(PairingState.Paired, localFirst.State);
        Assert.Equal(PairingState.Paired, remoteFirst.State);
    }

    [Fact]
    public void The_result_carries_the_certificate_fingerprint_and_the_offered_name()
    {
        var session = Started();
        session.ConfirmLocally();
        session.ReceiveRemoteConfirm();

        var result = session.Result!;
        Assert.Equal(_remote.DeviceId, result.DeviceId);
        Assert.Equal(_remote.Fingerprint, result.Fingerprint);
        Assert.Equal("Remote Phone", result.DisplayName);
    }

    [Fact]
    public void Cancelling_stops_any_later_confirmation_from_pairing()
    {
        var session = Started();
        session.Cancel();

        session.ConfirmLocally();
        session.ReceiveRemoteConfirm();

        Assert.Equal(PairingState.Cancelled, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void A_second_offer_is_ignored()
    {
        var session = Started();
        var code = session.Code;

        session.ReceiveOffer(RemoteOffer(), _remote.Fingerprint);

        Assert.Equal(code, session.Code);
        Assert.Equal(PairingState.AwaitingConfirmation, session.State);
    }

    [Fact]
    public void Confirming_before_an_offer_arrives_is_ignored()
    {
        var session = new PairingSession(_local);
        session.ConfirmLocally();
        session.ReceiveRemoteConfirm();

        Assert.Equal(PairingState.AwaitingOffer, session.State);
        Assert.Null(session.Result);
    }

    [Fact]
    public void An_offer_from_our_own_fingerprint_is_rejected()
    {
        // Self-discovery must never pair a device with itself.
        var session = new PairingSession(_local);
        session.ReceiveOffer(
            new PairOfferPayload(SlipstreamPorts.ProtocolVersion, _local.DeviceId, _local.DisplayName, _local.Fingerprint),
            _local.Fingerprint);

        Assert.Equal(PairingState.Cancelled, session.State);
    }
}
