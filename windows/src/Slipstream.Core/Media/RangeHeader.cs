namespace Slipstream.Core.Media;

public readonly record struct ByteRangeSpec(long Start, long End)
{
    public long Length => End - Start + 1;
}

/// <summary>
/// RFC 7233 single-range parsing. Multipart ranges are not supported; the first
/// range is served, which is legal and is all any media player sends.
/// </summary>
public static class RangeHeader
{
    public static bool TryParse(string? header, long fileSize, out ByteRangeSpec range)
    {
        range = default;

        if (string.IsNullOrWhiteSpace(header)) return false;
        if (fileSize <= 0) return false;

        const string prefix = "bytes=";
        if (!header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;

        var first = header[prefix.Length..].Split(',')[0].Trim();
        var separator = first.IndexOf('-');
        if (separator < 0) return false;

        var startText = first[..separator].Trim();
        var endText = first[(separator + 1)..].Trim();

        long start;
        long end;

        if (startText.Length == 0)
        {
            // Suffix form: bytes=-N means the last N bytes.
            if (!long.TryParse(endText, out var suffixLength) || suffixLength <= 0) return false;

            start = Math.Max(0, fileSize - suffixLength);
            end = fileSize - 1;
        }
        else
        {
            if (!long.TryParse(startText, out start) || start < 0) return false;

            if (endText.Length == 0) end = fileSize - 1;
            else if (!long.TryParse(endText, out end)) return false;

            end = Math.Min(end, fileSize - 1);
        }

        if (start > end || start >= fileSize) return false;

        range = new ByteRangeSpec(start, end);
        return true;
    }
}
