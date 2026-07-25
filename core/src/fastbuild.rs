//! The build users actually run. No JDK, no Android SDK, no external process.
//!
//! Everything expensive was done once, at template build time: the Java runtime
//! is already compiled to `classes.dex` and the resource table is already
//! packed. A build here is
//!
//! 1. read the embedded template APK,
//! 2. write a fresh `AndroidManifest.xml` (package, label, permissions, theme),
//! 3. drop in the HTML folder plus the runtime settings as assets,
//! 4. swap the icon layers,
//! 5. write the archive with 4-byte alignment and sign it.
//!
//! which is why it finishes in a fraction of a second.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

use crate::aab;
use crate::apk::{self, Entry};
use crate::config::{AppConfig, OutputFormat};
use crate::manifest::{self, TemplateIds};
use crate::project::{self, AssetStats};
use crate::sign::{self, Signer, MIN_SDK_FOR_V2_ONLY};

/// The prebuilt runtime, embedded at compile time.
///
/// Produced by `sakiika devtemplate` on a machine that has the Android SDK. An
/// empty file means the tool was compiled without it.
const TEMPLATE_APK: &[u8] = include_bytes!("../prebuilt/template.apk");
/// Protobuf resource table and `res/**`, needed only for AAB output.
const TEMPLATE_PROTO: &[u8] = include_bytes!("../prebuilt/template-proto.zip");
const TEMPLATE_IDS: &str = include_str!("../prebuilt/template-ids.json");

/// Adaptive icons are the only icon format here, and they start at API 26.
pub const MIN_SDK: u32 = 26;

pub struct Report {
    /// Present unless the project asked for AAB output only.
    pub apk: Option<PathBuf>,
    pub apk_size_bytes: u64,
    /// Present when the project asked for AAB output.
    pub aab: Option<PathBuf>,
    pub aab_size_bytes: u64,
    pub key: PathBuf,
    pub certificate_fingerprint: String,
    pub elapsed_ms: u128,
    pub entry_count: usize,
    pub modules: Vec<String>,
    pub permissions: Vec<String>,
    pub html_files_patched: usize,
    pub asset_files: usize,
}

impl Report {
    /// The artifact to talk about first: the APK if there is one, else the AAB.
    pub fn primary(&self) -> &PathBuf {
        self.apk.as_ref().or(self.aab.as_ref()).expect("成果物がありません")
    }
}

/// Anything that wants to show progress: the CLI prints, the GUI streams.
pub trait Progress {
    fn step(&mut self, name: &str, detail: &str);
    fn log(&mut self, line: &str);
}

pub struct StdoutProgress {
    pub verbose: bool,
}

impl Progress for StdoutProgress {
    fn step(&mut self, name: &str, detail: &str) {
        println!("[{name}] {detail}");
    }

    fn log(&mut self, line: &str) {
        if self.verbose {
            println!("    {line}");
        }
    }
}

pub fn template_is_embedded() -> bool {
    !TEMPLATE_APK.is_empty()
}

pub fn template_ids() -> Result<TemplateIds, String> {
    if !template_is_embedded() {
        return Err(missing_template_message());
    }
    serde_json::from_str(TEMPLATE_IDS)
        .map_err(|e| format!("埋め込まれたテンプレート情報が壊れています: {e}"))
}

fn missing_template_message() -> String {
    concat!(
        "ランタイムテンプレートが埋め込まれていません。\n",
        "  開発者向け: Android SDK と JDK のある環境で次を実行してから、もう一度ビルドしてください。\n",
        "    sakiika devtemplate\n",
        "    cargo build --release\n",
        "  配布物を使っている場合は、テンプレート同梱版を入手してください。"
    )
    .to_string()
}

