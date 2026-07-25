using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace SakiikaBuilder;

/// <summary>
/// Mirrors the Rust <c>AppConfig</c> exactly. The GUI writes this out as JSON and
/// hands the file to <c>sakiika build</c>, so the engine stays the single source
/// of truth for what a build actually means.
/// </summary>
public sealed class ProjectConfig
{
    [JsonPropertyName("appName")] public string AppName { get; set; } = "マイアプリ";
    [JsonPropertyName("packageName")] public string PackageName { get; set; } = "com.example.myapp";
    [JsonPropertyName("versionName")] public string VersionName { get; set; } = "1.0";
    [JsonPropertyName("versionCode")] public int VersionCode { get; set; } = 1;
    [JsonPropertyName("webRoot")] public string WebRoot { get; set; } = "";
    [JsonPropertyName("entry")] public string Entry { get; set; } = "index.html";

    /// <summary>apk / aab / both.</summary>
    [JsonPropertyName("outputFormat")] public string OutputFormat { get; set; } = "apk";
    [JsonPropertyName("minSdk")] public int MinSdk { get; set; } = 26;
    [JsonPropertyName("targetSdk")] public int TargetSdk { get; set; } = 34;

    /// <summary>unspecified / portrait / landscape / sensor</summary>
    [JsonPropertyName("orientation")] public string Orientation { get; set; } = "unspecified";

    /// <summary>light / dark / auto</summary>
    [JsonPropertyName("theme")] public string Theme { get; set; } = "auto";

    [JsonPropertyName("fullscreen")] public bool Fullscreen { get; set; }
    [JsonPropertyName("splash")] public SplashConfig Splash { get; set; } = new();
    [JsonPropertyName("lightBackground")] public string LightBackground { get; set; } = "#FFFFFF";
    [JsonPropertyName("darkBackground")] public string DarkBackground { get; set; } = "#121212";
    [JsonPropertyName("iconPng")] public string? IconPng { get; set; }
    [JsonPropertyName("iconBackground")] public string IconBackground { get; set; } = "#1E88E5";
    [JsonPropertyName("permissions")] public List<string> Permissions { get; set; } = new() { "INTERNET" };

    /// <summary>off / app_private / folder_pick / documents / media_only / full_manager</summary>
    [JsonPropertyName("fileAccess")] public string FileAccess { get; set; } = "app_private";

    [JsonPropertyName("webview")] public WebViewConfig WebView { get; set; } = new();
    [JsonPropertyName("bridge")] public BridgeConfig Bridge { get; set; } = new();
    [JsonPropertyName("signing")] public SigningConfig Signing { get; set; } = new();
    [JsonPropertyName("outputDir")] public string? OutputDir { get; set; }
    [JsonPropertyName("release")] public bool Release { get; set; }
}

public sealed class SplashConfig
{
    [JsonPropertyName("enabled")] public bool Enabled { get; set; } = true;
    [JsonPropertyName("background")] public string Background { get; set; } = "#1E88E5";
}

public sealed class WebViewConfig
{
    [JsonPropertyName("javascriptEnabled")] public bool JavascriptEnabled { get; set; } = true;
    [JsonPropertyName("domStorage")] public bool DomStorage { get; set; } = true;
    [JsonPropertyName("database")] public bool Database { get; set; } = true;
    [JsonPropertyName("allowUniversalFileAccess")] public bool AllowUniversalFileAccess { get; set; } = true;
    [JsonPropertyName("mixedContent")] public bool MixedContent { get; set; }
    [JsonPropertyName("zoom")] public bool Zoom { get; set; }
    [JsonPropertyName("mediaPlaybackRequiresGesture")] public bool MediaPlaybackRequiresGesture { get; set; }
    [JsonPropertyName("debuggable")] public bool Debuggable { get; set; } = true;
    [JsonPropertyName("userAgentSuffix")] public string UserAgentSuffix { get; set; } = "";
    [JsonPropertyName("externalLinksInBrowser")] public bool ExternalLinksInBrowser { get; set; }
    [JsonPropertyName("backNavigatesHistory")] public bool BackNavigatesHistory { get; set; } = true;
    [JsonPropertyName("pullToRefresh")] public bool PullToRefresh { get; set; }
    [JsonPropertyName("htmlFileInput")] public bool HtmlFileInput { get; set; } = true;
    [JsonPropertyName("allowMediaCapture")] public bool AllowMediaCapture { get; set; } = true;
    [JsonPropertyName("allowGeolocation")] public bool AllowGeolocation { get; set; } = true;
}

