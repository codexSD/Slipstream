using Slipstream.Core.Media;

namespace Slipstream.Core.Tests.Media;

public class RangeHeaderTests
{
    [Fact]
    public void Parses_a_closed_range()
    {
        Assert.True(RangeHeader.TryParse("bytes=0-499", 1000, out var range));
        Assert.Equal(0, range.Start);
        Assert.Equal(499, range.End);
        Assert.Equal(500, range.Length);
    }

    [Fact]
    public void Parses_an_open_ended_range()
    {
        Assert.True(RangeHeader.TryParse("bytes=500-", 1000, out var range));
        Assert.Equal(500, range.Start);
        Assert.Equal(999, range.End);
    }

    [Fact]
    public void Parses_a_suffix_range()
    {
        Assert.True(RangeHeader.TryParse("bytes=-200", 1000, out var range));
        Assert.Equal(800, range.Start);
        Assert.Equal(999, range.End);
    }

    [Fact]
    public void Clamps_an_end_beyond_the_file()
    {
        Assert.True(RangeHeader.TryParse("bytes=900-5000", 1000, out var range));
        Assert.Equal(999, range.End);
    }

    [Fact]
    public void A_suffix_longer_than_the_file_yields_the_whole_file()
    {
        Assert.True(RangeHeader.TryParse("bytes=-5000", 1000, out var range));
        Assert.Equal(0, range.Start);
        Assert.Equal(999, range.End);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("items=0-100")]
    [InlineData("bytes=abc-def")]
    [InlineData("bytes=500-100")]   // inverted
    [InlineData("bytes=2000-3000")] // wholly past the end
    [InlineData("bytes=-")]
    public void Rejects_malformed_or_unsatisfiable_ranges(string? header)
    {
        Assert.False(RangeHeader.TryParse(header, 1000, out _));
    }

    [Fact]
    public void Takes_the_first_range_of_a_multi_range_request()
    {
        // Multipart responses are not supported; serving the first range is legal
        // and is what every media player actually sends anyway.
        Assert.True(RangeHeader.TryParse("bytes=0-99,200-299", 1000, out var range));
        Assert.Equal(0, range.Start);
        Assert.Equal(99, range.End);
    }
}
