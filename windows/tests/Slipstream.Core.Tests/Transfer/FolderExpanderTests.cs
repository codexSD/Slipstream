using Slipstream.Core.Transfer;

namespace Slipstream.Core.Tests.Transfer;

public class FolderExpanderTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("slipstream-folder-").FullName;

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    private string Make(string relative, string content = "x")
    {
        var path = Path.Combine(_dir, relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, content);
        return path;
    }

    [Fact]
    public void Expands_a_flat_folder()
    {
        Make("a.txt");
        Make("b.txt");

        var items = FolderExpander.Expand(_dir);

        Assert.Equal(2, items.Count(i => !i.IsDirectory));
        Assert.Contains(items, i => i.RelativePath == "a.txt");
    }

    [Fact]
    public void Expands_nested_folders_with_forward_slash_relative_paths()
    {
        Make("photos/2026/holiday.jpg");

        var items = FolderExpander.Expand(_dir);
        var file = items.Single(i => !i.IsDirectory);

        Assert.Equal("photos/2026/holiday.jpg", file.RelativePath);
        Assert.DoesNotContain('\\', file.RelativePath);
    }

    [Fact]
    public void Records_file_sizes()
    {
        Make("sized.txt", new string('x', 1234));

        Assert.Equal(1234, FolderExpander.Expand(_dir).Single(i => !i.IsDirectory).Size);
    }

    [Fact]
    public void Preserves_empty_directories()
    {
        Directory.CreateDirectory(Path.Combine(_dir, "empty-one", "empty-two"));

        var directories = FolderExpander.Expand(_dir).Where(i => i.IsDirectory).ToList();

        Assert.Contains(directories, d => d.RelativePath == "empty-one/empty-two");
        Assert.All(directories, d => Assert.Equal(0, d.Size));
    }

    [Fact]
    public void A_single_file_path_expands_to_one_item()
    {
        var path = Make("solo.txt");

        var items = FolderExpander.Expand(path);

        Assert.Single(items);
        Assert.Equal("solo.txt", items[0].RelativePath);
        Assert.False(items[0].IsDirectory);
    }

    [Fact]
    public void Absolute_paths_point_at_the_real_files()
    {
        Make("real.txt", "content");

        var item = FolderExpander.Expand(_dir).Single(i => !i.IsDirectory);

        Assert.True(File.Exists(item.AbsolutePath));
        Assert.Equal("content", File.ReadAllText(item.AbsolutePath));
    }

    [Fact]
    public void An_empty_folder_yields_no_items()
    {
        Assert.Empty(FolderExpander.Expand(_dir));
    }

    [Fact]
    public void A_missing_path_throws()
    {
        Assert.Throws<DirectoryNotFoundException>(
            () => FolderExpander.Expand(Path.Combine(_dir, "nope")));
    }
}
