using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Slipstream.App.Services;
using Slipstream.Meridian.Controls;

namespace Slipstream_App.Pages;

/// <summary>
/// Spec §12's Transfers page: a single MeridianDataGrid of every transfer this session has
/// queued, with live progress/rate/ETA/status columns fed by <see cref="TransferQueue"/>.
/// </summary>
public sealed partial class TransfersPage : Page
{
    public Slipstream.App.Pages.TransfersViewModel ViewModel { get; }

    public TransfersPage(TransferQueue queue)
    {
        ViewModel = new Slipstream.App.Pages.TransfersViewModel(queue);
        InitializeComponent();

        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Name", Binding = "Name" });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Size", Binding = "SizeText", IsTabular = true, Width = new GridLength(160) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Rate", Binding = "RateText", IsTabular = true, Width = new GridLength(120) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "ETA", Binding = "EtaText", IsTabular = true, Width = new GridLength(120) });
        DataGrid.Columns.Add(new MeridianDataGridColumn { Header = "Status", Binding = "StatusText", Width = new GridLength(100) });
    }
}
