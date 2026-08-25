using System.Collections.ObjectModel;
using System.Reflection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Slipstream.Meridian.Controls;

/// <summary>
/// The admin-table workhorse: muted uppercase-free headers, hairline row separators, no
/// vertical gridlines, generous rows, Brand-tint selection with a one-step-lighter hover.
///
/// Architecture: built on <see cref="ListView"/> rather than a from-scratch grid engine.
/// A WinUI ListView has no native multi-column header, so the default template (Generic.xaml)
/// composes a header row — an ItemsControl bound to <see cref="Columns"/> — above the ListView.
/// Row cells are NOT authored as a single static DataTemplate, because the number/shape of
/// columns is only known at runtime from the Columns collection. Instead this control handles
/// ListView's ContainerContentChanging event and builds each row's content as a horizontal
/// StackPanel of TextBlocks in code, one per column, resolving each cell's value via reflection
/// against MeridianDataGridColumn.Binding (a simple property-path string, e.g. "Name" or
/// "Owner.DisplayName"). This keeps column definition entirely data-driven — callers (Browse,
/// Transfers, History) build a Columns collection with no per-screen XAML/DataTemplate
/// authoring required — while staying well inside "control, not a grid engine" scope for this
/// task. ContainerContentChanging participates in container recycling, so this scales to large
/// row counts the same way a normal ListView does.
/// </summary>
public sealed class MeridianDataGrid : Control
{
    private ListView? _listView;

    public MeridianDataGrid()
    {
        DefaultStyleKey = typeof(MeridianDataGrid);
        Columns = new ObservableCollection<MeridianDataGridColumn>();
    }

    public static readonly DependencyProperty ColumnsProperty = DependencyProperty.Register(
        nameof(Columns), typeof(IList<MeridianDataGridColumn>), typeof(MeridianDataGrid),
        new PropertyMetadata(null));

    /// <summary>The column set. Order determines left-to-right layout in both header and rows.</summary>
    public IList<MeridianDataGridColumn> Columns
    {
        get => (IList<MeridianDataGridColumn>)GetValue(ColumnsProperty);
        set => SetValue(ColumnsProperty, value);
    }

    public static readonly DependencyProperty ItemsSourceProperty = DependencyProperty.Register(
        nameof(ItemsSource), typeof(object), typeof(MeridianDataGrid),
        new PropertyMetadata(null));

    /// <summary>Row data. Forwarded to the inner ListView's ItemsSource.</summary>
    public object? ItemsSource
    {
        get => GetValue(ItemsSourceProperty);
        set => SetValue(ItemsSourceProperty, value);
    }

    public static readonly DependencyProperty SelectedItemProperty = DependencyProperty.Register(
        nameof(SelectedItem), typeof(object), typeof(MeridianDataGrid), new PropertyMetadata(null));

    public object? SelectedItem
    {
        get => GetValue(SelectedItemProperty);
        set => SetValue(SelectedItemProperty, value);
    }

    protected override void OnApplyTemplate()
    {
        base.OnApplyTemplate();

        if (_listView is not null)
        {
            _listView.ContainerContentChanging -= OnContainerContentChanging;
            _listView.SelectionChanged -= OnSelectionChanged;
        }

        _listView = GetTemplateChild("PART_ListView") as ListView;
        if (_listView is not null)
        {
            _listView.ContainerContentChanging += OnContainerContentChanging;
            _listView.SelectionChanged += OnSelectionChanged;
        }
    }

    private void OnSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        SelectedItem = _listView?.SelectedItem;
    }

    private void OnContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.ItemContainer is not ListViewItem item) return;
        item.Content = BuildRowContent(args.Item);
    }

    private FrameworkElement BuildRowContent(object rowItem)
    {
        var panel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 0 };

        foreach (var column in Columns)
        {
            var value = ResolveValue(rowItem, column.Binding);
            var text = new TextBlock
            {
                Text = value?.ToString() ?? string.Empty,
                Width = ColumnWidthToPixels(column.Width),
                Padding = new Thickness(12, 0, 12, 0),
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
                TextAlignment = column.IsTabular
                    ? TextAlignment.Right
                    : column.Alignment switch
                    {
                        MeridianDataGridColumnAlignment.Right => TextAlignment.Right,
                        MeridianDataGridColumnAlignment.Center => TextAlignment.Center,
                        _ => TextAlignment.Left,
                    },
            };

            panel.Children.Add(text);
        }

        return panel;
    }

    /// <summary>
    /// Star/Auto columns don't have a natural pixel width outside a Grid, and the header and
    /// row panels are plain StackPanels (see class remarks) rather than Grids sharing one
    /// ColumnDefinitions collection. A fixed fallback keeps header and row cells the same width
    /// for a given column without requiring pixel widths from every caller; callers that need
    /// precise alignment can set an absolute (pixel) GridLength per column.
    /// </summary>
    internal static double ColumnWidthToPixels(GridLength width) =>
        width.IsAbsolute ? width.Value : 140;

    private static object? ResolveValue(object? item, string path)
    {
        if (item is null || string.IsNullOrEmpty(path)) return null;

        object? current = item;
        foreach (var segment in path.Split('.'))
        {
            if (current is null) return null;
            var prop = current.GetType().GetProperty(segment, BindingFlags.Public | BindingFlags.Instance);
            current = prop?.GetValue(current);
        }

        return current;
    }
}
