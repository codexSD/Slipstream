using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class TokenVaultTests
{
    private static readonly Guid Transfer = Guid.Parse("11111111-1111-1111-1111-111111111111");

    [Fact]
    public void A_bulk_token_validates_as_many_times_as_a_fragmented_resume_needs()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 4);

        // A fragmented bitmap legitimately needs more connections than streams.
        for (var i = 0; i < 32; i++)
            Assert.NotNull(vault.ValidateBulk(token.Value, Transfer));
    }

    [Fact]
    public void A_bulk_token_expires_after_thirty_minutes()
    {
        var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-08-25T10:00:00Z"));
        var vault = new TokenVault(time);

        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 4);

        time.Advance(TimeSpan.FromMinutes(29));
        Assert.NotNull(vault.ValidateBulk(token.Value, Transfer));

        time.Advance(TimeSpan.FromMinutes(2));
        Assert.Null(vault.ValidateBulk(token.Value, Transfer));
    }

    [Fact]
    public void A_bulk_token_is_scoped_to_its_transfer_id()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 1);

        Assert.Null(vault.ValidateBulk(token.Value, Guid.NewGuid()));
    }

    [Fact]
    public void An_unknown_bulk_token_is_refused()
    {
        Assert.Null(new TokenVault().ValidateBulk(Guid.NewGuid(), Transfer));
    }

    [Fact]
    public void Validation_returns_the_path_and_size_the_token_was_issued_for()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\movies\film.mkv", 42_000, expectedStreams: 1);

        var validated = vault.ValidateBulk(token.Value, Transfer);

        Assert.Equal(@"C:\movies\film.mkv", validated!.Path);
        Assert.Equal(42_000, validated.Size);
    }

    [Fact]
    public void Revoke_invalidates_every_token_for_a_transfer()
    {
        var vault = new TokenVault();
        var token = vault.IssueBulk(Transfer, @"C:\file.bin", 1000, expectedStreams: 8);

        vault.Revoke(Transfer);

        Assert.Null(vault.ValidateBulk(token.Value, Transfer));
    }

    [Fact]
    public void Issued_tokens_are_unpredictable()
    {
        var vault = new TokenVault();
        var issued = Enumerable.Range(0, 100)
            .Select(_ => vault.IssueBulk(Guid.NewGuid(), "x", 1, 1).Value)
            .ToHashSet();

        Assert.Equal(100, issued.Count);
    }

    [Fact]
    public void A_media_token_validates_repeatedly_within_its_lifetime()
    {
        var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-08-25T10:00:00Z"));
        var vault = new TokenVault(time);

        var token = vault.IssueMedia(@"C:\movies\film.mkv", 1_000_000);

        Assert.NotNull(vault.ValidateMedia(token.Value));
        time.Advance(TimeSpan.FromHours(11));
        Assert.NotNull(vault.ValidateMedia(token.Value)); // seeking hours into a film must still work
    }

    [Fact]
    public void A_media_token_expires_after_twelve_hours()
    {
        var time = new FakeTimeProvider(DateTimeOffset.Parse("2026-08-25T10:00:00Z"));
        var vault = new TokenVault(time);

        var token = vault.IssueMedia(@"C:\movies\film.mkv", 1_000_000);
        time.Advance(TimeSpan.FromHours(12) + TimeSpan.FromMinutes(1));

        Assert.Null(vault.ValidateMedia(token.Value));
    }

    private sealed class FakeTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan by) => _now += by;
    }
}