public sealed class BridgeConfig
{
    [JsonPropertyName("globalName")] public string GlobalName { get; set; } = "Android";
    [JsonPropertyName("enableReflection")] public bool EnableReflection { get; set; } = true;

    /// <summary>Empty means "every module".</summary>
    [JsonPropertyName("modules")] public List<string> Modules { get; set; } = new();

    [JsonPropertyName("traceCalls")] public bool TraceCalls { get; set; } = true;
}

public sealed class SigningConfig
{
    /// <summary>
    /// PEM-encoded EC P-256 private key. Left empty, the engine creates
    /// <c>sakiika-key.pem</c> in the output folder and reuses it, which is what
    /// lets a rebuilt APK install over the previous one.
    /// </summary>
    [JsonPropertyName("key")] public string? Key { get; set; }
}

// ------------------------------------------------- engine responses (--json)

public sealed class PermissionInfo
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("manifest")] public string Manifest { get; set; } = "";
    [JsonPropertyName("runtime")] public bool Runtime { get; set; }
    [JsonPropertyName("special")] public bool Special { get; set; }
    [JsonPropertyName("group")] public string Group { get; set; } = "";
    [JsonPropertyName("description")] public string Description { get; set; } = "";
    [JsonPropertyName("minSdk")] public int MinSdk { get; set; }
    [JsonPropertyName("maxSdk")] public int MaxSdk { get; set; }
}

public sealed class PermissionList
{
    [JsonPropertyName("permissions")] public List<PermissionInfo> Permissions { get; set; } = new();
}

public sealed class ModuleInfo
{
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("description")] public string Description { get; set; } = "";
    [JsonPropertyName("wants")] public List<string> Wants { get; set; } = new();
}

public sealed class ModuleList
{
    [JsonPropertyName("modules")] public List<ModuleInfo> Modules { get; set; } = new();
}

public sealed class LevelInfo
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("impliedPermissions")] public List<string> ImpliedPermissions { get; set; } = new();
}

public sealed class LevelList
{
    [JsonPropertyName("levels")] public List<LevelInfo> Levels { get; set; } = new();
}

public sealed class DoctorResult
{
    [JsonPropertyName("ok")] public bool Ok { get; set; }

    /// <summary>True when the prebuilt runtime is inside the engine binary.</summary>
    [JsonPropertyName("templateEmbedded")] public bool TemplateEmbedded { get; set; }

    /// <summary>Present only when adb happens to be installed; builds never need it.</summary>
    [JsonPropertyName("adb")] public string? Adb { get; set; }

    [JsonPropertyName("minSdk")] public int MinSdk { get; set; }

    /// <summary>Android SDK / JDK, needed only to regenerate the template.</summary>
    [JsonPropertyName("developerToolchain")] public DeveloperToolchain? DeveloperToolchain { get; set; }

    [JsonPropertyName("error")] public string? Error { get; set; }
}

public sealed class DeveloperToolchain
{
    [JsonPropertyName("sdkRoot")] public string? SdkRoot { get; set; }
    [JsonPropertyName("buildTools")] public string? BuildTools { get; set; }
    [JsonPropertyName("platform")] public int Platform { get; set; }
    [JsonPropertyName("jdk")] public string? Jdk { get; set; }
}

public sealed class BuildDone
{
    [JsonPropertyName("type")] public string Type { get; set; } = "";
    /// <summary>Null when the project asked for AAB output only.</summary>
    [JsonPropertyName("apk")] public string? Apk { get; set; }
    [JsonPropertyName("apkSizeBytes")] public long ApkSizeBytes { get; set; }

    /// <summary>Null unless the project asked for AAB output.</summary>
    [JsonPropertyName("aab")] public string? Aab { get; set; }
    [JsonPropertyName("aabSizeBytes")] public long AabSizeBytes { get; set; }

    [JsonPropertyName("key")] public string Key { get; set; } = "";
    [JsonPropertyName("certificateFingerprint")] public string CertificateFingerprint { get; set; } = "";
    [JsonPropertyName("elapsedMs")] public long ElapsedMs { get; set; }
    [JsonPropertyName("entryCount")] public int EntryCount { get; set; }
    [JsonPropertyName("modules")] public List<string> Modules { get; set; } = new();
    [JsonPropertyName("permissions")] public List<string> Permissions { get; set; } = new();
    [JsonPropertyName("htmlPatched")] public int HtmlPatched { get; set; }
    [JsonPropertyName("assetFiles")] public int AssetFiles { get; set; }
}
