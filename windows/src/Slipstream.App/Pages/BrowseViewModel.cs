using CommunityToolkit.Mvvm.ComponentModel;
using Slipstream.App.Services;
using Slipstream.Core.Files;
using Slipstream.Meridian.Controls;

namespace Slipstream.App.Pages;

/// <summary>The four filter chips spec §12 shows above the Browse data table.</summary>
public enum BrowseFilter
{
    All,
    Video,
    Audio,
    Images,
    Docs,
}

/// <summary>
/// Drives the Browse-phone page: a directories-first/alphabetical listing of one remote
/// folder, filterable by MIME-prefix chip, with breadcrumb navigation and an honest notice
/// when the peer truncated the listing (spec §6's 5000-entry cap).
/// </summary>
/// <remarks>
/// The Docs chip has no single canonical MIME prefix the way Video/Audio/Images do, so it
/// is defined as the practical complement: anything under "application/" (pdf, Word, Excel,
/// PowerPoint, zip, octet-stream, ...) or "text/" (plain text, markdown, ...). That covers
/// every non-media file a phone's shared storage is likely to hold without requiring an
/// exhaustive allowlist of document extensions.
/// </remarks>
public sealed partial class BrowseViewModel : ObservableObject
{
    private readonly IPeerHost _peerHost;

    /// <summary>Breadcrumb stack of path segments, root first. Rebuilt from the full path
    /// each navigation rather than tracked as raw remote paths, since that is what the UI
    /// renders and what NavigateBackAsync needs to rebuild a parent path.</summary>
    private readonly List<string> _pathSegments = [];

    private IReadOnlyList<FileEntry> _allEntries = [];
    private IReadOnlyList<FileEntry> _entries = [];
    private BrowseFilter _selectedFilter = BrowseFilter.All;
    private string _searchText = string.Empty;
    private IReadOnlyList<string> _breadcrumbs = [];
    private string? _truncationNotice;
    private MeridianStateViewState _state = MeridianStateViewState.Loading;
    private string? _errorMessage;

    public BrowseViewModel(IPeerHost peerHost)
    {
        ArgumentNullException.ThrowIfNull(peerHost);
        _peerHost = peerHost;
    }

    /// <summary>The current folder's entries after directories-first/alphabetical sort and
    /// the active filter chip.</summary>
    public IReadOnlyList<FileEntry> Entries
    {
        get => _entries;
        private set => SetProperty(ref _entries, value);
    }

    public BrowseFilter SelectedFilter
    {
        get => _selectedFilter;
        private set => SetProperty(ref _selectedFilter, value);
    }

    /// <summary>Path segments from root to the current folder, e.g. ["storage", "DCIM"].</summary>
    public IReadOnlyList<string> Breadcrumbs
    {
        get => _breadcrumbs;
        private set => SetProperty(ref _breadcrumbs, value);
    }

    /// <summary>Non-null (and user-facing) only when the peer reported the listing was
    /// capped — never fabricated when the peer says the listing is complete.</summary>
    public string? TruncationNotice
    {
        get => _truncationNotice;
        private set
        {
            if (SetProperty(ref _truncationNotice, value))
                OnPropertyChanged(nameof(IsTruncated));
        }
    }

    /// <summary>Mirrors <see cref="TruncationNotice"/> as a plain bool, since x:Bind's
    /// InfoBar.IsOpen binding needs a bool rather than a string-emptiness check.</summary>
    public bool IsTruncated => _truncationNotice is not null;

    public MeridianStateViewState State
    {
        get => _state;
        private set => SetProperty(ref _state, value);
    }

    /// <summary>§15-voice: direct, no apology, names the next step. Set only when
    /// <see cref="State"/> is Error.</summary>
    public string? ErrorMessage
    {
        get => _errorMessage;
        private set => SetProperty(ref _errorMessage, value);
    }

