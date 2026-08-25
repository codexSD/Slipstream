using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Slipstream.App.Pages;
using Slipstream.App.Services;
using Slipstream.Core.Files;
using Slipstream.Meridian.Controls;

namespace Slipstream_App.Pages;

/// <summary>
/// Spec §12's Browse-phone page: breadcrumbs + search toolbar, the four MIME filter chips,
/// a directories-first data table (with a gallery-view toggle for media-heavy folders), and
/// the honest truncation banner when the peer capped the listing.
/// </summary>
public sealed partial class BrowsePage : Page, INotifyPropertyChanged
{
    private const string RootPath = "/storage";

    private readonly IPeerHost _peerHost;
    private readonly TransferQueue? _transferQueue;

    public Slipstream.App.Pages.BrowseViewModel ViewModel { get; }

    private bool _isGalleryView;

    public event PropertyChangedEventHandler? PropertyChanged;

    public bool IsGalleryView
    {
        get => _isGalleryView;
        private set
        {
            if (_isGalleryView == value) return;
            _isGalleryView = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsGalleryView)));
        }
    }

    // ShellWindow builds exactly one BrowsePage instance for the whole session (see the
    // ContentPresenter comment in ShellWindow.xaml), so a static "current instance" is safe
    // here and is what lets the gallery's x:Bind — which runs inside a DataTemplate scoped
    // to FileEntry, not BrowsePage — reach IPeerHost.GetThumbnailUrl via a static function.
    private static BrowsePage? _current;

    public BrowsePage(IPeerHost peerHost, TransferQueue? transferQueue = null)
    {
        _peerHost = peerHost;
        _transferQueue = transferQueue;
        _current = this;
        ViewModel = new Slipstream.App.Pages.BrowseViewModel(peerHost);
        InitializeComponent();

        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Name", Binding = "Name" });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Size", Binding = "Size", IsTabular = true, Width = new GridLength(100) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Modified", Binding = "Modified", Width = new GridLength(180) });

        Loaded += async (_, _) => await ViewModel.LoadAsync(RootPath);
    }

    /// <summary>Resolves a gallery tile's <see cref="FileEntry.ThumbnailToken"/> to a loadable
    /// image, or null (leaving the plain surface tile visible) when there is no token or no
    /// connected peer to fetch it from.</summary>
    public static Microsoft.UI.Xaml.Media.ImageSource? ThumbnailSource(string? thumbnailToken)
    {
        if (string.IsNullOrEmpty(thumbnailToken)) return null;

        var url = _current?._peerHost.GetThumbnailUrl(thumbnailToken);
        if (url is null) return null;

        return new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(new Uri(url));
    }

    private async void Back_Click(object sender, RoutedEventArgs e) => await ViewModel.NavigateBackAsync();

    private void FilterChip_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleButton clicked) return;

        foreach (var chip in new[] { AllChip, VideoChip, AudioChip, ImagesChip, DocsChip })
            chip.IsChecked = ReferenceEquals(chip, clicked);

        var filter = clicked.Name switch
        {
            nameof(VideoChip) => BrowseFilter.Video,
            nameof(AudioChip) => BrowseFilter.Audio,
            nameof(ImagesChip) => BrowseFilter.Images,
            nameof(DocsChip) => BrowseFilter.Docs,
            _ => BrowseFilter.All,
        };

        ViewModel.SelectFilter(filter);
    }

    private void GalleryToggle_Click(object sender, RoutedEventArgs e) =>
        IsGalleryView = GalleryToggle.IsChecked == true;

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) =>
        ViewModel.SetSearchText(SearchBox.Text);

    private async void DataGrid_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (DataGrid.SelectedItem is FileEntry entry)
            await ActivateEntryAsync(entry);
    }

    private async void GalleryView_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (GalleryView.SelectedItem is FileEntry entry)
            await ActivateEntryAsync(entry);
    }

    /// <summary>Double-tapping a folder navigates into it, same as before. Double-tapping a
    /// file is the reachable entry point into a real transfer (finding #4): it queues a pull
    /// via the shared <see cref="TransferQueue"/> rather than doing nothing.</summary>
    private async Task ActivateEntryAsync(FileEntry entry)
    {
        if (entry.IsDirectory)
        {
            await ViewModel.NavigateIntoAsync(entry);
            return;
        }

        _transferQueue?.Enqueue(entry.Path, entry.Size);
    }

    private void Download_Click(object sender, RoutedEventArgs e)
    {
        var entry = (IsGalleryView ? GalleryView.SelectedItem : DataGrid.SelectedItem) as FileEntry;
        if (entry is null || entry.IsDirectory) return;

        _transferQueue?.Enqueue(entry.Path, entry.Size);
    }

    private async void Stream_Click(object sender, RoutedEventArgs e)
    {
        var entry = (IsGalleryView ? GalleryView.SelectedItem : DataGrid.SelectedItem) as FileEntry;
        if (entry is null || entry.IsDirectory) return;

        try
        {
            await _peerHost.StreamAsync(entry.Path, CancellationToken.None);
        }
        catch (Exception)
        {
            // Nothing further to surface here beyond what StreamAsync's own caller-visible
            // state already reflects; a richer error surface for this button is future work.
        }
    }
}
