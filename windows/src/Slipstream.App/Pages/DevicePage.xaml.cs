using Microsoft.UI.Xaml.Controls;
using Slipstream.App.Pages;
using Slipstream.App.Services;

namespace Slipstream_App.Pages;

/// <summary>
/// Spec §12's Device page: a 3-up stat row, the screen's one hero metric (the live transfer
/// rate), and a connection panel naming the winning discovery strategy and elapsed time.
/// </summary>
public sealed partial class DevicePage : Page
{
    public DeviceViewModel ViewModel { get; }

    public DevicePage(IPeerHost peerHost, TransferQueue? transferQueue = null)
    {
        ViewModel = new DeviceViewModel(peerHost, transferQueue);
        InitializeComponent();
    }
}
