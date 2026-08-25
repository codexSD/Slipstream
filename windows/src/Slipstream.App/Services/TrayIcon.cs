using System.Runtime.InteropServices;

namespace Slipstream.App.Services;

/// <summary>
/// The system tray icon that lets Slipstream keep running after the main window is hidden
/// (per plan §14: "Closing the main window hides to tray rather than exiting. The tray menu
/// offers Show, Pause discovery, and Quit").
/// </summary>
/// <remarks>
/// WinUI 3 / the Windows App SDK has no first-class tray-icon API. Two real options exist: the
/// community package <c>H.NotifyIcon.WinUI</c>, or raw Win32 <c>Shell_NotifyIcon</c> via
/// P/Invoke. This uses the raw Win32 route deliberately — it needs no new NuGet dependency
/// (the WinUI/WinRT interop surface this project already links against is enough), it is a
/// handful of well-documented calls, and a tray icon with a three-item popup menu (Show,
/// Pause discovery, Quit) has no need for the richer XAML-hosted-in-tray-flyout features
/// <c>H.NotifyIcon</c> exists for. Should the tray surface grow real WinUI content later,
/// revisit that package then.
/// </remarks>
public sealed class TrayIcon : IDisposable
{
    private const uint WM_TRAYICON = 0x8001; // WM_APP + 1
    private const uint WM_DESTROY = 0x0002;
    private const uint WM_COMMAND = 0x0111;
    private const uint WM_RBUTTONUP = 0x0205;
    private const uint WM_LBUTTONUP = 0x0202;
    private const uint WM_LBUTTONDBLCLK = 0x0203;

    private const uint NIM_ADD = 0x00000000;
    private const uint NIM_MODIFY = 0x00000001;
    private const uint NIM_DELETE = 0x00000002;
    private const uint NIF_MESSAGE = 0x00000001;
    private const uint NIF_ICON = 0x00000002;
    private const uint NIF_TIP = 0x00000004;

    private const int IdShow = 1;
    private const int IdPause = 2;
    private const int IdQuit = 3;

    private readonly WndProcDelegate _wndProc;
    private readonly nint _hwnd;
    private readonly nint _hIcon;
    private NOTIFYICONDATA _iconData;
    private bool _disposed;

    /// <summary>Raised when the user picks "Show" from the tray menu or double-clicks the
    /// icon. The owner restores/focuses <c>ShellWindow</c>.</summary>
    public event Action? ShowRequested;

    /// <summary>Raised when the user toggles "Pause discovery". <paramref name="paused"/> is
    /// the new desired state (checked/unchecked), so the owner can drive the real
    /// <see cref="IPeerHost.PauseDiscovery"/>/<see cref="IPeerHost.ResumeDiscovery"/> pair.</summary>
    public event Action<bool>? PauseDiscoveryToggled;

    /// <summary>Raised when the user picks "Quit". The owner must actually terminate the
    /// process here — this is the one path that bypasses hide-to-tray.</summary>
    public event Action? QuitRequested;

    private bool _discoveryPaused;

    public TrayIcon(string iconPath, string tooltip)
    {
        _wndProc = WndProc;
        _hwnd = CreateMessageWindow();

        _hIcon = LoadIconFromFile(iconPath);

        _iconData = new NOTIFYICONDATA
        {
            cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
            hWnd = _hwnd,
            uID = 1,
            uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP,
            uCallbackMessage = WM_TRAYICON,
            hIcon = _hIcon,
            szTip = tooltip,
        };

        Shell_NotifyIcon(NIM_ADD, ref _iconData);
    }

    private nint CreateMessageWindow()
    {
        var className = "SlipstreamTrayWindow_" + Guid.NewGuid().ToString("N");
        var wndClass = new WNDCLASS
        {
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(_wndProc),
            lpszClassName = className,
            hInstance = GetModuleHandle(null),
        };
        RegisterClass(ref wndClass);

        // HWND_MESSAGE (-3): a message-only window needs no visible surface — it exists
        // purely to receive Shell_NotifyIcon callbacks and WM_COMMAND from the popup menu.
        return CreateWindowEx(0, className, "Slipstream Tray", 0, 0, 0, 0, 0,
            new nint(-3), nint.Zero, GetModuleHandle(null), nint.Zero);
    }