pub fn build(
    cfg: &AppConfig,
    progress: &mut dyn Progress,
) -> Result<Report, String> {
    let started = Instant::now();
    if !template_is_embedded() {
        return Err(missing_template_message());
    }
    if cfg.min_sdk < MIN_SDK {
        return Err(format!(
            "minSdk は {MIN_SDK} 以上にしてください（現在 {}）。\n\
             アダプティブアイコンが Android 8.0 以降、v2 署名が Android {}.0 以降を必要とします。",
            cfg.min_sdk, MIN_SDK_FOR_V2_ONLY
        ));
    }
    let ids = template_ids()?;
    let format = cfg.output_format;
    let total_steps = if format == OutputFormat::Both { 6 } else { 5 };

    let out_dir = cfg.resolved_output_dir();
    fs::create_dir_all(&out_dir)
        .map_err(|e| format!("出力フォルダーを作れません {}: {e}", out_dir.display()))?;
    let key_path = match &cfg.signing.key {
        Some(path) => path.clone(),
        None => out_dir.join("sakiika-key.pem"),
    };
    let signer = Signer::load_or_create(&key_path, &certificate_subject(cfg))?;
    let base_name = format!(
        "{}-{}{}",
        sanitize_file_name(&cfg.app_name),
        cfg.version_name,
        if cfg.release { "" } else { "-debug" }
    );

    progress.step(&format!("1/{total_steps}"), "テンプレートを展開");
    let template = apk::Template::read(TEMPLATE_APK)?;
    let mut builder = apk::Builder::new();
    for entry in &template.entries {
        // The manifest is regenerated and the template carries no signature, but
        // guard against both so a re-run can never inherit stale metadata.
        if entry.name == "AndroidManifest.xml" || is_signature_entry(&entry.name) {
            continue;
        }
        builder.add(clone_entry(entry));
    }
    if !builder.contains("classes.dex") {
        return Err("テンプレートに classes.dex がありません。作り直してください。".to_string());
    }
    progress.log(&format!("テンプレート {} エントリ", builder.len()));

    progress.step(&format!("2/{total_steps}"), "マニフェストを生成");
    let axml = manifest::encode(cfg, &ids)?;
    builder.add(Entry::new("AndroidManifest.xml", axml));

    progress.step(&format!("3/{total_steps}"), "HTML と設定を格納");
    let mut stats = AssetStats::default();
    let assets = project::collect_assets(cfg, &mut stats)?;
    for asset in assets {
        builder.add(Entry::new(asset.name, asset.data));
    }
    progress.log(&format!(
        "アセット {} ファイル / {:.1} KB / HTML への script 挿入 {} 件",
        stats.files,
        stats.bytes as f64 / 1024.0,
        stats.html_patched
    ));

    progress.step(&format!("4/{total_steps}"), "アイコンを差し替え");
    for (name, data) in project::icon_layers(cfg, &ids)? {
        if !builder.contains(&name) {
            return Err(format!(
                "テンプレートにアイコン {name} がありません。\
                 `sakiika devtemplate` でテンプレートを作り直してください。"
            ));
        }
        // Stored, not deflated: Android memory-maps bitmap resources.
        builder.add(Entry::stored(name, data));
    }

    let mut step = 5;
    let mut apk_path = None;
    let mut apk_size = 0u64;
    let entry_count = builder.len();

    if format.wants_apk() {
        progress.step(&format!("{step}/{total_steps}"), "APK を書き出して署名 (v2/v3)");
        step += 1;
        let unsigned = builder.finish()?;
        let signed = sign::sign(&unsigned, &signer, cfg.min_sdk)?;
        let path = out_dir.join(format!("{base_name}.apk"));
        fs::write(&path, &signed)
            .map_err(|e| format!("APK を書けません {}: {e}", path.display()))?;
        apk_size = signed.len() as u64;
        apk_path = Some(path);
    }

    let mut aab_path = None;
    let mut aab_size = 0u64;
    if format.wants_aab() {
        progress.step(
            &format!("{step}/{total_steps}"),
            "AAB を書き出して署名 (JAR 署名)",
        );
        if TEMPLATE_PROTO.is_empty() {
            return Err(concat!(
                "AAB 用の protobuf リソースが埋め込まれていません。\n",
                "  `sakiika devtemplate` を実行してから再ビルドしてください。"
            )
            .to_string());
        }
        let proto_template = apk::Template::read(TEMPLATE_PROTO)?;
        let bundle = aab::build(cfg, &ids, &template, &proto_template, &signer)?;
        let path = out_dir.join(format!("{base_name}.aab"));
        fs::write(&path, &bundle.bytes)
            .map_err(|e| format!("AAB を書けません {}: {e}", path.display()))?;
        progress.log(&format!("AAB {} エントリ", bundle.entry_count));
        aab_size = bundle.bytes.len() as u64;
        aab_path = Some(path);
    }

    Ok(Report {
        apk: apk_path,
        apk_size_bytes: apk_size,
        aab: aab_path,
        aab_size_bytes: aab_size,
        key: key_path,
        certificate_fingerprint: signer.certificate_fingerprint(),
        elapsed_ms: started.elapsed().as_millis(),
        entry_count,
        modules: project::enabled_modules(cfg),
        permissions: cfg.effective_permissions(),
        html_files_patched: stats.html_patched,
        asset_files: stats.files,
    })
}

