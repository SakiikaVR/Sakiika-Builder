using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace SakiikaBuilder;

public sealed partial class MainWindow : Window
{
    private readonly Engine _engine = new();
    private readonly DispatcherQueue _ui;
    private readonly StringBuilder _log = new();

    private readonly List<(PermissionInfo Info, CheckBox Box, FrameworkElement Row)> _permissionRows = new();
    private readonly List<(ModuleInfo Info, CheckBox Box)> _moduleRows = new();
    private readonly List<(LevelInfo Info, RadioButton Button)> _levelRows = new();

    private CancellationTokenSource? _cancel;
    /// <summary>adb is optional: builds never need it, USB install does.</summary>
    private bool _adbAvailable;
    private string? _lastApk;
    private string? _lastOutputDir;
    private bool _loadingConfig;

    public MainWindow()
    {
        InitializeComponent();
        _ui = DispatcherQueue.GetForCurrentThread();

        Title = "さきいかビルダー — HTML から Android アプリを作る";
        try
        {
            AppWindow.Resize(new Windows.Graphics.SizeInt32(1360, 940));
        }
        catch
        {
            // Resizing is cosmetic; a failure here must not stop startup.
        }

        ThemeCombo.SelectedIndex = 0;
        OrientationCombo.SelectedIndex = 0;
        FormatCombo.SelectedIndex = 0;
        _engine.Output += OnEngineOutput;

        WebRootBox.TextChanged += (_, _) => UpdateSuggestions();
        AppNameBox.TextChanged += (_, _) => AutoFillPackage();
        GlobalNameBox.TextChanged += (_, _) =>
            GlobalNameHint.Text = $"window.{GlobalNameBox.Text.Trim()} から呼べます";
        SplashSwitch.Toggled += (_, _) => SplashBgBox.IsEnabled = SplashSwitch.IsOn;

        _ = InitializeAsync();
    }

    // -------------------------------------------------------------- 起動処理

