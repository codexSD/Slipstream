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

    public BrowsePage(IPeerHost peerHost)
    {
        ViewModel = new Slipstream.App.Pages.BrowseViewModel(peerHost);
        InitializeComponent();

        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Name", Binding = "Name" });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Size", Binding = "Size", IsTabular = true, Width = new GridLength(100) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Modified", Binding = "Modified", Width = new GridLength(180) });

        Loaded += async (_, _) => await ViewModel.LoadAsync(RootPath);
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
            await ViewModel.NavigateIntoAsync(entry);
    }

    private async void GalleryView_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (GalleryView.SelectedItem is FileEntry entry)
            await ViewModel.NavigateIntoAsync(entry);
    }
}
