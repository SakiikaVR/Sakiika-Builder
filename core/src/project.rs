//! Two jobs:
//!
//! * `generate_template` lays out the one-off Android project that becomes the
//!   prebuilt template APK. This runs on a developer machine with the Android
//!   SDK, never on a user's machine.
//! * `runtime_config_json` and `collect_assets` produce the per-project pieces
//!   that get dropped into a copy of that template at build time.

use crate::catalog;
use crate::config::{AppConfig, ThemeMode};
use crate::manifest::RUNTIME_PACKAGE;
use crate::png::{self, Rgba};
use serde_json::json;
use std::fs;
use std::path::{Path, PathBuf};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Adaptive icon layers are 108dp; 432px covers xxhdpi without being wasteful.
const ICON_SIZE: u32 = 432;

/// Java that is always part of the template. Every module ships in the dex and
/// is switched on or off at run time, because the dex is compiled once.
const JAVA_SOURCES: &[(&str, &str)] = &[
    ("Cfg.java", include_str!("../templates/java/Cfg.java")),
    ("SakiikaApplication.java", include_str!("../templates/java/SakiikaApplication.java")),
    ("Jsonx.java", include_str!("../templates/java/Jsonx.java")),
    ("BridgeError.java", include_str!("../templates/java/BridgeError.java")),
    ("ApiModule.java", include_str!("../templates/java/ApiModule.java")),
    ("Bridge.java", include_str!("../templates/java/Bridge.java")),
    ("MainActivity.java", include_str!("../templates/java/MainActivity.java")),
    ("ShareProvider.java", include_str!("../templates/java/ShareProvider.java")),
    ("Mime.java", include_str!("../templates/java/Mime.java")),
    ("SysApi.java", include_str!("../templates/java/SysApi.java")),
    ("UiApi.java", include_str!("../templates/java/UiApi.java")),
    ("PermApi.java", include_str!("../templates/java/PermApi.java")),
    ("FsApi.java", include_str!("../templates/java/FsApi.java")),
    ("PrefsApi.java", include_str!("../templates/java/PrefsApi.java")),
    ("ClipApi.java", include_str!("../templates/java/ClipApi.java")),
    ("NetApi.java", include_str!("../templates/java/NetApi.java")),
    ("IntentApi.java", include_str!("../templates/java/IntentApi.java")),
    ("SensorApi.java", include_str!("../templates/java/SensorApi.java")),
    ("LocationApi.java", include_str!("../templates/java/LocationApi.java")),
    ("MediaApi.java", include_str!("../templates/java/MediaApi.java")),
    ("NotifyApi.java", include_str!("../templates/java/NotifyApi.java")),
    ("ContentApi.java", include_str!("../templates/java/ContentApi.java")),
    ("PkgApi.java", include_str!("../templates/java/PkgApi.java")),
    ("BiometricApi.java", include_str!("../templates/java/BiometricApi.java")),
    ("ReflectApi.java", include_str!("../templates/java/ReflectApi.java")),
];

const BRIDGE_JS: &str = include_str!("../templates/bridge.js");

/// Marker that stops a second build from injecting the script tag twice.
const INJECT_MARKER: &str = "sakiika-bridge";

/// Where the bridge and the settings land inside `assets/`.
pub const ASSET_DIR: &str = "__sakiika";

pub struct TemplateLayout {
    pub work_dir: PathBuf,
    pub java_dir: PathBuf,
    pub res_dir: PathBuf,
    pub gen_dir: PathBuf,
    pub classes_dir: PathBuf,
    pub dex_dir: PathBuf,
    pub manifest: PathBuf,
}

fn write(path: &Path, contents: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("フォルダーを作れません {}: {e}", parent.display()))?;
    }
    fs::write(path, contents).map_err(|e| format!("書き込めません {}: {e}", path.display()))
}

fn write_bytes(path: &Path, contents: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("フォルダーを作れません {}: {e}", parent.display()))?;
    }
    fs::write(path, contents).map_err(|e| format!("書き込めません {}: {e}", path.display()))
}

fn xml_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(c),
        }
    }
    out
}

// ------------------------------------------------------------ template project