    private async Task InitializeAsync()
    {
        if (!_engine.IsAvailable)
        {
            ToolchainText.Text = "ビルドエンジンが見つかりません";
            ShowResult(InfoBarSeverity.Error, "sakiika.exe が見つかりません",
                $"{_engine.ExecutablePath} に置いてください。" +
                "リポジトリから動かす場合は先に `cargo build --release` を実行します。");
            SetBusy(false);
            return;
        }

        SetBusy(true, "ツールチェーンと一覧を読み込み中…");
        try
        {
            var levelsTask = _engine.LevelsAsync();
            var permsTask = _engine.PermissionsAsync();
            var modulesTask = _engine.ModulesAsync();
            await Task.WhenAll(levelsTask, permsTask, modulesTask);

            BuildLevelRows(await levelsTask);
            BuildPermissionRows(await permsTask);
            BuildModuleRows(await modulesTask);
            UpdateSuggestions();

            await RefreshDoctorAsync();
        }
        catch (Exception e)
        {
            ShowResult(InfoBarSeverity.Error, "初期化に失敗しました", e.Message);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private async Task RefreshDoctorAsync()
    {
        var target = (int)SafeValue(TargetSdkBox, 34);
        var doctor = await _engine.DoctorAsync(target);
        _adbAvailable = !string.IsNullOrEmpty(doctor.Adb);

        if (doctor.Ok)
        {
            // Nothing external is needed to build; adb only matters for USB install.
            ToolchainText.Text =
                "ビルドに必要なものは内蔵済み — Java・Android SDK・Gradle 不要"
                + $"  /  対応 Android {doctor.MinSdk} 以上"
                + (_adbAvailable ? "  /  adb あり（USB インストール可）" : "  /  adb なし（APK を手動でコピー）");
            SdkHintText.Text = $"（Android {doctor.MinSdk} 以上が対象です）";
            BuildButton.IsEnabled = true;
            BuildInstallButton.IsEnabled = _adbAvailable;
            InstallLastButton.IsEnabled = _adbAvailable && _lastApk is not null;
        }
        else
        {
            ToolchainText.Text = "ランタイムが埋め込まれていません";
            BuildButton.IsEnabled = false;
            BuildInstallButton.IsEnabled = false;
            ShowResult(InfoBarSeverity.Error, "ビルドできません",
                doctor.Error ?? "原因不明。sakiika.exe を作り直してください。");
        }
    }

    // ---------------------------------------------------------- 一覧の組み立て

    private void BuildLevelRows(List<LevelInfo> levels)
    {
        FileAccessPanel.Children.Clear();
        _levelRows.Clear();
        foreach (var level in levels)
        {
            var button = new RadioButton
            {
                Content = level.Label,
                Tag = level.Id,
                GroupName = "fileAccess",
                IsChecked = level.Id == "app_private"
            };
            button.Checked += (_, _) => OnLevelChanged();
            FileAccessPanel.Children.Add(button);

            if (level.ImpliedPermissions.Count > 0)
            {
                FileAccessPanel.Children.Add(new TextBlock
                {
                    Text = "自動で付く権限: " + string.Join(", ", level.ImpliedPermissions),
                    FontSize = 11,
                    Opacity = 0.65,
                    Margin = new Thickness(30, 0, 0, 6),
                    TextWrapping = TextWrapping.Wrap
                });
            }
            _levelRows.Add((level, button));
        }
        OnLevelChanged();
    }

    private void OnLevelChanged()
    {
        var id = SelectedLevel();
        FileAccessInfo.IsOpen = true;
        (FileAccessInfo.Severity, FileAccessInfo.Message) = id switch
        {
            "off" => (InfoBarSeverity.Informational,
                "fs モジュールは組み込まれません。ファイルを一切読み書きしないアプリ向けです。"),
            "app_private" => (InfoBarSeverity.Success,
                "アプリ専用領域だけを読み書きします。権限もユーザー確認も不要で、アンインストールすると消えます。"),
            "folder_pick" => (InfoBarSeverity.Success,
                "起動後に一度フォルダーを選んでもらい、その配下だけを読み書きします。選択は次回起動後も保持されます。"),
            "documents" => (InfoBarSeverity.Informational,
                "フォルダー選択に加えて、必要なときにファイル単位でも選んでもらえます。権限は不要です。"),
            "media_only" => (InfoBarSeverity.Informational,
                "写真・動画・音声だけを読めます。READ_MEDIA_* の実行時許可が必要です。"),
            "full_manager" => (InfoBarSeverity.Warning,
                "全ストレージにアクセスします。MANAGE_EXTERNAL_STORAGE は設定画面での手動許可が必要で、" +
                "Google Play では用途の審査対象になります。ファイルマネージャーのようなアプリだけに使ってください。"),
            _ => (InfoBarSeverity.Informational, "")
        };
        UpdateSuggestions();
    }

    private string SelectedLevel() =>
        _levelRows.FirstOrDefault(r => r.Button.IsChecked == true).Info?.Id ?? "app_private";

    private void BuildPermissionRows(List<PermissionInfo> permissions)
    {
        PermissionsPanel.Children.Clear();
        _permissionRows.Clear();

        foreach (var group in permissions.GroupBy(p => p.Group))
        {
            PermissionsPanel.Children.Add(new TextBlock
            {
                Text = group.Key,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                FontSize = 13,
                Margin = new Thickness(0, 10, 0, 2)
            });

            foreach (var permission in group)
            {
                var box = new CheckBox
                {
                    IsChecked = permission.Id == "INTERNET",
                    Margin = new Thickness(0),
                    MinHeight = 0
                };

                var kind = permission.Special ? "設定画面で許可"
                    : permission.Runtime ? "実行時に確認"
                    : "インストール時";
                var sdkNote = permission.MinSdk > 0 ? $" / API {permission.MinSdk}+" : "";
                if (permission.MaxSdk > 0) { sdkNote += $" / API {permission.MaxSdk} まで"; }

                box.Content = new StackPanel
                {
                    Children =
                    {
                        new TextBlock { Text = permission.Id, FontSize = 13 },
                        new TextBlock
                        {
                            Text = $"{permission.Description}（{kind}{sdkNote}）",
                            FontSize = 11,
                            Opacity = 0.65,
                            TextWrapping = TextWrapping.Wrap
                        }
                    }
                };

                var row = new Grid { Margin = new Thickness(4, 2, 0, 2) };
                row.Children.Add(box);
                PermissionsPanel.Children.Add(row);
                _permissionRows.Add((permission, box, row));
            }
        }

        PermissionCountText.Text = $"{permissions.Count} 件から選べます。必要なものだけチェックしてください。";
    }

    private void BuildModuleRows(List<ModuleInfo> modules)
    {
        ModulesPanel.Children.Clear();
        _moduleRows.Clear();
        foreach (var module in modules)
        {
            var box = new CheckBox
            {
                IsChecked = true,
                MinHeight = 0,
                Margin = new Thickness(0, 1, 0, 1),
                Content = new StackPanel
                {
                    Children =
                    {
                        new TextBlock { Text = $"Android.{module.Name}", FontSize = 13 },
                        new TextBlock
                        {
                            Text = module.Description,
                            FontSize = 11,
                            Opacity = 0.65,
                            TextWrapping = TextWrapping.Wrap
                        }
                    }
                }
            };
            box.Checked += (_, _) => UpdateSuggestions();
            box.Unchecked += (_, _) => UpdateSuggestions();
            ModulesPanel.Children.Add(box);
            _moduleRows.Add((module, box));
        }
    }

    /// <summary>
    /// An AAB is not installable, so the install actions only make sense when an
    /// APK is part of the output.
    /// </summary>
    private void OnFormatChanged(object sender, SelectionChangedEventArgs e)
    {
        if (BuildInstallButton is null) { return; }
        var producesApk = TagOf(FormatCombo, "apk") != "aab";
        BuildInstallButton.IsEnabled = producesApk && _adbAvailable;
        InstallLastButton.IsEnabled = producesApk && _adbAvailable && _lastApk is not null;
        if (!producesApk)
        {
            ShowResult(InfoBarSeverity.Informational, "AAB は端末に直接入れられません",
                "Google Play Console にアップロードして配布します。端末で試すなら「APK と AAB の両方」を選んでください。");
        }
    }

    private void OnPermissionFilterChanged(object sender, TextChangedEventArgs e)
    {
        var needle = PermissionFilterBox.Text.Trim();
        foreach (var (info, box, row) in _permissionRows)
        {
            var match = needle.Length == 0
                        || info.Id.Contains(needle, StringComparison.OrdinalIgnoreCase)
                        || info.Description.Contains(needle, StringComparison.OrdinalIgnoreCase)
                        || info.Group.Contains(needle, StringComparison.OrdinalIgnoreCase);
            // Checked-but-filtered-out permissions stay visible, so nothing the
            // user selected can silently scroll out of reach.
            row.Visibility = match || box.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        }
    }

    private void UpdateSuggestions()
    {
        if (_loadingConfig) { return; }
        var wanted = _moduleRows
            .Where(m => m.Box.IsChecked == true)
            .SelectMany(m => m.Info.Wants)
            .Distinct()
            .ToList();
        var missing = wanted
            .Where(id => _permissionRows.Any(p => p.Info.Id == id && p.Box.IsChecked != true))
            .ToList();
        PermissionCountText.Text = missing.Count == 0
            ? $"{_permissionRows.Count} 件から選べます。選んだ機能に足りない権限はありません。"
            : $"{_permissionRows.Count} 件から選べます。選んだ機能には {string.Join(", ", missing.Take(6))}" +
              (missing.Count > 6 ? " ほか" : "") + " があると便利です（「必要な分を推測」で追加）。";
    }

    private void OnSuggestPermissions(object sender, RoutedEventArgs e)
    {
        var wanted = _moduleRows
            .Where(m => m.Box.IsChecked == true)
            .SelectMany(m => m.Info.Wants)
            .Distinct()
            .ToHashSet();
        foreach (var (info, box, _) in _permissionRows)
        {
            if (wanted.Contains(info.Id)) { box.IsChecked = true; }
        }
        UpdateSuggestions();
        OnPermissionFilterChanged(sender, null!);
    }

    private void OnClearPermissions(object sender, RoutedEventArgs e)
    {
        foreach (var (_, box, _) in _permissionRows) { box.IsChecked = false; }
        UpdateSuggestions();
    }

    private void AutoFillPackage()
    {
        if (_loadingConfig) { return; }
        // Only auto-derive while the user has not personalised the package name.
        if (PackageBox.Text is not ("com.example.myapp" or "")) { return; }
        var slug = new string(AppNameBox.Text.Where(char.IsAsciiLetterOrDigit).ToArray()).ToLowerInvariant();
        if (slug.Length == 0 || !char.IsAsciiLetterLower(slug[0])) { slug = "app" + slug; }
        PackageBox.Text = $"com.example.{slug}";
    }

    // ------------------------------------------------------------- ピッカー

    private IntPtr Hwnd => WinRT.Interop.WindowNative.GetWindowHandle(this);

    private async Task<string?> PickFolderAsync()
    {
        var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.Desktop };
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, Hwnd);
        var folder = await picker.PickSingleFolderAsync();
        return folder?.Path;
    }