    private nint WndProc(nint hWnd, uint msg, nint wParam, nint lParam)
    {
        switch (msg)
        {
            case WM_TRAYICON:
                var lp = (uint)lParam.ToInt64();
                if (lp is WM_RBUTTONUP or WM_LBUTTONUP)
                {
                    ShowPopupMenu();
                }
                else if (lp == WM_LBUTTONDBLCLK)
                {
                    ShowRequested?.Invoke();
                }
                return 0;

            case WM_COMMAND:
                var id = (int)(wParam.ToInt64() & 0xFFFF);
                switch (id)
                {
                    case IdShow:
                        ShowRequested?.Invoke();
                        break;
                    case IdPause:
                        _discoveryPaused = !_discoveryPaused;
                        PauseDiscoveryToggled?.Invoke(_discoveryPaused);
                        break;
                    case IdQuit:
                        QuitRequested?.Invoke();
                        break;
                }
                return 0;

            case WM_DESTROY:
                return 0;

            default:
                return DefWindowProc(hWnd, msg, wParam, lParam);
        }
    }

    private void ShowPopupMenu()
    {
        var hMenu = CreatePopupMenu();
        AppendMenu(hMenu, 0, IdShow, "Show");
        AppendMenu(hMenu, _discoveryPaused ? 0x00000008u /* MF_CHECKED */ : 0, IdPause, "Pause discovery");
        AppendMenu(hMenu, 0x00000800u /* MF_SEPARATOR */, 0, string.Empty);
        AppendMenu(hMenu, 0, IdQuit, "Quit");

        GetCursorPos(out var pt);

        // A popup menu must be owned by a foreground window or it won't dismiss correctly
        // on click-away; the message-only window is set as foreground just for this call.
        SetForegroundWindow(_hwnd);
        TrackPopupMenu(hMenu, 0x0000, pt.X, pt.Y, 0, _hwnd, nint.Zero);
        PostMessage(_hwnd, 0, nint.Zero, nint.Zero);

        DestroyMenu(hMenu);
    }

    private static nint LoadIconFromFile(string path) =>
        File.Exists(path)
            ? LoadImage(nint.Zero, path, 1 /* IMAGE_ICON */, 0, 0, 0x00000010u /* LR_LOADFROMFILE */)
            : LoadIcon(nint.Zero, new nint(32512) /* IDI_APPLICATION */);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        Shell_NotifyIcon(NIM_DELETE, ref _iconData);
        if (_hIcon != nint.Zero) DestroyIcon(_hIcon);
        if (_hwnd != nint.Zero) DestroyWindow(_hwnd);
    }

    // --- P/Invoke surface -------------------------------------------------------------

    private delegate nint WndProcDelegate(nint hWnd, uint msg, nint wParam, nint lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct WNDCLASS
    {
        public uint style;
        public nint lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public nint hInstance;
        public nint hIcon;
        public nint hCursor;
        public nint hbrBackground;
        [MarshalAs(UnmanagedType.LPWStr)] public string? lpszMenuName;
        [MarshalAs(UnmanagedType.LPWStr)] public string lpszClassName;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize;
        public nint hWnd;
        public int uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public nint hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern bool Shell_NotifyIcon(uint dwMessage, ref NOTIFYICONDATA lpData);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClass(ref WNDCLASS lpWndClass);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint CreateWindowEx(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight,
        nint hWndParent, nint hMenu, nint hInstance, nint lpParam);

    [DllImport("user32.dll")]
    private static extern nint DefWindowProc(nint hWnd, uint msg, nint wParam, nint lParam);

    [DllImport("user32.dll")]
    private static extern bool DestroyWindow(nint hWnd);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern nint GetModuleHandle(string? lpModuleName);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern nint LoadImage(nint hInst, string name, uint type, int cx, int cy, uint fuLoad);

    [DllImport("user32.dll")]
    private static extern nint LoadIcon(nint hInstance, nint lpIconName);

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(nint hIcon);

    [DllImport("user32.dll")]
    private static extern nint CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool AppendMenu(nint hMenu, uint uFlags, int uIDNewItem, string lpNewItem);

    [DllImport("user32.dll")]
    private static extern bool DestroyMenu(nint hMenu);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT lpPoint);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(nint hWnd);

    [DllImport("user32.dll")]
    private static extern bool TrackPopupMenu(nint hMenu, uint uFlags, int x, int y, int nReserved, nint hWnd, nint prcRect);

    [DllImport("user32.dll")]
    private static extern bool PostMessage(nint hWnd, uint msg, nint wParam, nint lParam);
}
