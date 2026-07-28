//! Builds `AndroidManifest.xml` for a project, as binary AXML.
//!
//! Resource references (icon, theme) point into the prebuilt template's resource
//! table, so their numeric IDs are recorded when the template is built rather
//! than being resolved by name here.

use crate::axml::{attr, config_changes, orientation, Attr, Element, Value};
use crate::catalog;
use crate::config::{AppConfig, FileAccess, Orientation, ThemeMode};
use serde::{Deserialize, Serialize};

/// `@android:style/Theme.Translucent.NoTitleBar`. Framework-public style IDs
/// are stable across every Android version, so the reference needs nothing from
/// the template's resource table.
const THEME_TRANSLUCENT_NO_TITLE_BAR: u32 = 0x0103_0010;

/// Resource IDs inside the template APK, produced alongside it at template build
/// time. Without these the manifest could not reference the icon or a theme.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TemplateIds {
    pub icon: u32,
    pub theme_auto: u32,
    pub theme_light: u32,
    pub theme_dark: u32,
    pub theme_auto_no_splash: u32,
    pub theme_light_no_splash: u32,
    pub theme_dark_no_splash: u32,

    /// Zip entry names of the icon layers, as aapt2 actually wrote them.
    ///
    /// aapt2 appends a version qualifier (`mipmap-xxhdpi` becomes
    /// `mipmap-xxhdpi-v4`), so guessing the path would add a second, unreferenced
    /// copy instead of replacing the layer the resource table points at.
    #[serde(default)]
    pub icon_background_entry: String,
    #[serde(default)]
    pub icon_foreground_entry: String,
    #[serde(default)]
    pub icon_legacy_entry: String,
}

impl TemplateIds {
    /// Picks the style that matches the theme and splash settings.
    pub fn theme_for(&self, theme: ThemeMode, splash: bool) -> u32 {
        match (theme, splash) {
            (ThemeMode::Auto, true) => self.theme_auto,
            (ThemeMode::Auto, false) => self.theme_auto_no_splash,
            (ThemeMode::Light, true) => self.theme_light,
            (ThemeMode::Light, false) => self.theme_light_no_splash,
            (ThemeMode::Dark, true) => self.theme_dark,
            (ThemeMode::Dark, false) => self.theme_dark_no_splash,
        }
    }
}

/// Java package the prebuilt runtime classes live in. Independent of the app's
/// package name, which is why one template serves every project.
pub const RUNTIME_PACKAGE: &str = "net.sakiika.runtime";

const CONFIG_CHANGES: u32 = config_changes::ORIENTATION
    | config_changes::SCREEN_SIZE
    | config_changes::SCREEN_LAYOUT
    | config_changes::SMALLEST_SCREEN_SIZE
    | config_changes::KEYBOARD_HIDDEN
    | config_changes::UI_MODE
    | config_changes::DENSITY
    | config_changes::LOCALE
    | config_changes::FONT_SCALE;

const LAUNCH_MODE_SINGLE_TOP: i32 = 1;