/// The template's own package/version. None of it reaches a built app: the
/// manifest is rewritten per project, so these only matter while compiling.
const TEMPLATE_MIN_SDK: u32 = 26;
const TEMPLATE_TARGET_SDK: u32 = 34;

pub fn generate_template(work_dir: &Path) -> Result<TemplateLayout, String> {
    if work_dir.exists() {
        fs::remove_dir_all(work_dir)
            .map_err(|e| format!("作業フォルダーを消せません {}: {e}", work_dir.display()))?;
    }
    let java_dir = work_dir.join("java");
    let res_dir = work_dir.join("res");
    let gen_dir = work_dir.join("gen");
    let classes_dir = work_dir.join("classes");
    let dex_dir = work_dir.join("dex");
    for d in [&java_dir, &res_dir, &gen_dir, &classes_dir, &dex_dir] {
        fs::create_dir_all(d)
            .map_err(|e| format!("フォルダーを作れません {}: {e}", d.display()))?;
    }

    let mut pkg_dir = java_dir.clone();
    for part in RUNTIME_PACKAGE.split('.') {
        pkg_dir.push(part);
    }
    fs::create_dir_all(&pkg_dir)
        .map_err(|e| format!("パッケージフォルダーを作れません {}: {e}", pkg_dir.display()))?;
    for (file, source) in JAVA_SOURCES {
        write(&pkg_dir.join(file), &source.replace("__PKG__", RUNTIME_PACKAGE))?;
    }

    write_template_resources(&res_dir)?;

    let manifest = work_dir.join("AndroidManifest.xml");
    write(&manifest, &template_manifest_xml())?;

    Ok(TemplateLayout {
        work_dir: work_dir.to_path_buf(),
        java_dir,
        res_dir,
        gen_dir,
        classes_dir,
        dex_dir,
        manifest,
    })
}

/// A manifest just complete enough for aapt2 to build the resource table. The
/// real one is generated per project by `manifest.rs`.
fn template_manifest_xml() -> String {
    format!(
        r#"<?xml version="1.0" encoding="utf-8"?>
<!-- Template only. Every built app gets a freshly generated manifest. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{RUNTIME_PACKAGE}"
    android:versionCode="1"
    android:versionName="{VERSION}">
    <application
        android:name=".SakiikaApplication"
        android:label="Sakiika Runtime"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/AppTheme">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <provider
            android:name=".ShareProvider"
            android:authorities="{RUNTIME_PACKAGE}.share"
            android:exported="false"
            android:grantUriPermissions="true" />
    </application>
</manifest>
"#
    )
}