    /// <summary>Loads the given remote folder as the new current folder, replacing the
    /// breadcrumb stack entirely (used for the initial load and any direct navigation).</summary>
    public async Task LoadAsync(string path)
    {
        _pathSegments.Clear();
        _pathSegments.AddRange(SplitSegments(path));
        Breadcrumbs = _pathSegments.ToList();

        await FetchAsync(path);
    }

    /// <summary>Navigates into a child folder, pushing one breadcrumb segment.</summary>
    public async Task NavigateIntoAsync(FileEntry entry)
    {
        if (!entry.IsDirectory) return;

        _pathSegments.Add(entry.Name);
        Breadcrumbs = _pathSegments.ToList();

        await FetchAsync(entry.Path);
    }

    /// <summary>Pops one breadcrumb segment and reloads the parent folder. No-op at the root.</summary>
    public async Task NavigateBackAsync()
    {
        if (_pathSegments.Count <= 1) return;

        _pathSegments.RemoveAt(_pathSegments.Count - 1);
        Breadcrumbs = _pathSegments.ToList();

        await FetchAsync(JoinSegments(_pathSegments));
    }

    public void SelectFilter(BrowseFilter filter)
    {
        SelectedFilter = filter;
        Refresh();
    }

    /// <summary>Narrows the current folder's listing by name, client-side — the folder is
    /// already fully (or truncated-but-fully) loaded, so this needs no server round trip.</summary>
    public void SetSearchText(string text)
    {
        _searchText = text ?? string.Empty;
        Refresh();
    }

    private void Refresh()
    {
        var filtered = ApplyFilterAndSort(_allEntries, SelectedFilter);
        Entries = string.IsNullOrWhiteSpace(_searchText)
            ? filtered
            : filtered.Where(e => e.Name.Contains(_searchText, StringComparison.OrdinalIgnoreCase)).ToList();
    }

    private async Task FetchAsync(string path)
    {
        State = MeridianStateViewState.Loading;
        ErrorMessage = null;

        ListResult result;
        try
        {
            result = await _peerHost.ListAsync(path, CancellationToken.None);
        }
        catch (Exception)
        {
            _allEntries = [];
            Entries = [];
            TruncationNotice = null;
            ErrorMessage = "Couldn't load this folder. Try again.";
            State = MeridianStateViewState.Error;
            return;
        }

        _allEntries = result.Entries;
        Refresh();
        TruncationNotice = result.Truncated
            ? $"Showing the first {FileBrowser.MaxEntries:N0} items in this folder."
            : null;

        State = Entries.Count == 0 ? MeridianStateViewState.Empty : MeridianStateViewState.Content;
    }

    private static IReadOnlyList<FileEntry> ApplyFilterAndSort(IReadOnlyList<FileEntry> entries, BrowseFilter filter)
    {
        var filtered = filter == BrowseFilter.All
            ? entries
            : entries.Where(e => e.IsDirectory || Matches(e.Mime, filter)).ToList();

        return filtered
            .OrderBy(e => e.IsDirectory ? 0 : 1)
            .ThenBy(e => e.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static bool Matches(string? mime, BrowseFilter filter)
    {
        if (mime is null) return false;

        return filter switch
        {
            BrowseFilter.Video => mime.StartsWith("video/", StringComparison.OrdinalIgnoreCase),
            BrowseFilter.Audio => mime.StartsWith("audio/", StringComparison.OrdinalIgnoreCase),
            BrowseFilter.Images => mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase),
            BrowseFilter.Docs => mime.StartsWith("application/", StringComparison.OrdinalIgnoreCase)
                || mime.StartsWith("text/", StringComparison.OrdinalIgnoreCase),
            _ => true,
        };
    }

    private static IEnumerable<string> SplitSegments(string path) =>
        path.Split(['/', '\\'], StringSplitOptions.RemoveEmptyEntries);

    private static string JoinSegments(IReadOnlyList<string> segments) =>
        "/" + string.Join('/', segments);
}