pub fn build(cfg: &AppConfig, ids: &TemplateIds) -> Element {
    let mut manifest = Element::new("manifest")
        .attr(Attr::plain("package", Value::Str(cfg.package_name.clone())))
        .attr(Attr::android(
            "versionCode",
            attr::VERSION_CODE,
            Value::Int(cfg.version_code as i32),
        ))
        .attr(Attr::android(
            "versionName",
            attr::VERSION_NAME,
            Value::Str(cfg.version_name.clone()),
        ))
        .child(
            Element::new("uses-sdk")
                .attr(Attr::android(
                    "minSdkVersion",
                    attr::MIN_SDK_VERSION,
                    Value::Int(cfg.min_sdk as i32),
                ))
                .attr(Attr::android(
                    "targetSdkVersion",
                    attr::TARGET_SDK_VERSION,
                    Value::Int(cfg.target_sdk as i32),
                )),
        );

    for id in cfg.effective_permissions() {
        let Some(permission) = catalog::find(&id) else { continue };
        if permission.min_sdk > 0 && cfg.target_sdk < permission.min_sdk {
            // Declaring a permission the target platform predates is noise.
            continue;
        }
        let mut element = Element::new("uses-permission").attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str(permission.manifest.to_string()),
        ));
        if permission.max_sdk > 0 {
            element = element.attr(Attr::android(
                "maxSdkVersion",
                attr::MAX_SDK_VERSION,
                Value::Int(permission.max_sdk as i32),
            ));
        }
        manifest = manifest.child(element);
    }

    for feature in [
        "android.hardware.camera",
        "android.hardware.location.gps",
        "android.hardware.sensor.accelerometer",
        "android.hardware.microphone",
    ] {
        manifest = manifest.child(
            Element::new("uses-feature")
                .attr(Attr::android("name", attr::NAME, Value::Str(feature.to_string())))
                .attr(Attr::android("required", attr::REQUIRED, Value::Bool(false))),
        );
    }

    // Android 11+ hides other apps unless the app holds QUERY_ALL_PACKAGES or
    // declares what it intends to look for.
    if !cfg.permissions.iter().any(|p| p == "QUERY_ALL_PACKAGES") {
        manifest = manifest.child(queries_element());
    }

    manifest.child(application_element(cfg, ids))
}

fn queries_element() -> Element {
    let view_https = Element::new("intent")
        .child(Element::new("action").attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str("android.intent.action.VIEW".to_string()),
        )))
        .child(Element::new("category").attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str("android.intent.category.BROWSABLE".to_string()),
        )))
        .child(Element::new("data").attr(Attr::android(
            "scheme",
            attr::SCHEME,
            Value::Str("https".to_string()),
        )));

    let send_any = Element::new("intent")
        .child(Element::new("action").attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str("android.intent.action.SEND".to_string()),
        )))
        .child(Element::new("data").attr(Attr::android(
            "mimeType",
            attr::MIME_TYPE,
            Value::Str("*/*".to_string()),
        )));

    let capture = Element::new("intent").child(Element::new("action").attr(Attr::android(
        "name",
        attr::NAME,
        Value::Str("android.media.action.IMAGE_CAPTURE".to_string()),
    )));

    let dial = Element::new("intent").child(Element::new("action").attr(Attr::android(
        "name",
        attr::NAME,
        Value::Str("android.intent.action.DIAL".to_string()),
    )));

    Element::new("queries")
        .child(view_https)
        .child(send_any)
        .child(capture)
        .child(dial)
}