/// Duplicates a template entry so the same template can feed both outputs.
fn clone_entry(entry: &Entry) -> Entry {
    Entry {
        name: entry.name.clone(),
        data: entry.data.clone(),
        method: entry.method,
        raw: entry.raw.as_ref().map(|raw| apk::RawEntry {
            compressed: raw.compressed.clone(),
            crc32: raw.crc32,
            uncompressed_size: raw.uncompressed_size,
            method: raw.method,
        }),
    }
}

/// The certificate's subject. Includes the app name so a user inspecting the
/// APK can tell which project a key belongs to.
fn certificate_subject(cfg: &AppConfig) -> String {
    let safe: String = cfg
        .app_name
        .chars()
        .filter(|c| !matches!(c, ',' | '=' | '+' | '<' | '>' | '#' | ';' | '\\' | '"'))
        .collect();
    let name = if safe.trim().is_empty() { "Sakiika App" } else { safe.trim() };
    format!("CN={name},O=Sakiika Builder,C=JP")
}

fn is_signature_entry(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    if !upper.starts_with("META-INF/") {
        return false;
    }
    upper.ends_with(".SF")
        || upper.ends_with(".RSA")
        || upper.ends_with(".DSA")
        || upper.ends_with(".EC")
        || upper == "META-INF/MANIFEST.MF"
}

fn sanitize_file_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.').to_string();
    if trimmed.is_empty() {
        "app".to_string()
    } else {
        trimmed
    }
}

/// Installs onto a connected device with adb, if one is available.
///
/// This is the only step that still wants an external tool. Without adb the APK
/// is simply copied to the device by hand, so it stays optional.
pub fn install(apk: &Path, progress: &mut dyn Progress) -> Result<String, String> {
    let adb = crate::toolchain::find_adb().ok_or_else(|| {
        concat!(
            "adb が見つかりません。\n",
            "  APK をそのまま端末にコピーして開けばインストールできます（提供元不明のアプリの許可が必要）。\n",
            "  USB 経由で入れたい場合は Android SDK の platform-tools を入れ、adb を PATH に追加してください。"
        )
        .to_string()
    })?;

    let mut list = std::process::Command::new(&adb);
    list.arg("devices");
    let output = list
        .output()
        .map_err(|e| format!("adb を起動できません: {e}"))?;
    let devices = String::from_utf8_lossy(&output.stdout).to_string();
    let connected = devices
        .lines()
        .skip(1)
        .filter(|line| line.contains("\tdevice"))
        .count();
    if connected == 0 {
        return Err(concat!(
            "接続された端末がありません。\n",
            "  1. 端末の「開発者向けオプション」→「USB デバッグ」をオンにする\n",
            "  2. USB で接続し、端末に出る確認ダイアログを許可する"
        )
        .to_string());
    }

    progress.step("install", &format!("{connected} 台の端末にインストール"));
    let mut cmd = std::process::Command::new(&adb);
    // -r reinstalls in place; -g pre-grants runtime permissions so a test build
    // does not open a dialog for every one of them.
    cmd.arg("install").arg("-r").arg("-g").arg(apk);
    let result = cmd
        .output()
        .map_err(|e| format!("adb install を実行できません: {e}"))?;
    let stdout = String::from_utf8_lossy(&result.stdout).to_string();
    let stderr = String::from_utf8_lossy(&result.stderr).to_string();
    for line in stdout.lines().chain(stderr.lines()) {
        if !line.trim().is_empty() {
            progress.log(line);
        }
    }
    if !result.status.success() {
        return Err(format!(
            "adb install が失敗しました\n{}",
            if stderr.trim().is_empty() { stdout } else { stderr }
        ));
    }
    Ok(stdout)
}
