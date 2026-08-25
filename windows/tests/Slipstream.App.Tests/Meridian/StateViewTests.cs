using System.Reflection;
using System.Windows.Input;
using System.Xml.Linq;
using Microsoft.UI.Xaml;
using Slipstream.Meridian.Controls;

namespace Slipstream.App.Tests.Meridian;

public class StateViewTests
{
    private static XDocument LoadGeneric() =>
        XDocument.Load(TestPaths.Meridian("Themes/Generic.xaml"));

    // Deliberately does NOT call FieldInfo.GetValue: touching a static DependencyProperty
    // field's value forces MeridianStateView's static constructor to run, which calls into
    // DependencyProperty.Register/PropertyMetadata — a WinRT activation that requires a live
    // packaged app host and throws COMException (Class not registered) under a bare test
    // runner. Field *existence* and its declared type are enough to assert the API shape
    // without paying that cost, matching ControlDefaultsTests' XAML-only approach.
    private static void AssertStaticDpFieldExists(string name)
    {
        var field = typeof(MeridianStateView).GetField(name, BindingFlags.Public | BindingFlags.Static);
        Assert.True(field is not null, $"MeridianStateView is missing the static DependencyProperty field {name}");
        Assert.Equal(typeof(DependencyProperty), field!.FieldType);
    }

    [Fact]
    public void MeridianStateViewState_declares_all_four_states()
    {
        var names = Enum.GetNames(typeof(MeridianStateViewState));
        Assert.Equal(new[] { "Loading", "Content", "Empty", "Error" }, names);
    }

    [Fact]
    public void State_dependency_property_is_typed_as_MeridianStateViewState()
    {
        AssertStaticDpFieldExists(nameof(MeridianStateView.StateProperty));

        var stateProp = typeof(MeridianStateView).GetProperty(nameof(MeridianStateView.State));
        Assert.NotNull(stateProp);
        Assert.Equal(typeof(MeridianStateViewState), stateProp!.PropertyType);
    }

    [Fact]
    public void Empty_and_error_states_expose_message_and_action_dps()
    {
        AssertStaticDpFieldExists(nameof(MeridianStateView.MessageProperty));
        AssertStaticDpFieldExists(nameof(MeridianStateView.ActionLabelProperty));
        AssertStaticDpFieldExists(nameof(MeridianStateView.ActionCommandProperty));

        var messageProp = typeof(MeridianStateView).GetProperty(nameof(MeridianStateView.Message));
        var actionLabelProp = typeof(MeridianStateView).GetProperty(nameof(MeridianStateView.ActionLabel));
        var actionCommandProp = typeof(MeridianStateView).GetProperty(nameof(MeridianStateView.ActionCommand));

        Assert.NotNull(messageProp);
        Assert.NotNull(actionLabelProp);
        Assert.NotNull(actionCommandProp);
        Assert.Equal(typeof(string), messageProp!.PropertyType);
        Assert.Equal(typeof(string), actionLabelProp!.PropertyType);
        Assert.Equal(typeof(ICommand), actionCommandProp!.PropertyType);
    }

    [Fact]
    public void Default_style_references_MeridianCriticalBrush_for_the_error_state()
    {
        var doc = LoadGeneric();

        var stateViewStyle = doc.Descendants()
            .Where(e => e.Name.LocalName == "Style")
            .FirstOrDefault(e => (e.Attribute("TargetType")?.Value ?? "").EndsWith("MeridianStateView"));

        Assert.True(stateViewStyle is not null, "No default Style with TargetType MeridianStateView found in Generic.xaml");

        // The Error visual state (or an equivalent trigger) must reference MeridianCriticalBrush
        // somewhere within the style — either as a Setter Value or a VisualState Setter Value.
        var referencesCriticalBrush = stateViewStyle!.Descendants()
            .Where(e => e.Name.LocalName == "Setter")
            .Any(e => (e.Attribute("Value")?.Value ?? "").Contains("MeridianCriticalBrush"));

        Assert.True(referencesCriticalBrush,
            "MeridianStateView default style must reference MeridianCriticalBrush for its Error state text.");

        var xNs = XNamespace.Get("http://schemas.microsoft.com/winfx/2006/xaml");
        var errorVisualState = stateViewStyle.Descendants()
            .Where(e => e.Name.LocalName == "VisualState")
            .FirstOrDefault(e => e.Attribute("Name")?.Value == "Error" || e.Attribute(xNs.GetName("Name"))?.Value == "Error");

        Assert.True(errorVisualState is not null, "MeridianStateView default style must declare an Error VisualState.");
    }

    [Fact]
    public void Action_button_default_tap_target_is_at_least_44px()
    {
        var doc = LoadGeneric();

        var stateViewStyle = doc.Descendants()
            .Where(e => e.Name.LocalName == "Style")
            .FirstOrDefault(e => (e.Attribute("TargetType")?.Value ?? "").EndsWith("MeridianStateView"));

        Assert.True(stateViewStyle is not null, "No default Style with TargetType MeridianStateView found in Generic.xaml");

        var actionButton = stateViewStyle!.Descendants()
            .Where(e => e.Name.LocalName == "Button")
            .FirstOrDefault(e => (e.Attribute("Content")?.Value ?? "").Contains("ActionLabel"));

        Assert.True(actionButton is not null,
            "MeridianStateView default template must declare the ActionLabel/ActionCommand Button.");

        var minWidth = actionButton!.Attribute("MinWidth")?.Value;
        Assert.True(minWidth is not null, "MeridianStateView action Button is missing MinWidth");
        Assert.True(double.Parse(minWidth!) >= 44,
            "MeridianStateView action Button MinWidth must be >= 44 to satisfy the tap-target constraint");

        var minHeight = actionButton.Attribute("MinHeight")?.Value;
        Assert.True(minHeight is not null, "MeridianStateView action Button is missing MinHeight");
        Assert.True(double.Parse(minHeight!) >= 44,
            "MeridianStateView action Button MinHeight must be >= 44 to satisfy the tap-target constraint");
    }
}