/// The six themes a project can pick between, plus the icon layers.
///
/// The set is fixed because the resource table ships prebuilt: a project chooses
/// a theme by referencing one of these IDs from its manifest rather than by
/// having new resources compiled.
fn write_template_resources(res_dir: &Path) -> Result<(), String> {
    const LIGHT_PARENT: &str = "@android:style/Theme.Material.Light.NoActionBar";
    const DARK_PARENT: &str = "@android:style/Theme.Material.NoActionBar";

    // (style name, parent in `values`, parent in `values-night`, light bars in
    // `values`, light bars in `values-night`, splash on)
    let variants: [(&str, &str, &str, bool, bool, bool); 6] = [
        ("AppTheme", LIGHT_PARENT, DARK_PARENT, true, false, true),
        ("AppThemeLight", LIGHT_PARENT, LIGHT_PARENT, true, true, true),
        ("AppThemeDark", DARK_PARENT, DARK_PARENT, false, false, true),
        ("AppThemeNoSplash", LIGHT_PARENT, DARK_PARENT, true, false, false),
        ("AppThemeLightNoSplash", LIGHT_PARENT, LIGHT_PARENT, true, true, false),
        ("AppThemeDarkNoSplash", DARK_PARENT, DARK_PARENT, false, false, false),
    ];

    for (folder, night, v31) in [
        ("values", false, false),
        ("values-night", true, false),
        ("values-v31", false, true),
        ("values-night-v31", true, true),
    ] {
        let mut xml = String::from("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n");
        for (name, day_parent, night_parent, day_light, night_light, splash) in variants {
            let parent = if night { night_parent } else { day_parent };
            let light_bars = if night { night_light } else { day_light };
            xml.push_str(&format!(
                "    <style name=\"{name}\" parent=\"{parent}\">\n"
            ));
            xml.push_str(
                "        <item name=\"android:windowBackground\">@color/window_bg</item>\n",
            );
            xml.push_str(&format!(
                "        <item name=\"android:windowLightStatusBar\">{light_bars}</item>\n"
            ));
            if !splash {
                // Removes the pre-Android-12 starting window outright.
                xml.push_str(
                    "        <item name=\"android:windowDisablePreview\">true</item>\n",
                );
                if v31 {
                    xml.push_str("        <item name=\"android:windowSplashScreenAnimationDuration\">0</item>\n");
                    xml.push_str("        <item name=\"android:windowSplashScreenBackground\">@color/window_bg</item>\n");
                }
            } else if v31 {
                xml.push_str("        <item name=\"android:windowSplashScreenBackground\">@color/splash_bg</item>\n");
            }
            xml.push_str("    </style>\n");
        }
        xml.push_str("</resources>\n");
        write(&res_dir.join(folder).join("themes.xml"), &xml)?;
    }

    // Colours are fixed in the template. A project's own colours are applied at
    // run time to the WebView, status bar and navigation bar; only the very first
    // pre-draw frame uses these, and with the splash disabled it never shows.
    write(
        &res_dir.join("values").join("colors.xml"),
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    \
         <color name=\"window_bg\">#FFFFFFFF</color>\n    \
         <color name=\"splash_bg\">#FF1E88E5</color>\n</resources>\n",
    )?;
    write(
        &res_dir.join("values-night").join("colors.xml"),
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    \
         <color name=\"window_bg\">#FF121212</color>\n</resources>\n",
    )?;

    // Icon layers are bitmaps so a project can swap them by replacing a zip
    // entry — no resource table changes needed.
    write(
        &res_dir.join("mipmap-anydpi-v26").join("ic_launcher.xml"),
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n\
         <adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    \
         <background android:drawable=\"@mipmap/ic_launcher_bg\" />\n    \
         <foreground android:drawable=\"@mipmap/ic_launcher_fg\" />\n\
         </adaptive-icon>\n",
    )?;

    // Placeholders only: every build replaces all three layers, but they have to
    // exist as resource entries for aapt2 to register them.
    write_bytes(
        &res_dir.join("mipmap-xxhdpi").join("ic_launcher_bg.png"),
        &png::solid(ICON_SIZE, Rgba::new(0xff, 0xff, 0xff, 0xff))?,
    )?;
    write_bytes(
        &res_dir.join("mipmap-xxhdpi").join("ic_launcher_fg.png"),
        png::default_foreground(),
    )?;
    write_bytes(
        &res_dir.join("mipmap-xxhdpi").join("ic_launcher.png"),
        png::default_legacy(),
    )?;
    write(
        &res_dir.join("values").join("strings.xml"),
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    \
         <string name=\"app_name\">Sakiika Runtime</string>\n</resources>\n",
    )?;
    let _ = xml_escape("");
    Ok(())
}

/// Regenerates the icon layers for a project's colours, or wraps a supplied PNG.
///
/// Entry names come from `ids` rather than being constructed here: aapt2 rewrites
/// `mipmap-xxhdpi` to `mipmap-xxhdpi-v4`, and writing to the un-suffixed path
/// would append an unreferenced second copy instead of replacing the layer.
pub fn icon_layers(
    cfg: &AppConfig,
    ids: &crate::manifest::TemplateIds,
) -> Result<Vec<(String, Vec<u8>)>, String> {
    let background = Rgba::parse(&cfg.icon_background, Rgba::new(0xff, 0xff, 0xff, 0xff));
    for (label, name) in [
        ("背景", &ids.icon_background_entry),
        ("前景", &ids.icon_foreground_entry),
        ("旧形式", &ids.icon_legacy_entry),
    ] {
        if name.is_empty() {
            return Err(format!(
                "テンプレート情報にアイコン({label})のエントリ名がありません。\
                 `sakiika devtemplate` でテンプレートを作り直してください。"
            ));
        }
    }

    let mut out = vec![(
        ids.icon_background_entry.clone(),
        png::solid(ICON_SIZE, background)?,
    )];

    match &cfg.icon_png {
        Some(path) => {
            let bytes = fs::read(path)
                .map_err(|e| format!("アイコンを読めません {}: {e}", path.display()))?;
            if !bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
                return Err(format!(
                    "アイコンは PNG 画像を指定してください: {}",
                    path.display()
                ));
            }
            out.push((ids.icon_foreground_entry.clone(), bytes.clone()));
            out.push((ids.icon_legacy_entry.clone(), bytes));
        }
        None => {
            out.push((
                ids.icon_foreground_entry.clone(),
                png::default_foreground().to_vec(),
            ));
            out.push((ids.icon_legacy_entry.clone(), png::default_legacy().to_vec()));
        }
    }
    Ok(out)
}

