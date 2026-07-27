use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// How much of the device filesystem the generated app may touch.
///
/// The ladder runs from "no filesystem at all" up to "file-manager grade access
/// to shared storage", which is exactly the spread the GUI exposes as a slider.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FileAccess {
    /// The `fs` bridge module is not compiled into the app at all.
    #[serde(rename = "off")]
    Off,
    /// Only the app-private sandbox (filesDir/cacheDir/getExternalFilesDir).
    /// Needs no permission and no user consent.
    #[serde(rename = "app_private")]
    AppPrivate,
    /// One user-granted folder tree via ACTION_OPEN_DOCUMENT_TREE, persisted
    /// across restarts. Every path is resolved relative to that root.
    #[serde(rename = "folder_pick")]
    FolderPick,
    /// Ad-hoc picking: the app can ask for individual files or extra trees at
    /// any time (ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE / CREATE_DOCUMENT).
    #[serde(rename = "documents")]
    Documents,
    /// Scoped media access only (images / video / audio collections).
    #[serde(rename = "media_only")]
    MediaOnly,
    /// MANAGE_EXTERNAL_STORAGE: raw java.io.File access over all of shared
    /// storage, the level a file manager runs at.
    #[serde(rename = "full_manager")]
    FullManager,
}

impl FileAccess {
    pub fn as_java_enum(self) -> &'static str {
        match self {
            FileAccess::Off => "OFF",
            FileAccess::AppPrivate => "APP_PRIVATE",
            FileAccess::FolderPick => "FOLDER_PICK",
            FileAccess::Documents => "DOCUMENTS",
            FileAccess::MediaOnly => "MEDIA_ONLY",
            FileAccess::FullManager => "FULL_MANAGER",
        }
    }

    /// Permissions this access level implies, on top of whatever the user ticked.
    pub fn implied_permissions(self) -> &'static [&'static str] {
        match self {
            FileAccess::Off | FileAccess::AppPrivate | FileAccess::FolderPick
            | FileAccess::Documents => &[],
            FileAccess::MediaOnly => &["READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"],
            FileAccess::FullManager => &[
                "MANAGE_EXTERNAL_STORAGE",
                "READ_EXTERNAL_STORAGE",
                "WRITE_EXTERNAL_STORAGE",
            ],
        }
    }

    pub fn label_ja(self) -> &'static str {
        match self {
            FileAccess::Off => "オフ（ファイルアクセスなし）",
            FileAccess::AppPrivate => "アプリ専用領域のみ",
            FileAccess::FolderPick => "ユーザーが選んだフォルダーのみ",
            FileAccess::Documents => "都度選択（ファイル/フォルダーをその場で指定）",
            FileAccess::MediaOnly => "メディアのみ（画像・動画・音声）",
            FileAccess::FullManager => "ファイルマネージャーレベル（全ストレージ）",
        }
    }

    pub fn all() -> &'static [FileAccess] {
        &[
            FileAccess::Off,
            FileAccess::AppPrivate,
            FileAccess::FolderPick,
            FileAccess::Documents,
            FileAccess::MediaOnly,
            FileAccess::FullManager,
        ]
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct WebViewOptions {
    pub javascript_enabled: bool,
    pub dom_storage: bool,
    pub database: bool,
    /// setAllowUniversalAccessFromFileURLs — lets file:// pages fetch http(s).
    pub allow_universal_file_access: bool,
    pub mixed_content: bool,
    pub zoom: bool,
    pub media_playback_requires_gesture: bool,
    /// Enables chrome://inspect remote debugging of the WebView.
    pub debuggable: bool,
    pub user_agent_suffix: String,
    /// Open http(s) links in the system browser instead of inside the WebView.
    pub external_links_in_browser: bool,
    /// Hardware back button navigates WebView history before exiting.
    pub back_navigates_history: bool,
    /// Pull-to-refresh gesture on the WebView.
    pub pull_to_refresh: bool,
    /// Show a native file chooser when a page uses <input type="file">.
    pub html_file_input: bool,
    /// Allow getUserMedia (camera/mic) requests from the page.
    pub allow_media_capture: bool,
    /// Allow the page's JS geolocation API.
    pub allow_geolocation: bool,
}