fn application_element(cfg: &AppConfig, ids: &TemplateIds) -> Element {
    let mut application = Element::new("application")
        // Loads the runtime settings before any provider or activity exists.
        .attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str(format!("{RUNTIME_PACKAGE}.SakiikaApplication")),
        ))
        // The label is written inline rather than as @string/app_name so the
        // template's resource table never has to change.
        .attr(Attr::android("label", attr::LABEL, Value::Str(cfg.app_name.clone())))
        .attr(Attr::android("icon", attr::ICON, Value::Ref(ids.icon)))
        .attr(Attr::android("roundIcon", attr::ROUND_ICON, Value::Ref(ids.icon)))
        .attr(Attr::android(
            "theme",
            attr::THEME,
            Value::Ref(ids.theme_for(cfg.theme, cfg.splash.enabled)),
        ))
        .attr(Attr::android(
            "hardwareAccelerated",
            attr::HARDWARE_ACCELERATED,
            Value::Bool(true),
        ))
        .attr(Attr::android("allowBackup", attr::ALLOW_BACKUP, Value::Bool(true)))
        .attr(Attr::android("supportsRtl", attr::SUPPORTS_RTL, Value::Bool(true)))
        .attr(Attr::android(
            "extractNativeLibs",
            attr::EXTRACT_NATIVE_LIBS,
            Value::Bool(false),
        ))
        .attr(Attr::android(
            "usesCleartextTraffic",
            attr::USES_CLEARTEXT_TRAFFIC,
            Value::Bool(cfg.webview.mixed_content),
        ))
        .attr(Attr::android(
            "resizeableActivity",
            attr::RESIZEABLE_ACTIVITY,
            Value::Bool(true),
        ));

    // Keeps java.io.File working on Android 10 for a file-manager-grade app.
    if cfg.file_access == FileAccess::FullManager {
        application = application
            .attr(Attr::android(
                "requestLegacyExternalStorage",
                attr::REQUEST_LEGACY_EXTERNAL_STORAGE,
                Value::Bool(true),
            ))
            .attr(Attr::android(
                "preserveLegacyExternalStorage",
                attr::PRESERVE_LEGACY_EXTERNAL_STORAGE,
                Value::Bool(true),
            ));
    }

    let screen_orientation = match cfg.orientation {
        Orientation::Unspecified => orientation::UNSPECIFIED,
        Orientation::Portrait => orientation::PORTRAIT,
        Orientation::Landscape => orientation::LANDSCAPE,
        Orientation::Sensor => orientation::SENSOR,
    };

    // With the splash disabled, a translucent activity is the strongest
    // suppression available: the OS shows neither the Android 12+ system splash
    // nor the legacy starting-window preview for translucent windows. Android
    // 8.0/8.1 throw "Only fullscreen opaque activities can request orientation"
    // for a translucent activity with a fixed orientation, so the swap is
    // limited to builds that cannot run there or do not fix the orientation;
    // everything else falls back to the NoSplash theme's zero-duration splash.
    let translucent = !cfg.splash.enabled
        && (cfg.min_sdk >= 28
            || matches!(cfg.orientation, Orientation::Unspecified | Orientation::Sensor));

    let mut activity = Element::new("activity")
        // Fully qualified: the runtime classes live in their own package, not the
        // app's, so a leading-dot shorthand would resolve to the wrong name.
        .attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str(format!("{RUNTIME_PACKAGE}.MainActivity")),
        ))
        .attr(Attr::android("exported", attr::EXPORTED, Value::Bool(true)))
        .attr(Attr::android(
            "launchMode",
            attr::LAUNCH_MODE,
            Value::Int(LAUNCH_MODE_SINGLE_TOP),
        ))
        .attr(Attr::android(
            "screenOrientation",
            attr::SCREEN_ORIENTATION,
            Value::Int(screen_orientation),
        ))
        .attr(Attr::android(
            "configChanges",
            attr::CONFIG_CHANGES,
            Value::Hex(CONFIG_CHANGES),
        ))
        .child(
            Element::new("intent-filter")
                .child(Element::new("action").attr(Attr::android(
                    "name",
                    attr::NAME,
                    Value::Str("android.intent.action.MAIN".to_string()),
                )))
                .child(Element::new("category").attr(Attr::android(
                    "name",
                    attr::NAME,
                    Value::Str("android.intent.category.LAUNCHER".to_string()),
                ))),
        );

    if translucent {
        activity = activity.attr(Attr::android(
            "theme",
            attr::THEME,
            Value::Ref(THEME_TRANSLUCENT_NO_TITLE_BAR),
        ));
    }

    let provider = Element::new("provider")
        .attr(Attr::android(
            "name",
            attr::NAME,
            Value::Str(format!("{RUNTIME_PACKAGE}.ShareProvider")),
        ))
        .attr(Attr::android(
            "authorities",
            attr::AUTHORITIES,
            Value::Str(format!("{}.share", cfg.package_name)),
        ))
        .attr(Attr::android("exported", attr::EXPORTED, Value::Bool(false)))
        .attr(Attr::android(
            "grantUriPermissions",
            attr::GRANT_URI_PERMISSIONS,
            Value::Bool(true),
        ));

    application.child(activity).child(provider)
}

pub fn encode(cfg: &AppConfig, ids: &TemplateIds) -> Result<Vec<u8>, String> {
    crate::axml::encode(&build(cfg, ids))
}
