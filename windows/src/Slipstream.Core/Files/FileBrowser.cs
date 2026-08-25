namespace Slipstream.Core.Files;

public sealed record FileEntry(
    string Name,
    string Path,
    long Size,
    DateTimeOffset Modified,
    bool IsDirectory,
    string? Mime,
    string? ThumbnailToken);

public sealed record ListResult(string Path, IReadOnlyList<FileEntry> Entries, bool Truncated);

/// <summary>
/// Spec §6. Listings cap at 5000 entries with an honest flag rather than silently
/// truncating — no real directory on either device approaches the cap.
/// </summary>
public sealed class FileBrowser
{
    public const int MaxEntries = 5000;

    private static readonly Dictionary<string, string> MimeByExtension = new(StringComparer.OrdinalIgnoreCase)
    {
        [".mp4"] = "video/mp4", [".mkv"] = "video/x-matroska", [".avi"] = "video/x-msvideo",
        [".mov"] = "video/quicktime", [".webm"] = "video/webm", [".m4v"] = "video/x-m4v",
        [".mp3"] = "audio/mpeg", [".flac"] = "audio/flac", [".wav"] = "audio/wav",
        [".m4a"] = "audio/mp4", [".ogg"] = "audio/ogg", [".opus"] = "audio/opus",
        [".jpg"] = "image/jpeg", [".jpeg"] = "image/jpeg", [".png"] = "image/png",
        [".gif"] = "image/gif", [".webp"] = "image/webp", [".heic"] = "image/heic",
        [".pdf"] = "application/pdf", [".txt"] = "text/plain", [".zip"] = "application/zip",
    };

    public ListResult List(string path, string sort = "name")
    {
        if (!Directory.Exists(path))
            throw new DirectoryNotFoundException($"No folder at {path}.");

        var entries = new List<FileEntry>();
        var truncated = false;

        foreach (var entryPath in Directory.EnumerateFileSystemEntries(path))
        {
            if (entries.Count >= MaxEntries) { truncated = true; break; }

            var entry = Describe(entryPath);
            if (entry is not null) entries.Add(entry);
        }

        return new ListResult(path, Sort(entries, sort), truncated);
    }

    public FileEntry? Stat(string path) =>
        File.Exists(path) || Directory.Exists(path) ? Describe(path) : null;

    public IReadOnlyList<FileEntry> Roots() =>
        DriveInfo.GetDrives()
            .Where(d => d.IsReady)
            .Select(d => new FileEntry(
                string.IsNullOrWhiteSpace(d.VolumeLabel) ? d.Name : $"{d.VolumeLabel} ({d.Name})",
                d.RootDirectory.FullName, 0, DateTimeOffset.MinValue, true, null, null))
            .ToList();

    private static FileEntry? Describe(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                var info = new DirectoryInfo(path);
                return new FileEntry(info.Name, info.FullName, 0,
                    new DateTimeOffset(info.LastWriteTimeUtc, TimeSpan.Zero), true, null, null);
            }

            var file = new FileInfo(path);
            return new FileEntry(file.Name, file.FullName, file.Length,
                new DateTimeOffset(file.LastWriteTimeUtc, TimeSpan.Zero), false,
                MimeFor(file.Extension), null);
        }
        catch (UnauthorizedAccessException)
        {
            return null; // Skip what we cannot read rather than failing the listing.
        }
        catch (IOException)
        {
            return null;
        }
    }

    private static string MimeFor(string extension) =>
        MimeByExtension.GetValueOrDefault(extension, "application/octet-stream");

    private static List<FileEntry> Sort(List<FileEntry> entries, string sort)
    {
        // Directories always lead, whatever the sort — that is how a file browser reads.
        var comparer = sort switch
        {
            "size" => (Comparison<FileEntry>)((a, b) => b.Size.CompareTo(a.Size)),
            "modified" => (a, b) => b.Modified.CompareTo(a.Modified),
            _ => (a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase),
        };

        entries.Sort((a, b) =>
            a.IsDirectory != b.IsDirectory ? (a.IsDirectory ? -1 : 1) : comparer(a, b));

        return entries;
    }
}
