using Slipstream.App.Pages;
using Slipstream.App.Tests.Fakes;
using Slipstream.Core.Files;
using Slipstream.Meridian.Controls;
using Xunit;

namespace Slipstream.App.Tests;

/// <summary>
/// Tests the plain C# <see cref="BrowseViewModel"/> directly — no UI thread needed (see
/// DeviceViewModelTests' remarks on headless WinUI controls).
/// </summary>
public class BrowseViewModelTests
{
    private static FileEntry Dir(string name) =>
        new(name, $"/storage/{name}", 0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), true, null, null);

    private static FileEntry File(string name, string mime, long size = 100) =>
        new(name, $"/storage/{name}", size, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), false, mime, null);

    private static FakePeerHost FakeHostReturning(IReadOnlyList<FileEntry> entries, bool truncated = false)
    {
        var host = new FakePeerHost();
        host.ListResultFactory = path => new ListResult(path, entries, truncated);
        return host;
    }

    private static FakePeerHost FakeHostReturning(int entries, bool truncated)
    {
        var list = Enumerable.Range(0, entries)
            .Select(i => File($"file{i:D5}.txt", "text/plain"))
            .ToList();
        return FakeHostReturning(list, truncated);
    }

    [Fact]
    public async Task Directories_sort_before_files_then_alphabetically_within_each_group()
    {
        var host = FakeHostReturning(
        [
            File("banana.txt", "text/plain"),
            Dir("zzz-folder"),
            File("apple.txt", "text/plain"),
            Dir("aaa-folder"),
        ]);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");

        Assert.Equal(
            ["aaa-folder", "zzz-folder", "apple.txt", "banana.txt"],
            vm.Entries.Select(e => e.Name).ToArray());
    }

    [Fact]
    public async Task Video_chip_filters_to_video_mime_entries_only()
    {
        var host = FakeHostReturning(
        [
            File("clip.mp4", "video/mp4"),
            File("song.mp3", "audio/mpeg"),
            File("photo.jpg", "image/jpeg"),
            File("notes.pdf", "application/pdf"),
        ]);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");

        vm.SelectFilter(BrowseFilter.Video);

        Assert.Equal(["clip.mp4"], vm.Entries.Select(e => e.Name).ToArray());
    }

    [Fact]
    public async Task Audio_chip_filters_to_audio_mime_entries_only()
    {
        var host = FakeHostReturning(
        [
            File("clip.mp4", "video/mp4"),
            File("song.mp3", "audio/mpeg"),
        ]);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");

        vm.SelectFilter(BrowseFilter.Audio);

        Assert.Equal(["song.mp3"], vm.Entries.Select(e => e.Name).ToArray());
    }

    [Fact]
    public async Task Images_chip_filters_to_image_mime_entries_only()
    {
        var host = FakeHostReturning(
        [
            File("photo.jpg", "image/jpeg"),
            File("notes.pdf", "application/pdf"),
        ]);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");

        vm.SelectFilter(BrowseFilter.Images);

        Assert.Equal(["photo.jpg"], vm.Entries.Select(e => e.Name).ToArray());
    }

    [Fact]
    public async Task Docs_chip_filters_to_document_mime_entries_only()
    {
        // Docs bucket: application/* (pdf, word, excel, ppt, zip, octet-stream, etc.) and
        // text/* — everything that isn't video/audio/image, which is the practical
        // complement a "documents" bucket needs on a phone's shared storage.
        var host = FakeHostReturning(
        [
            File("notes.pdf", "application/pdf"),
            File("readme.txt", "text/plain"),
            File("clip.mp4", "video/mp4"),
        ]);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");

        vm.SelectFilter(BrowseFilter.Docs);

        Assert.Equal(["notes.pdf", "readme.txt"], vm.Entries.Select(e => e.Name).ToArray());
    }

    [Fact]
    public async Task All_chip_shows_directories_and_every_file_type()
    {
        var host = FakeHostReturning(
        [
            Dir("folder"),
            File("clip.mp4", "video/mp4"),
            File("notes.pdf", "application/pdf"),
        ]);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");
        vm.SelectFilter(BrowseFilter.Video);
        vm.SelectFilter(BrowseFilter.All);

        Assert.Equal(3, vm.Entries.Count);
    }

    [Fact]
    public async Task Navigating_into_a_folder_pushes_a_breadcrumb_and_back_pops_it()
    {
        var host = new FakePeerHost();
        host.ListResultFactory = path => new ListResult(path, [Dir("sub")], false);

        var vm = new BrowseViewModel(host);
        await vm.LoadAsync("/storage");
        Assert.Equal(["storage"], vm.Breadcrumbs);

        await vm.NavigateIntoAsync(vm.Entries.Single());
        Assert.Equal(["storage", "sub"], vm.Breadcrumbs);

        await vm.NavigateBackAsync();
        Assert.Equal(["storage"], vm.Breadcrumbs);
    }

    [Fact]
    public async Task A_truncated_listing_says_so_rather_than_pretending_it_is_complete()
    {
        var vm = new BrowseViewModel(FakeHostReturning(entries: 5000, truncated: true));
        await vm.LoadAsync("/storage");

        Assert.Equal("Showing the first 5,000 items in this folder.", vm.TruncationNotice);
    }

    [Fact]
    public async Task A_non_truncated_listing_has_no_truncation_notice()
    {
        var vm = new BrowseViewModel(FakeHostReturning([File("a.txt", "text/plain")]));
        await vm.LoadAsync("/storage");

        Assert.Null(vm.TruncationNotice);
    }

    [Fact]
    public async Task State_moves_from_loading_to_content_when_entries_come_back()
    {
        var host = FakeHostReturning([File("a.txt", "text/plain")]);
        var vm = new BrowseViewModel(host);

        Assert.Equal(MeridianStateViewState.Loading, vm.State);

        await vm.LoadAsync("/storage");

        Assert.Equal(MeridianStateViewState.Content, vm.State);
    }

    [Fact]
    public async Task State_is_empty_when_the_folder_has_no_entries()
    {
        var vm = new BrowseViewModel(FakeHostReturning([]));

        await vm.LoadAsync("/storage");

        Assert.Equal(MeridianStateViewState.Empty, vm.State);
    }

    [Fact]
    public async Task A_failed_listing_surfaces_a_direct_error_message_instead_of_throwing()
    {
        var host = new FakePeerHost { ListFailure = new InvalidOperationException("boom") };
        var vm = new BrowseViewModel(host);

        await vm.LoadAsync("/storage");

        Assert.Equal(MeridianStateViewState.Error, vm.State);
        Assert.Equal("Couldn't load this folder. Try again.", vm.ErrorMessage);
    }
}
