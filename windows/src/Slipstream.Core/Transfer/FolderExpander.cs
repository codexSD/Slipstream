namespace Slipstream.Core.Transfer;

public sealed record TransferItem(string AbsolutePath, string RelativePath, long Size, bool IsDirectory);

/// <summary>
/// Spec §7. Flattens a tree to relative paths so the receiver can recreate the
/// structure. Separators are normalised to '/' — Windows and Android must agree
/// on the wire representation.
/// </summary>
public static class FolderExpander
{
    public static IReadOnlyList<TransferItem> Expand(string rootPath)
    {
        if (File.Exists(rootPath))
        {
            var info = new FileInfo(rootPath);
            return [new TransferItem(info.FullName, info.Name, info.Length, IsDirectory: false)];
        }

        if (!Directory.Exists(rootPath))
            throw new DirectoryNotFoundException($"No file or folder at {rootPath}.");

        var root = new DirectoryInfo(rootPath).FullName.TrimEnd(Path.DirectorySeparatorChar);
        var items = new List<TransferItem>();

        foreach (var path in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
        {
            var info = new FileInfo(path);
            items.Add(new TransferItem(info.FullName, Relative(root, info.FullName), info.Length, false));
        }

        // Empty directories carry no files, so they must be listed explicitly or
        // they silently vanish on the receiving side.
        foreach (var path in Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories))
        {
            if (Directory.EnumerateFileSystemEntries(path).Any()) continue;
            items.Add(new TransferItem(path, Relative(root, path), 0, IsDirectory: true));
        }

        return items;
    }

    private static string Relative(string root, string fullPath) =>
        Path.GetRelativePath(root, fullPath).Replace(Path.DirectorySeparatorChar, '/');
}
