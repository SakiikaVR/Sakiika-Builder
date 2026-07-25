using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace SakiikaBuilder;

/// <summary>
/// Hand-written entry point (the XAML-generated one is disabled via
/// DISABLE_XAML_GENERATED_MAIN).
///
/// The reason to own it: a WinUI 3 failure during startup surfaces as a stowed
/// exception with no message anywhere the user can see. Wrapping the whole
/// startup lets us write a readable log and show a message box instead.
/// </summary>
public static class Program
{
    private static readonly string LogPath =
        Path.Combine(Path.GetTempPath(), "sakiika-builder.log");

    [STAThread]
    public static void Main(string[] args)
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            Log("AppDomain", e.ExceptionObject as Exception);
        TaskSchedulerUnobserved();

        try
        {
            Log("start", null, $"起動 / {Environment.OSVersion} / .NET {Environment.Version}");
            WinRT.ComWrappersSupport.InitializeComWrappers();
            Application.Start(_ignored =>
            {
                var context = new DispatcherQueueSynchronizationContext(
                    DispatcherQueue.GetForCurrentThread());
                SynchronizationContext.SetSynchronizationContext(context);
                _ = new App();
            });
            Log("exit", null, "正常終了");
        }
        catch (Exception e)
        {
            Log("fatal", e);
            ShowFailure(e);
        }
    }

    private static void TaskSchedulerUnobserved()
    {
        System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Log("task", e.Exception);
            e.SetObserved();
        };
    }

    /// <summary>Appends to the log; never throws, because it runs while failing.</summary>
    public static void Log(string tag, Exception? error, string? note = null)
    {
        try
        {
            var text = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {tag}: {note}\n";
            if (error is not null)
            {
                text += $"  {error.GetType().FullName}: {error.Message}\n";
                if (error.StackTrace is not null)
                {
                    text += "  " + error.StackTrace.Replace("\n", "\n  ") + "\n";
                }
                for (var inner = error.InnerException; inner is not null; inner = inner.InnerException)
                {
                    text += $"  内側: {inner.GetType().FullName}: {inner.Message}\n";
                }
            }
            File.AppendAllText(LogPath, text);
        }
        catch
        {
            // Logging must not become the failure.
        }
    }

    private static void ShowFailure(Exception e)
    {
        var message =
            "さきいかビルダーを起動できませんでした。\n\n" +
            $"{e.GetType().Name}: {e.Message}\n\n" +
            $"詳しいログ: {LogPath}";
        MessageBox(IntPtr.Zero, message, "さきいかビルダー", 0x10);
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int MessageBox(IntPtr hWnd, string text, string caption, uint type);
}