// -------------------------------------------------------------- runtime config

/// The settings the app reads at startup. Replaces the compiled-in constants a
/// per-project Java build used to produce.
pub fn runtime_config_json(cfg: &AppConfig) -> String {
    let permissions: Vec<&str> = cfg
        .effective_permissions()
        .iter()
        .filter_map(|id| catalog::find(id).map(|p| p.manifest))
        .collect();
    let theme = match cfg.theme {
        ThemeMode::Light => "light",
        ThemeMode::Dark => "dark",
        ThemeMode::Auto => "auto",
    };
    let w = &cfg.webview;
    let value = json!({
        "builderVersion": VERSION,
        "appName": cfg.app_name,
        "versionName": cfg.version_name,
        "versionCode": cfg.version_code,
        "entry": cfg.entry,
        "fileAccess": cfg.file_access.as_java_enum(),
        "reflection": cfg.bridge.enable_reflection,
        "trace": cfg.bridge.trace_calls && !cfg.release,
        "declaredPermissions": permissions,
        "modules": enabled_modules(cfg),
        "theme": theme,
        "fullscreen": cfg.fullscreen,
        "splash": cfg.splash.enabled,
        "lightBackground": normalize_color(&cfg.light_background, "#FFFFFFFF"),
        "darkBackground": normalize_color(&cfg.dark_background, "#FF121212"),
        "webview": {
            "javascriptEnabled": w.javascript_enabled,
            "domStorage": w.dom_storage,
            "database": w.database,
            "allowUniversalFileAccess": w.allow_universal_file_access,
            "mixedContent": w.mixed_content,
            "zoom": w.zoom,
            "mediaPlaybackRequiresGesture": w.media_playback_requires_gesture,
            "debuggable": w.debuggable && !cfg.release,
            "userAgentSuffix": w.user_agent_suffix,
            "externalLinksInBrowser": w.external_links_in_browser,
            "backNavigatesHistory": w.back_navigates_history,
            "pullToRefresh": w.pull_to_refresh,
            "htmlFileInput": w.html_file_input,
            "allowMediaCapture": w.allow_media_capture,
            "allowGeolocation": w.allow_geolocation,
        }
    });
    serde_json::to_string_pretty(&value).unwrap_or_else(|_| "{}".to_string())
}

/// Normalises `#RGB` / `RRGGBB` / `#AARRGGBB` into the `#AARRGGBB` Android wants.
fn normalize_color(input: &str, fallback: &str) -> String {
    let hex: String = input
        .trim()
        .trim_start_matches('#')
        .chars()
        .filter(|c| c.is_ascii_hexdigit())
        .collect();
    let expanded = match hex.len() {
        3 => hex.chars().flat_map(|c| [c, c]).collect::<String>(),
        6 => hex.clone(),
        8 => return format!("#{}", hex.to_uppercase()),
        _ => return fallback.to_string(),
    };
    format!("#FF{}", expanded.to_uppercase())
}

pub fn enabled_modules(cfg: &AppConfig) -> Vec<String> {
    catalog::all_module_names()
        .into_iter()
        .filter(|name| {
            if *name == "reflect" && !cfg.bridge.enable_reflection {
                return false;
            }
            cfg.module_enabled(name)
        })
        .map(|s| s.to_string())
        .collect()
}

// --------------------------------------------------------------------- assets

