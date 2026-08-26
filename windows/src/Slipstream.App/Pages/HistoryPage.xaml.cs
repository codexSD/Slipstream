using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Slipstream.App.Services;
using Slipstream.Meridian.Controls;

namespace Slipstream_App.Pages;

/// <summary>
/// The History page: a MeridianDataGrid of every persisted transfer (Task 13's
/// HistoryStore), newest first, with "Reveal in folder" (disabled once the local file is gone)
/// and "Run again" (re-enqueues via the shared TransferQueue) acting on the selected row.
/// </summary>
public sealed partial class HistoryPage : UserControl
{
    public Slipstream.App.Pages.HistoryViewModel ViewModel { get; }

    public HistoryPage(HistoryStore store, TransferQueue queue)
    {
        ViewModel = new Slipstream.App.Pages.HistoryViewModel(store, queue);
        InitializeComponent();

        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Name", Binding = "Name" });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Size", Binding = "SizeText", IsTabular = true, Width = new GridLength(120) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Status", Binding = "StatusText", Width = new GridLength(100) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Completed", Binding = "CompletedText", Width = new GridLength(180) });
    }

    private void RevealButton_Click(object sender, RoutedEventArgs e)
    {
        if (DataGrid.SelectedItem is not Slipstream.App.Pages.HistoryRow row) return;

        // Re-check right before acting — CanReveal was computed at load time and the file
        // may have been moved or deleted since (see HistoryRow.RefreshCanReveal).
        row.RefreshCanReveal();
        if (!row.CanReveal) return;

        // Standard way to select a file in Explorer rather than merely opening its folder.
        System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{row.LocalPath}\"");
    }

    private void RunAgainButton_Click(object sender, RoutedEventArgs e)
    {
        if (DataGrid.SelectedItem is not Slipstream.App.Pages.HistoryRow row) return;

        ViewModel.RunAgainCommand.Execute(row);
    }
}