impl Default for WebViewOptions {
    fn default() -> Self {
        Self {
            javascript_enabled: true,
            dom_storage: true,
            database: true,
            allow_universal_file_access: true,
            mixed_content: false,
            zoom: false,
            media_playback_requires_gesture: false,
            debuggable: true,
            user_agent_suffix: String::new(),
            external_links_in_browser: false,
            back_navigates_history: true,
            pull_to_refresh: false,
            html_file_input: true,
            allow_media_capture: true,
            allow_geolocation: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct BridgeOptions {
    /// Name of the global JS object (default `Android`).
    pub global_name: String,
    /// Expose the generic java.lang.reflect gateway. This is what makes *every*
    /// Android API reachable from JS, not just the curated modules.
    pub enable_reflection: bool,
    /// Curated modules to expose. Empty = all of them.
    pub modules: Vec<String>,
    /// Log every bridge call to logcat under the tag `Sakiika`.
    pub trace_calls: bool,
}

impl Default for BridgeOptions {
    fn default() -> Self {
        Self {
            global_name: "Android".to_string(),
            enable_reflection: true,
            modules: Vec::new(),
            trace_calls: true,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SigningOptions {
    /// PEM-encoded EC P-256 private key.
    ///
    /// When absent, `sakiika-key.pem` is created in the output folder and reused
    /// on later builds — Android only accepts an update signed by the same
    /// certificate, so the key has to be stable.
    pub key: Option<PathBuf>,
}

/// What the build produces.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum OutputFormat {
    /// A signed APK — installable directly on a device.
    #[serde(rename = "apk")]
    Apk,
    /// A signed Android App Bundle — what Google Play accepts. Not installable
    /// as-is; Play (or bundletool) turns it into APKs.
    #[serde(rename = "aab")]
    Aab,
    #[serde(rename = "both")]
    Both,
}

impl OutputFormat {
    pub fn wants_apk(self) -> bool {
        matches!(self, OutputFormat::Apk | OutputFormat::Both)
    }

    pub fn wants_aab(self) -> bool {
        matches!(self, OutputFormat::Aab | OutputFormat::Both)
    }

    pub fn id(self) -> &'static str {
        match self {
            OutputFormat::Apk => "apk",
            OutputFormat::Aab => "aab",
            OutputFormat::Both => "both",
        }
    }

    pub fn label_ja(self) -> &'static str {
        match self {
            OutputFormat::Apk => "APK（端末に直接インストール）",
            OutputFormat::Aab => "AAB（Google Play へのアップロード用）",
            OutputFormat::Both => "APK と AAB の両方",
        }
    }

    pub fn all() -> &'static [OutputFormat] {
        &[OutputFormat::Apk, OutputFormat::Aab, OutputFormat::Both]
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Orientation {
    #[serde(rename = "unspecified")]
    Unspecified,
    #[serde(rename = "portrait")]
    Portrait,
    #[serde(rename = "landscape")]
    Landscape,
    #[serde(rename = "sensor")]
    Sensor,
}

impl Orientation {
    pub fn manifest_value(self) -> &'static str {
        match self {
            Orientation::Unspecified => "unspecified",
            Orientation::Portrait => "portrait",
            Orientation::Landscape => "landscape",
            Orientation::Sensor => "sensor",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SplashOptions {
    /// When false the launch/splash screen is suppressed as far as the platform
    /// allows: no starting-window preview, zero-length Android 12+ animation, and
    /// the splash view is removed on the first frame.
    pub enabled: bool,
    /// Background of the Android 12+ splash window, when it is enabled.
    pub background: String,
}

impl Default for SplashOptions {
    fn default() -> Self {
        Self {
            enabled: true,
            background: "#1E88E5".to_string(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ThemeMode {
    #[serde(rename = "light")]
    Light,
    #[serde(rename = "dark")]
    Dark,
    /// Follow the system dark-mode setting, and let JS override it at runtime.
    #[serde(rename = "auto")]
    Auto,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AppConfig {
    pub app_name: String,
    pub package_name: String,
    pub version_name: String,
    pub version_code: u32,
    /// Folder holding index.html and friends. Copied wholesale into assets/.
    pub web_root: PathBuf,
    pub entry: String,
    /// apk / aab / both.
    pub output_format: OutputFormat,
    pub min_sdk: u32,
    pub target_sdk: u32,
    pub orientation: Orientation,
    pub theme: ThemeMode,
    pub fullscreen: bool,
    /// Launch/splash screen behaviour. Set `enabled: false` to suppress it.
    pub splash: SplashOptions,
    /// Window and status-bar colour in light mode.
    pub light_background: String,
    /// Window and status-bar colour in dark mode.
    pub dark_background: String,
    /// Optional launcher icon. Any square PNG; scaled copies are not generated,
    /// the single image is used for every density.
    pub icon_png: Option<PathBuf>,
    /// Fills the adaptive icon behind the artwork. White by default so the
    /// built-in icon, whose own edges are white, has no visible seam.
    pub icon_background: String,
    pub permissions: Vec<String>,
    pub file_access: FileAccess,
    pub webview: WebViewOptions,
    pub bridge: BridgeOptions,
    pub signing: SigningOptions,
    /// Where the finished APK lands. Defaults to `<web_root>/../sakiika-out`.
    pub output_dir: Option<PathBuf>,
    /// Build a release-flavoured APK (no debuggable flag, no WebView contents
    /// debugging) — still signed with the same key.
    pub release: bool,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            app_name: "My Web App".to_string(),
            package_name: "com.example.mywebapp".to_string(),
            version_name: "1.0".to_string(),
            version_code: 1,
            web_root: PathBuf::new(),
            entry: "index.html".to_string(),
            output_format: OutputFormat::Apk,
            min_sdk: 26,
            target_sdk: 34,
            orientation: Orientation::Unspecified,
            theme: ThemeMode::Auto,
            fullscreen: false,
            splash: SplashOptions::default(),
            light_background: "#FFFFFF".to_string(),
            dark_background: "#121212".to_string(),
            icon_png: None,
            icon_background: "#FFFFFF".to_string(),
            permissions: vec!["INTERNET".to_string()],
            file_access: FileAccess::AppPrivate,
            webview: WebViewOptions::default(),
            bridge: BridgeOptions::default(),
            signing: SigningOptions::default(),
            output_dir: None,
            release: false,
        }
    }
}

impl AppConfig {
    pub fn load(path: &Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| format!("設定ファイルを読めません {}: {e}", path.display()))?;
        let mut cfg: AppConfig = serde_json::from_str(&text)
            .map_err(|e| format!("設定ファイルの JSON が不正です {}: {e}", path.display()))?;
        // Relative paths in a project file are relative to the file itself,
        // which is what a user dragging the folder around would expect.
        let base = path.parent().unwrap_or(Path::new("."));
        if cfg.web_root.is_relative() {
            cfg.web_root = base.join(&cfg.web_root);
        }
        if let Some(icon) = &cfg.icon_png {
            if icon.is_relative() {
                cfg.icon_png = Some(base.join(icon));
            }
        }
        if let Some(out) = &cfg.output_dir {
            if out.is_relative() {
                cfg.output_dir = Some(base.join(out));
            }
        }
        cfg.validate()?;
        Ok(cfg)
    }

    pub fn save(&self, path: &Path) -> Result<(), String> {
        let text = serde_json::to_string_pretty(self)
            .map_err(|e| format!("設定の直列化に失敗: {e}"))?;
        std::fs::write(path, text)
            .map_err(|e| format!("設定ファイルを書けません {}: {e}", path.display()))
    }

    pub fn validate(&self) -> Result<(), String> {
        if !is_valid_package_name(&self.package_name) {
            return Err(format!(
                "パッケージ名が不正です: '{}' （例: com.example.myapp / 2つ以上の要素・各要素は英小文字で始まる）",
                self.package_name
            ));
        }
        if self.app_name.trim().is_empty() {
            return Err("アプリ名が空です".to_string());
        }
        if self.web_root.as_os_str().is_empty() {
            return Err("HTML フォルダー (webRoot) が指定されていません".to_string());
        }
        if !self.web_root.is_dir() {
            return Err(format!(
                "HTML フォルダーが見つかりません: {}",
                self.web_root.display()
            ));
        }
        if !self.web_root.join(&self.entry).is_file() {
            return Err(format!(
                "エントリ HTML が見つかりません: {}",
                self.web_root.join(&self.entry).display()
            ));
        }
        if self.min_sdk < 26 {
            return Err(
                "minSdk は 26 以上にしてください（アダプティブアイコンと v2 署名の要件）"
                    .to_string(),
            );
        }
        if self.target_sdk < self.min_sdk {
            return Err("targetSdk が minSdk を下回っています".to_string());
        }
        if let Some(icon) = &self.icon_png {
            if !icon.is_file() {
                return Err(format!("アイコン PNG が見つかりません: {}", icon.display()));
            }
        }
        for p in &self.permissions {
            if crate::catalog::find(p).is_none() {
                return Err(format!("未知の権限 ID: {p}（`sakiika permissions` で一覧）"));
            }
        }
        Ok(())
    }

    /// Every permission that ends up in the manifest: user picks plus whatever
    /// the file-access level and enabled bridge modules require.
    pub fn effective_permissions(&self) -> Vec<String> {
        let mut out: Vec<String> = self.permissions.clone();
        for p in self.file_access.implied_permissions() {
            out.push((*p).to_string());
        }
        out.sort();
        out.dedup();
        out
    }

    pub fn resolved_output_dir(&self) -> PathBuf {
        self.output_dir.clone().unwrap_or_else(|| {
            self.web_root
                .parent()
                .unwrap_or(Path::new("."))
                .join("sakiika-out")
        })
    }

    pub fn module_enabled(&self, name: &str) -> bool {
        if name == "fs" && self.file_access == FileAccess::Off {
            return false;
        }
        if self.bridge.modules.is_empty() {
            return true;
        }
        self.bridge.modules.iter().any(|m| m == name)
    }

    pub fn package_dir(&self) -> PathBuf {
        let mut p = PathBuf::new();
        for part in self.package_name.split('.') {
            p.push(part);
        }
        p
    }
}

pub fn is_valid_package_name(name: &str) -> bool {
    let parts: Vec<&str> = name.split('.').collect();
    if parts.len() < 2 {
        return false;
    }
    parts.iter().all(|part| {
        !part.is_empty()
            && part.chars().next().is_some_and(|c| c.is_ascii_lowercase())
            && part
                .chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
            && !JAVA_KEYWORDS.contains(part)
    })
}

const JAVA_KEYWORDS: &[&str] = &[
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while",
];