    private async Task<string?> PickFileAsync(params string[] extensions)
    {
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.Desktop };
        foreach (var extension in extensions) { picker.FileTypeFilter.Add(extension); }
        if (extensions.Length == 0) { picker.FileTypeFilter.Add("*"); }
        WinRT.Interop.InitializeWithWindow.Initialize(picker, Hwnd);
        var file = await picker.PickSingleFileAsync();
        return file?.Path;
    }

    private async void OnBrowseWebRoot(object sender, RoutedEventArgs e)
    {
        var path = await PickFolderAsync();
        if (path is null) { return; }
        WebRootBox.Text = path;
        // A folder without index.html is the most common first-run mistake.
        if (!File.Exists(Path.Combine(path, EntryBox.Text.Trim())))
        {
            var candidate = Directory.EnumerateFiles(path, "*.htm*", SearchOption.TopDirectoryOnly)
                .Select(Path.GetFileName)
                .FirstOrDefault();
            if (candidate is not null)
            {
                EntryBox.Text = candidate;
                ShowResult(InfoBarSeverity.Informational, "開始ファイルを推測しました",
                    $"{candidate} を開始ファイルにしました。違う場合は書き換えてください。");
            }
            else
            {
                ShowResult(InfoBarSeverity.Warning, "HTML が見つかりません",
                    "選んだフォルダーの直下に .html がありません。");
            }
        }
    }

    private async void OnPickEntry(object sender, RoutedEventArgs e)
    {
        var path = await PickFileAsync(".html", ".htm");
        if (path is null) { return; }
        var root = WebRootBox.Text.Trim();
        if (root.Length == 0)
        {
            WebRootBox.Text = Path.GetDirectoryName(path) ?? "";
            EntryBox.Text = Path.GetFileName(path);
            return;
        }
        // Store it relative to the web root so the project file stays portable.
        var relative = Path.GetRelativePath(root, path).Replace('\\', '/');
        if (relative.StartsWith("..", StringComparison.Ordinal))
        {
            ShowResult(InfoBarSeverity.Warning, "HTML フォルダーの外です",
                "開始ファイルは HTML フォルダーの中から選んでください。");
            return;
        }
        EntryBox.Text = relative;
    }

    private async void OnBrowseIcon(object sender, RoutedEventArgs e)
    {
        var path = await PickFileAsync(".png");
        if (path is not null) { IconBox.Text = path; }
    }

    private void OnClearIcon(object sender, RoutedEventArgs e) => IconBox.Text = "";

    private async void OnBrowseOutput(object sender, RoutedEventArgs e)
    {
        var path = await PickFolderAsync();
        if (path is not null) { OutputDirBox.Text = path; }
    }

    private async void OnBrowseKey(object sender, RoutedEventArgs e)
    {
        var path = await PickFileAsync(".pem");
        if (path is not null) { KeyBox.Text = path; }
    }

    // ------------------------------------------------------------- 設定の往復

    private static double SafeValue(NumberBox box, double fallback) =>
        double.IsNaN(box.Value) ? fallback : box.Value;

    private static string TagOf(ComboBox combo, string fallback) =>
        (combo.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? fallback;

    private ProjectConfig CollectConfig()
    {
        var allModulesChecked = _moduleRows.All(m => m.Box.IsChecked == true);
        return new ProjectConfig
        {
            AppName = AppNameBox.Text.Trim(),
            PackageName = PackageBox.Text.Trim(),
            VersionName = VersionNameBox.Text.Trim(),
            VersionCode = (int)SafeValue(VersionCodeBox, 1),
            WebRoot = WebRootBox.Text.Trim(),
            Entry = EntryBox.Text.Trim(),
            OutputFormat = TagOf(FormatCombo, "apk"),
            MinSdk = (int)SafeValue(MinSdkBox, 26),
            TargetSdk = (int)SafeValue(TargetSdkBox, 34),
            Orientation = TagOf(OrientationCombo, "unspecified"),
            Theme = TagOf(ThemeCombo, "auto"),
            Fullscreen = FullscreenCheck.IsChecked == true,
            Splash = new SplashConfig
            {
                Enabled = SplashSwitch.IsOn,
                Background = SplashBgBox.Text.Trim()
            },
            LightBackground = LightBgBox.Text.Trim(),
            DarkBackground = DarkBgBox.Text.Trim(),
            IconBackground = IconBgBox.Text.Trim(),
            IconPng = string.IsNullOrWhiteSpace(IconBox.Text) ? null : IconBox.Text.Trim(),
            FileAccess = SelectedLevel(),
            Permissions = _permissionRows
                .Where(p => p.Box.IsChecked == true)
                .Select(p => p.Info.Id)
                .ToList(),
            WebView = new WebViewConfig
            {
                JavascriptEnabled = JsCheck.IsChecked == true,
                DomStorage = DomStorageCheck.IsChecked == true,
                Database = DomStorageCheck.IsChecked == true,
                AllowUniversalFileAccess = UniversalCheck.IsChecked == true,
                MixedContent = MixedCheck.IsChecked == true,
                Zoom = ZoomCheck.IsChecked == true,
                Debuggable = DebugCheck.IsChecked == true,
                UserAgentSuffix = UaSuffixBox.Text.Trim(),
                ExternalLinksInBrowser = ExternalLinksCheck.IsChecked == true,
                BackNavigatesHistory = BackNavCheck.IsChecked == true,
                PullToRefresh = PullRefreshCheck.IsChecked == true,
                HtmlFileInput = FileInputCheck.IsChecked == true,
                AllowMediaCapture = MediaCaptureCheck.IsChecked == true,
                AllowGeolocation = GeoCheck.IsChecked == true
            },
            Bridge = new BridgeConfig
            {
                GlobalName = GlobalNameBox.Text.Trim() is { Length: > 0 } name ? name : "Android",
                EnableReflection = ReflectionCheck.IsChecked == true,
                // An empty list means "all"; sending every name would freeze the
                // set even if a later version adds a module.
                Modules = allModulesChecked
                    ? new List<string>()
                    : _moduleRows.Where(m => m.Box.IsChecked == true).Select(m => m.Info.Name).ToList(),
                TraceCalls = TraceCheck.IsChecked == true
            },
            Signing = new SigningConfig
            {
                Key = string.IsNullOrWhiteSpace(KeyBox.Text) ? null : KeyBox.Text.Trim()
            },
            OutputDir = string.IsNullOrWhiteSpace(OutputDirBox.Text) ? null : OutputDirBox.Text.Trim(),
            Release = ReleaseCheck.IsChecked == true
        };
    }

    private void ApplyConfig(ProjectConfig cfg)
    {
        _loadingConfig = true;
        try
        {
            AppNameBox.Text = cfg.AppName;
            PackageBox.Text = cfg.PackageName;
            VersionNameBox.Text = cfg.VersionName;
            VersionCodeBox.Value = cfg.VersionCode;
            WebRootBox.Text = cfg.WebRoot;
            EntryBox.Text = cfg.Entry;
            SelectByTag(FormatCombo, cfg.OutputFormat);
            MinSdkBox.Value = cfg.MinSdk;
            TargetSdkBox.Value = cfg.TargetSdk;
            SelectByTag(OrientationCombo, cfg.Orientation);
            SelectByTag(ThemeCombo, cfg.Theme);
            FullscreenCheck.IsChecked = cfg.Fullscreen;
            SplashSwitch.IsOn = cfg.Splash.Enabled;
            SplashBgBox.Text = cfg.Splash.Background;
            SplashBgBox.IsEnabled = cfg.Splash.Enabled;
            LightBgBox.Text = cfg.LightBackground;
            DarkBgBox.Text = cfg.DarkBackground;
            IconBgBox.Text = cfg.IconBackground;
            IconBox.Text = cfg.IconPng ?? "";

            foreach (var (info, button) in _levelRows)
            {
                button.IsChecked = info.Id == cfg.FileAccess;
            }

            var wanted = cfg.Permissions.ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var (info, box, _) in _permissionRows)
            {
                box.IsChecked = wanted.Contains(info.Id);
            }

            var modules = cfg.Bridge.Modules;
            foreach (var (info, box) in _moduleRows)
            {
                box.IsChecked = modules.Count == 0 || modules.Contains(info.Name);
            }

            GlobalNameBox.Text = cfg.Bridge.GlobalName;
            ReflectionCheck.IsChecked = cfg.Bridge.EnableReflection;
            TraceCheck.IsChecked = cfg.Bridge.TraceCalls;

            JsCheck.IsChecked = cfg.WebView.JavascriptEnabled;
            DomStorageCheck.IsChecked = cfg.WebView.DomStorage;
            UniversalCheck.IsChecked = cfg.WebView.AllowUniversalFileAccess;
            MixedCheck.IsChecked = cfg.WebView.MixedContent;
            ZoomCheck.IsChecked = cfg.WebView.Zoom;
            DebugCheck.IsChecked = cfg.WebView.Debuggable;
            UaSuffixBox.Text = cfg.WebView.UserAgentSuffix;
            ExternalLinksCheck.IsChecked = cfg.WebView.ExternalLinksInBrowser;
            BackNavCheck.IsChecked = cfg.WebView.BackNavigatesHistory;
            PullRefreshCheck.IsChecked = cfg.WebView.PullToRefresh;
            FileInputCheck.IsChecked = cfg.WebView.HtmlFileInput;
            MediaCaptureCheck.IsChecked = cfg.WebView.AllowMediaCapture;
            GeoCheck.IsChecked = cfg.WebView.AllowGeolocation;

            KeyBox.Text = cfg.Signing.Key ?? "";
            OutputDirBox.Text = cfg.OutputDir ?? "";
            ReleaseCheck.IsChecked = cfg.Release;
        }
        finally
        {
            _loadingConfig = false;
        }
        UpdateSuggestions();
        OnPermissionFilterChanged(this, null!);
    }

    private static void SelectByTag(ComboBox combo, string tag)
    {
        for (var i = 0; i < combo.Items.Count; i++)
        {
            if (combo.Items[i] is ComboBoxItem item && item.Tag?.ToString() == tag)
            {
                combo.SelectedIndex = i;
                return;
            }
        }
    }

    private async void OnSaveProject(object sender, RoutedEventArgs e)
    {
        var picker = new FileSavePicker
        {
            SuggestedStartLocation = PickerLocationId.Desktop,
            SuggestedFileName = "sakiika"
        };
        picker.FileTypeChoices.Add("さきいかビルダー設定", new List<string> { ".json" });
        WinRT.Interop.InitializeWithWindow.Initialize(picker, Hwnd);
        var file = await picker.PickSaveFileAsync();
        if (file is null) { return; }
        try
        {
            var json = JsonSerializer.Serialize(CollectConfig(),
                new JsonSerializerOptions { WriteIndented = true });
            await File.WriteAllTextAsync(file.Path, json, new UTF8Encoding(false));
            ShowResult(InfoBarSeverity.Success, "設定を保存しました", file.Path);
        }
        catch (Exception ex)
        {
            ShowResult(InfoBarSeverity.Error, "保存に失敗しました", ex.Message);
        }
    }

    private async void OnLoadProject(object sender, RoutedEventArgs e)
    {
        var path = await PickFileAsync(".json");
        if (path is null) { return; }
        try
        {
            var json = await File.ReadAllTextAsync(path);
            var cfg = JsonSerializer.Deserialize<ProjectConfig>(json,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            if (cfg is null) { throw new InvalidOperationException("空の設定ファイルです"); }

            // Paths in a project file are relative to the file itself.
            var baseDir = Path.GetDirectoryName(Path.GetFullPath(path))!;
            if (cfg.WebRoot.Length > 0 && !Path.IsPathRooted(cfg.WebRoot))
            {
                cfg.WebRoot = Path.GetFullPath(Path.Combine(baseDir, cfg.WebRoot));
            }
            if (cfg.OutputDir is { Length: > 0 } outDir && !Path.IsPathRooted(outDir))
            {
                cfg.OutputDir = Path.GetFullPath(Path.Combine(baseDir, outDir));
            }
            if (cfg.IconPng is { Length: > 0 } icon && !Path.IsPathRooted(icon))
            {
                cfg.IconPng = Path.GetFullPath(Path.Combine(baseDir, icon));
            }

            ApplyConfig(cfg);
            ShowResult(InfoBarSeverity.Success, "設定を読み込みました", path);
        }
        catch (Exception ex)
        {
            ShowResult(InfoBarSeverity.Error, "読み込みに失敗しました", ex.Message);
        }
    }

    // ------------------------------------------------------------- ビルド

    private void OnBuild(object sender, RoutedEventArgs e) => _ = RunBuildAsync(false);

    private void OnBuildAndInstall(object sender, RoutedEventArgs e) => _ = RunBuildAsync(true);

    private async Task RunBuildAsync(bool install)
    {
        var cfg = CollectConfig();

        var problem = Validate(cfg);
        if (problem is not null)
        {
            ShowResult(InfoBarSeverity.Error, "設定を直してください", problem);
            return;
        }

        _cancel = new CancellationTokenSource();
        SetBusy(true, "ビルドを開始…");
        CancelButton.IsEnabled = true;
        BuildButton.IsEnabled = false;
        BuildInstallButton.IsEnabled = false;
        ResultBar.IsOpen = false;
        ClearLog();

        var projectPath = Path.Combine(Path.GetTempPath(), "sakiika-gui-project.json");
        try
        {
            var json = JsonSerializer.Serialize(cfg, new JsonSerializerOptions { WriteIndented = true });
            await File.WriteAllTextAsync(projectPath, json, new UTF8Encoding(false));

            var done = await _engine.BuildAsync(
                projectPath,
                install,
                onStep: (name, detail) => Post(() =>
                {
                    StatusText.Text = $"{name} {detail}";
                    AppendLog($"▶ {name} {detail}");
                }),
                onLog: line => Post(() =>
                {
                    if (VerboseCheck.IsChecked == true || !LooksLikeCommandEcho(line))
                    {
                        AppendLog("  " + line);
                    }
                }),
                cancel: _cancel.Token);

            _lastApk = done.Apk;
            var primary = done.Apk ?? done.Aab;
            _lastOutputDir = primary is null ? null : Path.GetDirectoryName(primary);
            OpenOutputButton.IsEnabled = _lastOutputDir is not null;
            InstallLastButton.IsEnabled = _adbAvailable && _lastApk is not null;

            var elapsed = done.ElapsedMs < 1000
                ? $"{done.ElapsedMs} ミリ秒"
                : $"{done.ElapsedMs / 1000.0:F1} 秒";
            var artifacts = new List<string>();
            if (done.Apk is not null)
            {
                artifacts.Add($"{Path.GetFileName(done.Apk)}  ({done.ApkSizeBytes / 1024.0 / 1024.0:F2} MB)");
            }
            if (done.Aab is not null)
            {
                artifacts.Add($"{Path.GetFileName(done.Aab)}  ({done.AabSizeBytes / 1024.0 / 1024.0:F2} MB)");
            }
            ShowResult(InfoBarSeverity.Success,
                install ? "ビルドしてインストールしました" : "ビルドが完了しました",
                string.Join("\n", artifacts) + $"\n{elapsed}\n" +
                $"モジュール {done.Modules.Count} 個、権限 {done.Permissions.Count} 個、" +
                $"HTML {done.HtmlPatched} 件にブリッジを挿入、アセット {done.AssetFiles} ファイル\n" +
                $"署名鍵: {done.Key}\n" +
                (_lastOutputDir ?? ""));
            StatusText.Text = "完了";
        }
        catch (OperationCanceledException)
        {
            ShowResult(InfoBarSeverity.Informational, "中止しました", "ビルドを取り消しました。");
            StatusText.Text = "中止";
        }
        catch (Exception ex)
        {
            ShowResult(InfoBarSeverity.Error, "ビルドに失敗しました", ex.Message);
            AppendLog("");
            AppendLog("=== 失敗 ===");
            AppendLog(ex.Message);
            StatusText.Text = "失敗";
        }
        finally
        {
            SetBusy(false);
            CancelButton.IsEnabled = false;
            BuildButton.IsEnabled = true;
            BuildInstallButton.IsEnabled = true;
            _cancel?.Dispose();
            _cancel = null;
        }
    }

    /// <summary>The engine echoes the full tool command line; noisy unless asked for.</summary>
    private static bool LooksLikeCommandEcho(string line) =>
        line.TrimStart().StartsWith('"') && line.Contains(".exe") || line.Contains(".bat\"");

    private string? Validate(ProjectConfig cfg)
    {
        if (cfg.WebRoot.Length == 0) { return "HTML フォルダーを指定してください。"; }
        if (!Directory.Exists(cfg.WebRoot)) { return $"HTML フォルダーが見つかりません: {cfg.WebRoot}"; }
        if (!File.Exists(Path.Combine(cfg.WebRoot, cfg.Entry)))
        {
            return $"開始ファイルが見つかりません: {Path.Combine(cfg.WebRoot, cfg.Entry)}";
        }
        if (cfg.AppName.Length == 0) { return "アプリ名を入力してください。"; }
        if (!IsValidPackage(cfg.PackageName))
        {
            return $"パッケージ名が不正です: {cfg.PackageName}\n" +
                   "com.example.myapp のように、2 つ以上の要素をドットでつなぎ、各要素は英小文字で始めてください。";
        }
        if (cfg.MinSdk < 26)
        {
            return "対応 Android の下限は 26（Android 8.0）以上にしてください。\n" +
                   "アダプティブアイコンと v2 署名がその世代から使えるようになります。";
        }
        if (cfg.TargetSdk < cfg.MinSdk) { return "対応 Android の下限が上限を超えています。"; }
        if (cfg.Bridge.Modules.Count == 0 && _moduleRows.All(m => m.Box.IsChecked != true))
        {
            return "JavaScript から使う機能を 1 つ以上選んでください。";
        }
        return null;
    }

    private static bool IsValidPackage(string name)
    {
        var parts = name.Split('.');
        if (parts.Length < 2) { return false; }
        return parts.All(part =>
            part.Length > 0
            && char.IsAsciiLetterLower(part[0])
            && part.All(ch => char.IsAsciiLetterLower(ch) || char.IsAsciiDigit(ch) || ch == '_'));
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        _cancel?.Cancel();
        StatusText.Text = "中止しています…";
    }

    private async void OnInstallLast(object sender, RoutedEventArgs e)
    {
        if (_lastApk is null || !File.Exists(_lastApk))
        {
            ShowResult(InfoBarSeverity.Warning, "APK がありません", "先にビルドしてください。");
            return;
        }
        if (!_adbAvailable)
        {
            ShowResult(InfoBarSeverity.Informational, "adb がありません",
                "APK を端末にコピーして開けばインストールできます（提供元不明のアプリの許可が必要）。");
            return;
        }
        SetBusy(true, "端末にインストール中…");
        try
        {
            await _engine.InstallAsync(_lastApk);
            ShowResult(InfoBarSeverity.Success, "インストールしました", Path.GetFileName(_lastApk));
        }
        catch (Exception ex)
        {
            ShowResult(InfoBarSeverity.Error, "インストールに失敗しました", ex.Message);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void OnOpenOutput(object sender, RoutedEventArgs e)
    {
        if (_lastOutputDir is null || !Directory.Exists(_lastOutputDir)) { return; }
        // Select the APK inside Explorer rather than just opening the folder.
        var argument = _lastApk is not null && File.Exists(_lastApk)
            ? $"/select,\"{_lastApk}\""
            : $"\"{_lastOutputDir}\"";
        Process.Start(new ProcessStartInfo("explorer.exe", argument) { UseShellExecute = true });
    }

    private async void OnDoctor(object sender, RoutedEventArgs e)
    {
        SetBusy(true, "ツールチェーンを確認中…");
        try
        {
            await RefreshDoctorAsync();
            if (BuildButton.IsEnabled)
            {
                ShowResult(InfoBarSeverity.Success, "ビルドできます", ToolchainText.Text);
            }
        }
        finally
        {
            SetBusy(false);
        }
    }

    // --------------------------------------------------------------- ログ

    private void OnEngineOutput(string line, bool isError)
    {
        if (isError) { Post(() => AppendLog("! " + line)); }
    }

    private void OnClearLog(object sender, RoutedEventArgs e) => ClearLog();

    private void ClearLog()
    {
        _log.Clear();
        LogText.Text = "";
    }

    private void AppendLog(string line)
    {
        _log.AppendLine(line);
        // Trimming keeps a very chatty verbose build from bloating the visual tree.
        if (_log.Length > 400_000)
        {
            _log.Remove(0, _log.Length - 300_000);
        }
        LogText.Text = _log.ToString();
        LogScroller.ChangeView(null, LogScroller.ScrollableHeight, null, true);
    }

    private void Post(Action action)
    {
        if (_ui.HasThreadAccess) { action(); }
        else { _ui.TryEnqueue(() => action()); }
    }

    private void SetBusy(bool busy, string? status = null)
    {
        BusyRing.IsActive = busy;
        if (status is not null) { StatusText.Text = status; }
        else if (!busy) { StatusText.Text = "準備完了"; }
    }

    private void ShowResult(InfoBarSeverity severity, string title, string message)
    {
        ResultBar.Severity = severity;
        ResultBar.Title = title;
        ResultBar.Message = message;
        ResultBar.IsOpen = true;
    }
}
