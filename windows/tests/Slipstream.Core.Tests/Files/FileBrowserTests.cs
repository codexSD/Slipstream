using System.Text.Json;
using Slipstream.Core.Control;
using Slipstream.Core.Files;

namespace Slipstream.Core.Tests.Files;

public class FileBrowserTests : IDisposable
{
    /// <summary>
    /// The two implementations never agreed on the listing schema. Windows serialized
    /// `modified` (an ISO timestamp) while Android's parser required `mtimeMs` (epoch millis)
    /// and threw NoSuchElementException on every single listing — so browsing the PC from the
    /// phone could not work, and never had. The spec (§6) names the field `mtime`; Android
    /// matched it, Windows drifted. Serialization is the contract between two codebases that
    /// share no types, so it gets a test.
    /// </summary>
    [Fact]
    public void A_listed_entry_serializes_the_fields_the_peer_actually_reads()
    {
        Make("a.txt", "hi");

        var entry = _browser.List(_dir).Entries.Single(e => e.Name == "a.txt");
        var json = JsonSerializer.Serialize(entry, ControlMessage.Json);

        using var parsed = JsonDocument.Parse(json);
        var fields = parsed.RootElement;

        Assert.Equal("a.txt", fields.GetProperty("name").GetString());
        Assert.Equal(2, fields.GetProperty("size").GetInt64());
        Assert.False(fields.GetProperty("isDirectory").GetBoolean());

        // The field the Android parser requires, as epoch milliseconds.
        Assert.True(fields.GetProperty("mtimeMs").GetInt64() > 0);
    }

    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-browse-").FullName;
    private readonly FileBrowser _browser = new();

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private void Make(string name, string content = "x") =>
        File.WriteAllText(Path.Combine(_dir, name), content);

    [Fact]
    public void Lists_files_and_directories()
    {
        Make("a.txt");
        Directory.CreateDirectory(Path.Combine(_dir, "sub"));

        var result = _browser.List(_dir);

        Assert.Equal(2, result.Entries.Count);
        Assert.Contains(result.Entries, e => e.Name == "a.txt" && !e.IsDirectory);
        Assert.Contains(result.Entries, e => e.Name == "sub" && e.IsDirectory);
    }

    [Fact]
    public void Directories_sort_before_files()
    {
        Make("aaa.txt");
        Directory.CreateDirectory(Path.Combine(_dir, "zzz"));

        var entries = _browser.List(_dir).Entries;

        Assert.True(entries[0].IsDirectory);
        Assert.Equal("zzz", entries[0].Name);
    }

    [Fact]
    public void Infers_mime_types_for_media()
    {
        Make("clip.mp4");
        Make("song.mp3");
        Make("photo.jpg");
        Make("mystery.zzz");

        var entries = _browser.List(_dir).Entries.ToDictionary(e => e.Name);

        Assert.Equal("video/mp4", entries["clip.mp4"].Mime);
        Assert.Equal("audio/mpeg", entries["song.mp3"].Mime);
        Assert.Equal("image/jpeg", entries["photo.jpg"].Mime);
        Assert.Equal("application/octet-stream", entries["mystery.zzz"].Mime);
    }

    [Fact]
    public void Directories_have_no_mime_type()
    {
        Directory.CreateDirectory(Path.Combine(_dir, "sub"));
        Assert.Null(_browser.List(_dir).Entries.Single().Mime);
    }

    [Fact]
    public void Sorts_by_size_and_by_modified_on_request()
    {
        Make("small.txt", "x");
        Make("large.txt", new string('x', 5000));

        Assert.Equal("large.txt", _browser.List(_dir, "size").Entries[0].Name);
        Assert.Equal(2, _browser.List(_dir, "modified").Entries.Count);
    }

    [Fact]
    public void Caps_at_five_thousand_entries_and_flags_truncation()
    {
        for (var i = 0; i < FileBrowser.MaxEntries + 10; i++)
            Make($"file-{i:D5}.txt");

        var result = _browser.List(_dir);

        Assert.Equal(FileBrowser.MaxEntries, result.Entries.Count);
        Assert.True(result.Truncated);
    }

    [Fact]
    public void Does_not_flag_truncation_below_the_cap()
    {
        Make("only.txt");
        Assert.False(_browser.List(_dir).Truncated);
    }

    [Fact]
    public void Stat_returns_metadata_for_a_file()
    {
        Make("target.mp4", new string('x', 999));

        var entry = _browser.Stat(Path.Combine(_dir, "target.mp4"));

        Assert.NotNull(entry);
        Assert.Equal(999, entry.Size);
        Assert.Equal("video/mp4", entry.Mime);
        Assert.False(entry.IsDirectory);
    }

    [Fact]
    public void Stat_returns_null_for_a_missing_path()
    {
        Assert.Null(_browser.Stat(Path.Combine(_dir, "nope.txt")));
    }

    [Fact]
    public void Listing_a_missing_directory_throws()
    {
        Assert.Throws<DirectoryNotFoundException>(() => _browser.List(Path.Combine(_dir, "nope")));
    }

    [Fact]
    public void Roots_returns_the_available_drives()
    {
        var roots = _browser.Roots();

        Assert.NotEmpty(roots);
        Assert.All(roots, r => Assert.True(r.IsDirectory));
    }
}