pub struct Asset {
    /// Zip entry name, always with forward slashes.
    pub name: String,
    pub data: Vec<u8>,
}

#[derive(Default)]
pub struct AssetStats {
    pub html_patched: usize,
    pub files: usize,
    pub bytes: u64,
}

/// The bridge, the settings and every file from the HTML folder, ready to be
/// added to the APK under `assets/`.
pub fn collect_assets(cfg: &AppConfig, stats: &mut AssetStats) -> Result<Vec<Asset>, String> {
    let mut out = Vec::new();
    out.push(Asset {
        name: format!("assets/{ASSET_DIR}/bridge.js"),
        data: BRIDGE_JS
            .replace("__GLOBAL__", &cfg.bridge.global_name)
            .into_bytes(),
    });
    out.push(Asset {
        name: format!("assets/{ASSET_DIR}/config.json"),
        data: runtime_config_json(cfg).into_bytes(),
    });
    walk_web_root(&cfg.web_root, "assets", &mut out, stats)?;
    Ok(out)
}

fn walk_web_root(
    dir: &Path,
    prefix: &str,
    out: &mut Vec<Asset>,
    stats: &mut AssetStats,
) -> Result<(), String> {
    let entries = fs::read_dir(dir)
        .map_err(|e| format!("フォルダーを読めません {}: {e}", dir.display()))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("読み取りエラー: {e}"))?;
        let name = entry.file_name().to_string_lossy().to_string();
        // Things that only matter to a source tree, not to a packaged app.
        if matches!(
            name.as_str(),
            "node_modules" | ".git" | ".svn" | ASSET_DIR | ".DS_Store" | "sakiika.json"
                | "apkforge.json" | "sakiika-out" | "target" | "Thumbs.db"
        ) {
            continue;
        }
        let path = entry.path();
        let child_name = format!("{prefix}/{name}");
        if path.is_dir() {
            walk_web_root(&path, &child_name, out, stats)?;
            continue;
        }
        let mut data =
            fs::read(&path).map_err(|e| format!("読めません {}: {e}", path.display()))?;
        let lower = name.to_ascii_lowercase();
        if lower.ends_with(".html") || lower.ends_with(".htm") {
            let (patched, changed) = inject_bridge(&data);
            data = patched;
            if changed {
                stats.html_patched += 1;
            }
        }
        stats.files += 1;
        stats.bytes += data.len() as u64;
        out.push(Asset { name: child_name, data });
    }
    Ok(())
}

/// Inserts the bridge `<script>` into an HTML document.
///
/// Works on bytes so an unusual encoding or a BOM survives untouched; the tag is
/// pure ASCII and goes immediately after `<head…>`, or at the very top when there
/// is no head element.
fn inject_bridge(bytes: &[u8]) -> (Vec<u8>, bool) {
    let haystack = String::from_utf8_lossy(bytes).to_ascii_lowercase();
    if haystack.contains(INJECT_MARKER) {
        return (bytes.to_vec(), false);
    }
    let tag = format!(
        "<script src=\"file:///android_asset/{ASSET_DIR}/bridge.js\" id=\"{INJECT_MARKER}\"></script>\n"
    );

    let insert_at = match haystack.find("<head") {
        Some(head_start) => match haystack[head_start..].find('>') {
            Some(offset) => head_start + offset + 1,
            None => 0,
        },
        None => match haystack.find("<html") {
            Some(html_start) => match haystack[html_start..].find('>') {
                Some(offset) => html_start + offset + 1,
                None => 0,
            },
            None => 0,
        },
    };
    // from_utf8_lossy can shift indices on invalid input; clamp to be safe.
    let insert_at = insert_at.min(bytes.len());

    let mut out = Vec::with_capacity(bytes.len() + tag.len());
    out.extend_from_slice(&bytes[..insert_at]);
    out.extend_from_slice(tag.as_bytes());
    out.extend_from_slice(&bytes[insert_at..]);
    (out, true)
}

/// SDK levels the template was compiled against, reported by `doctor`.
pub fn template_sdk_range() -> (u32, u32) {
    (TEMPLATE_MIN_SDK, TEMPLATE_TARGET_SDK)
}
