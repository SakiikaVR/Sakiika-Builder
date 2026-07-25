using System;
using Microsoft.UI.Xaml;

namespace SakiikaBuilder;

public partial class App : Application
{
    private Window? _window;

    public App()
    {
        InitializeComponent();
        // A XAML binding or template failure otherwise takes the process down
        // with no diagnostics at all.
        UnhandledException += (_, e) =>
        {
            Program.Log("xaml", e.Exception);
            e.Handled = true;
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            _window = new MainWindow();
            _window.Activate();
            Program.Log("window", null, "メインウィンドウを表示しました");
        }
        catch (Exception e)
        {
            Program.Log("onlaunched", e);
            throw;
        }
    }
}
