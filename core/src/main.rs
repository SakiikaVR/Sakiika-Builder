//! さきいかビルダー — HTML フォルダーから Android APK を作る CLI。
//!
//! 利用者の PC には Java も Android SDK も要りません。コンパイル済みの
//! ランタイム（テンプレート APK）を実行ファイルに埋め込み、マニフェスト生成・
//! ZIP 書き出し・APK 署名をすべて Rust で行います。
//!
//! テンプレート自体を作るときだけ Android SDK と JDK が必要で、それは
//! `sakiika devtemplate`（開発者向け）が担当します。

mod aab;
mod apk;
mod axml;
mod catalog;
mod config;
mod fastbuild;
mod jarsign;
mod manifest;
mod pbxml;
mod pipeline;
mod png;
mod project;
mod protobuf;
mod sign;
mod toolchain;

use config::{AppConfig, FileAccess, Orientation, OutputFormat, ThemeMode};
use fastbuild::Progress;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

const VERSION: &str = env!("CARGO_PKG_VERSION");
const BRAND: &str = "さきいかビルダー";
const DEFAULT_CERT_SUBJECT: &str = "CN=Sakiika Builder,O=Sakiika,C=JP";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        print_usage();
        return ExitCode::SUCCESS;
    }
    let command = args[0].clone();
    let rest = &args[1..];

    let result = match command.as_str() {
        "build" => cmd_build(rest),
        "quick" => cmd_quick(rest),
        "init" => cmd_init(rest),
        "doctor" => cmd_doctor(rest),
        "permissions" | "perms" => cmd_permissions(rest),
        "modules" => cmd_modules(rest),
        "levels" => cmd_levels(rest),
        "install" => cmd_install(rest),
        "schema" => cmd_schema(rest),
        "devtemplate" => cmd_devtemplate(rest),
        "resign" => cmd_resign(rest),
        "-h" | "--help" | "help" => {
            print_usage();
            Ok(())
        }
        "-V" | "--version" | "version" => {
            println!("{BRAND} {VERSION}");
            Ok(())
        }
        other => Err(format!(
            "未知のコマンド: {other}\n`sakiika --help` で使い方を表示します"
        )),
    };

    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("\nエラー: {message}");
            ExitCode::FAILURE
        }
    }
}

fn print_usage() {
    println!(
        r##"{BRAND} {VERSION}
HTML フォルダーを指定して Android アプリ (APK) を作ります。
Java・Android SDK・Gradle は必要ありません。

使い方:
  sakiika init      [--web <HTMLフォルダー>] [--name <アプリ名>] [--package <パッケージ>]
                    [--out <sakiika.json>]
  sakiika build     <sakiika.json> [--release] [--install] [--verbose] [--json]
  sakiika quick     --web <HTMLフォルダー> --name <アプリ名> --package <com.example.app>
                    [--format apk|aab|both] [--file-access <レベル>]
                    [--permissions A,B,C] [--no-splash]
                    [--theme light|dark|auto] [--orientation portrait|landscape|sensor]
                    [--min-sdk 26] [--target-sdk 34] [--icon <png>]
                    [--icon-background "#1E88E5"] [--out-dir <フォルダー>]
                    [--release] [--install] [--verbose] [--json]
  sakiika install   <apk>             接続中の端末にインストール（adb があるとき）
  sakiika doctor    [--json]          動作状態の確認
  sakiika permissions [--json]        選べる権限の一覧
  sakiika modules   [--json]          JS ブリッジのモジュール一覧
  sakiika levels    [--json]          ファイルアクセスレベルの一覧
  sakiika schema                      設定ファイルの雛形 (JSON) を出力

開発者向け（Android SDK と JDK が必要）:
  sakiika devtemplate [--out <フォルダー>] [--verbose]
                    ランタイムテンプレートを作り直します。実行後に
                    `cargo build --release` で実行ファイルに埋め込まれます。
  sakiika resign    <apk> [--out <apk>] [--key <pem>]
                    既存 APK を組み直して署名し直します（署名機構の検証用）。

出力形式:
{formats}

ファイルアクセスレベル:
{levels}
"##,
        formats = OutputFormat::all()
            .iter()
            .map(|f| format!("  {:<14} {}", f.id(), f.label_ja()))
            .collect::<Vec<_>>()
            .join("\n"),
        levels = FileAccess::all()
            .iter()
            .map(|l| format!("  {:<14} {}", level_id(*l), l.label_ja()))
            .collect::<Vec<_>>()
            .join("\n")
    );
}

fn level_id(level: FileAccess) -> &'static str {
    match level {
        FileAccess::Off => "off",
        FileAccess::AppPrivate => "app_private",
        FileAccess::FolderPick => "folder_pick",
        FileAccess::Documents => "documents",
        FileAccess::MediaOnly => "media_only",
        FileAccess::FullManager => "full_manager",
    }
}

fn parse_level(value: &str) -> Result<FileAccess, String> {
    FileAccess::all()
        .iter()
        .copied()
        .find(|l| level_id(*l) == value)
        .ok_or_else(|| {
            format!(
                "未知のファイルアクセスレベル: {value}\n  使えるのは: {}",
                FileAccess::all()
                    .iter()
                    .map(|l| level_id(*l))
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        })
}

// ------------------------------------------------------------ arg parsing

#[derive(Default)]
struct Args {
    flags: HashMap<String, String>,
    positional: Vec<String>,
}

impl Args {
    /// Accepts `--key value`, `--key=value` and bare `--flag`.
    fn parse(input: &[String]) -> Args {
        let mut out = Args::default();
        let mut i = 0;
        while i < input.len() {
            let item = &input[i];
            if let Some(stripped) = item.strip_prefix("--") {
                if let Some((key, value)) = stripped.split_once('=') {
                    out.flags.insert(key.to_string(), value.to_string());
                } else if i + 1 < input.len() && !input[i + 1].starts_with("--") {
                    out.flags.insert(stripped.to_string(), input[i + 1].clone());
                    i += 1;
                } else {
                    out.flags.insert(stripped.to_string(), "true".to_string());
                }
            } else {
                out.positional.push(item.clone());
            }
            i += 1;
        }
        out
    }

    fn has(&self, key: &str) -> bool {
        self.flags.contains_key(key)
    }

    fn get(&self, key: &str) -> Option<&str> {
        self.flags.get(key).map(|s| s.as_str())
    }

    fn parse_num<T: std::str::FromStr>(&self, key: &str) -> Result<Option<T>, String> {
        match self.get(key) {
            None => Ok(None),
            Some(raw) => raw
                .parse::<T>()
                .map(Some)
                .map_err(|_| format!("--{key} は数値で指定してください（受け取った値: {raw}）")),
        }
    }
}

// -------------------------------------------------------------- progress

/// One JSON object per line, so the GUI can parse progress as it streams.
struct JsonProgress;

impl Progress for JsonProgress {
    fn step(&mut self, name: &str, detail: &str) {
        println!(
            "{}",
            serde_json::json!({"type": "step", "name": name, "detail": detail})
        );
    }

    fn log(&mut self, line: &str) {
        println!("{}", serde_json::json!({"type": "log", "line": line}));
    }
}

fn progress_for(args: &Args) -> Box<dyn Progress> {
    if args.has("json") {
        Box::new(JsonProgress)
    } else {
        Box::new(fastbuild::StdoutProgress {
            verbose: args.has("verbose"),
        })
    }
}

// ---------------------------------------------------------------- commands

fn cmd_build(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let path = args
        .positional
        .first()
        .map(PathBuf::from)
        .or_else(|| {
            // Convenience: fall back to a project file in the current directory.
            ["sakiika.json", "apkforge.json"]
                .iter()
                .map(PathBuf::from)
                .find(|p| p.is_file())
        })
        .ok_or_else(|| {
            "設定ファイルを指定してください（例: sakiika build sakiika.json）".to_string()
        })?;

    let mut cfg = AppConfig::load(&path)?;
    if args.has("release") {
        cfg.release = true;
    }
    if let Some(out) = args.get("out-dir") {
        cfg.output_dir = Some(PathBuf::from(out));
    }
    run_build(cfg, &args)
}

fn cmd_quick(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let mut cfg = AppConfig::default();

    cfg.web_root = PathBuf::from(
        args.get("web")
            .ok_or_else(|| "--web で HTML フォルダーを指定してください".to_string())?,
    );
    if let Some(name) = args.get("name") {
        cfg.app_name = name.to_string();
    }
    cfg.package_name = match args.get("package") {
        Some(pkg) => pkg.to_string(),
        None => derive_package(&cfg.app_name),
    };
    apply_common_flags(&mut cfg, &args)?;
    cfg.validate()?;
    run_build(cfg, &args)
}

fn apply_common_flags(cfg: &mut AppConfig, args: &Args) -> Result<(), String> {
    if let Some(entry) = args.get("entry") {
        cfg.entry = entry.to_string();
    }
    if let Some(format) = args.get("format") {
        cfg.output_format = OutputFormat::all()
            .iter()
            .copied()
            .find(|f| f.id() == format)
            .ok_or_else(|| format!("--format は apk/aab/both です（{format}）"))?;
    }
    if args.has("aab") {
        cfg.output_format = OutputFormat::Aab;
    }
    if let Some(level) = args.get("file-access") {
        cfg.file_access = parse_level(level)?;
    }
    if let Some(list) = args.get("permissions") {
        cfg.permissions = list
            .split(',')
            .map(|s| s.trim().to_uppercase())
            .filter(|s| !s.is_empty())
            .collect();
    }
    if let Some(list) = args.get("modules") {
        cfg.bridge.modules = list
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();
    }
    if let Some(theme) = args.get("theme") {
        cfg.theme = match theme {
            "light" => ThemeMode::Light,
            "dark" => ThemeMode::Dark,
            "auto" => ThemeMode::Auto,
            other => return Err(format!("--theme は light/dark/auto です（{other}）")),
        };
    }
    if let Some(o) = args.get("orientation") {
        cfg.orientation = match o {
            "portrait" => Orientation::Portrait,
            "landscape" => Orientation::Landscape,
            "sensor" => Orientation::Sensor,
            "unspecified" => Orientation::Unspecified,
            other => {
                return Err(format!(
                    "--orientation は portrait/landscape/sensor/unspecified です（{other}）"
                ))
            }
        };
    }
    if let Some(v) = args.parse_num::<u32>("min-sdk")? {
        cfg.min_sdk = v;
    }
    if let Some(v) = args.parse_num::<u32>("target-sdk")? {
        cfg.target_sdk = v;
    }
    if let Some(v) = args.parse_num::<u32>("version-code")? {
        cfg.version_code = v;
    }
    if let Some(v) = args.get("version-name") {
        cfg.version_name = v.to_string();
    }
    if let Some(icon) = args.get("icon") {
        cfg.icon_png = Some(PathBuf::from(icon));
    }
    if let Some(color) = args.get("icon-background") {
        cfg.icon_background = color.to_string();
    }
    if let Some(out) = args.get("out-dir") {
        cfg.output_dir = Some(PathBuf::from(out));
    }
    if let Some(color) = args.get("light-background") {
        cfg.light_background = color.to_string();
    }
    if let Some(color) = args.get("dark-background") {
        cfg.dark_background = color.to_string();
    }
    if args.has("no-splash") {
        cfg.splash.enabled = false;
    }
    if let Some(color) = args.get("splash-background") {
        cfg.splash.background = color.to_string();
    }
    if args.has("fullscreen") {
        cfg.fullscreen = true;
    }
    if args.has("no-reflection") {
        cfg.bridge.enable_reflection = false;
    }
    if args.has("release") {
        cfg.release = true;
    }
    if let Some(key) = args.get("key") {
        cfg.signing.key = Some(PathBuf::from(key));
    }
    Ok(())
}

fn run_build(cfg: AppConfig, args: &Args) -> Result<(), String> {
    let json = args.has("json");
    let mut progress = progress_for(args);

    if !json {
        println!("{BRAND} {VERSION}");
        println!("  アプリ名     : {}", cfg.app_name);
        println!("  パッケージ   : {}", cfg.package_name);
        println!("  HTML         : {}", cfg.web_root.display());
        println!("  出力形式     : {}", cfg.output_format.label_ja());
        println!(
            "  ファイル権限 : {} ({})",
            level_id(cfg.file_access),
            cfg.file_access.label_ja()
        );
        println!(
            "  スプラッシュ : {}",
            if cfg.splash.enabled { "有効" } else { "無効" }
        );
        println!();
    }

    let report = fastbuild::build(&cfg, progress.as_mut())?;

    if args.has("install") {
        match &report.apk {
            Some(apk) => fastbuild::install(apk, progress.as_mut())?,
            None => {
                return Err(
                    "AAB は端末に直接インストールできません。--format both で APK も作ってください。"
                        .to_string(),
                )
            }
        };
    }

    if json {
        println!(
            "{}",
            serde_json::json!({
                "type": "done",
                "apk": report.apk.as_ref().map(|p| p.display().to_string()),
                "apkSizeBytes": report.apk_size_bytes,
                "aab": report.aab.as_ref().map(|p| p.display().to_string()),
                "aabSizeBytes": report.aab_size_bytes,
                "key": report.key.display().to_string(),
                "certificateFingerprint": report.certificate_fingerprint,
                "elapsedMs": report.elapsed_ms,
                "entryCount": report.entry_count,
                "modules": report.modules,
                "permissions": report.permissions,
                "htmlPatched": report.html_files_patched,
                "assetFiles": report.asset_files,
            })
        );
    } else {
        println!();
        if let Some(apk) = &report.apk {
            println!(
                "完成: {}  ({:.2} MB)",
                apk.display(),
                report.apk_size_bytes as f64 / 1024.0 / 1024.0
            );
        }
        if let Some(aab) = &report.aab {
            println!(
                "完成: {}  ({:.2} MB)",
                aab.display(),
                report.aab_size_bytes as f64 / 1024.0 / 1024.0
            );
        }
        println!("  所要時間 : {} ミリ秒", report.elapsed_ms);
        println!("  署名鍵   : {}", report.key.display());
        println!("  証明書   : SHA-256 {}", report.certificate_fingerprint);
        println!("  モジュール: {}", report.modules.join(", "));
        println!("  権限     : {}", report.permissions.join(", "));
        println!();
        match &report.apk {
            Some(apk) => {
                if toolchain::find_adb().is_some() {
                    println!("端末に入れる: sakiika install \"{}\"", apk.display());
                } else {
                    println!("APK を端末にコピーして開けばインストールできます。");
                }
            }
            None => {
                println!("AAB は Google Play Console にアップロードして使います。");
                println!("端末で試すには --format both で APK も作ってください。");
            }
        }
    }
    Ok(())
}

fn derive_package(app_name: &str) -> String {
    let slug: String = app_name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect::<String>()
        .to_ascii_lowercase();
    let slug = if slug.is_empty() || !slug.starts_with(|c: char| c.is_ascii_lowercase()) {
        format!("app{slug}")
    } else {
        slug
    };
    format!("com.sakiika.{slug}")
}

fn cmd_init(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let mut cfg = AppConfig::default();

    let web = args
        .get("web")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("www"));
    if let Some(name) = args.get("name") {
        cfg.app_name = name.to_string();
    }
    cfg.package_name = match args.get("package") {
        Some(pkg) => pkg.to_string(),
        None => derive_package(&cfg.app_name),
    };
    cfg.web_root = web.clone();
    apply_common_flags(&mut cfg, &args)?;

    if !web.exists() {
        std::fs::create_dir_all(&web)
            .map_err(|e| format!("フォルダーを作れません {}: {e}", web.display()))?;
    }
    let index = web.join(&cfg.entry);
    if !index.exists() {
        std::fs::write(&index, STARTER_HTML)
            .map_err(|e| format!("{} を書けません: {e}", index.display()))?;
        println!("作成: {}", index.display());
    }

    let out = PathBuf::from(args.get("out").unwrap_or("sakiika.json"));
    if out.exists() && !args.has("force") {
        return Err(format!(
            "{} はすでにあります（上書きするなら --force）",
            out.display()
        ));
    }
    cfg.save(&out)?;
    println!("作成: {}", out.display());
    println!();
    println!("次のコマンドでビルドできます:");
    println!("  sakiika build {}", out.display());
    Ok(())
}

const STARTER_HTML: &str = r#"<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>新しいアプリ</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; padding: 24px;
         background: #fff; color: #111; }
  @media (prefers-color-scheme: dark) { body { background: #121212; color: #eee; } }
  button { font-size: 16px; padding: 12px 20px; border-radius: 10px;
           border: 1px solid #888; background: transparent; color: inherit; }
  pre { white-space: pre-wrap; word-break: break-all; }
</style>
</head>
<body>
  <h1>こんにちは</h1>
  <p>このページは Android アプリとして動いています。</p>
  <button id="hello">トーストと端末情報</button>
  <pre id="out"></pre>
<script>
  document.getElementById('hello').addEventListener('click', async () => {
    if (!window.Android || !Android.available) {
      alert('ブラウザーで開いています（Android アプリ内でのみブリッジが使えます）');
      return;
    }
    await Android.ui.toast({ text: 'さきいかビルダーから、こんにちは' });
    const info = await Android.sys.info();
    document.getElementById('out').textContent = JSON.stringify(info, null, 2);
  });
</script>
</body>
</html>
"#;

fn cmd_doctor(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let embedded = fastbuild::template_is_embedded();
    let ids = fastbuild::template_ids().ok();
    let adb = toolchain::find_adb();
    // Only the developer command needs these; a normal build does not.
    let dev_toolchain = toolchain::Toolchain::detect(34).ok();

    if args.has("json") {
        println!(
            "{}",
            serde_json::json!({
                "ok": embedded,
                "templateEmbedded": embedded,
                "templateIds": ids,
                "adb": adb.as_ref().map(|p| p.display().to_string()),
                "minSdk": fastbuild::MIN_SDK,
                "developerToolchain": dev_toolchain.as_ref().map(|tc| serde_json::json!({
                    "sdkRoot": tc.sdk_root.display().to_string(),
                    "buildTools": tc.build_tools_version,
                    "platform": tc.platform_version,
                    "jdk": tc.jdk_home.display().to_string(),
                })),
                "error": if embedded { serde_json::Value::Null }
                         else { serde_json::json!("テンプレートが埋め込まれていません") },
            })
        );
        return Ok(());
    }

    println!("{BRAND} {VERSION} — 動作状態\n");
    println!(
        "ランタイムテンプレート : {}",
        if embedded { "埋め込み済み（外部ツール不要）" } else { "未埋め込み" }
    );
    println!("対応 Android         : {} 以上", fastbuild::MIN_SDK);
    println!(
        "adb（USB インストール）: {}",
        adb.as_ref()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|| "未検出（APK を手動でコピーすれば使えます）".to_string())
    );
    match &dev_toolchain {
        Some(tc) => {
            println!("\n開発者向けツールチェーン（テンプレート再生成用）");
            println!("  Android SDK : {}", tc.sdk_root.display());
            println!("  build-tools : {}", tc.build_tools_version);
            println!("  platform    : android-{}", tc.platform_version);
            println!("  JDK         : {}", tc.jdk_home.display());
        }
        None => {
            println!("\n開発者向けツールチェーン : 未検出（アプリのビルドには不要です）");
        }
    }
    if !embedded {
        println!();
        return Err(
            "テンプレートが未埋め込みのためビルドできません。`sakiika devtemplate` を実行してください。"
                .to_string(),
        );
    }
    println!("\nビルドできる状態です。");
    Ok(())
}

fn cmd_devtemplate(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let tc = toolchain::Toolchain::detect(34)?;
    let mut progress = progress_for(&args);

    let out_dir = args
        .get("out")
        .map(PathBuf::from)
        .unwrap_or_else(default_prebuilt_dir);
    let work_dir = std::env::temp_dir().join("sakiika-template");

    if !args.has("json") {
        println!("{BRAND} {VERSION} — ランタイムテンプレートを生成");
        println!("  build-tools : {}", tc.build_tools_version);
        println!("  platform    : android-{}", tc.platform_version);
        println!("  出力先      : {}", out_dir.display());
        println!();
    }

    let report = pipeline::build_template(&tc, &work_dir, &out_dir, progress.as_mut())?;

    if args.has("json") {
        println!(
            "{}",
            serde_json::json!({
                "type": "done",
                "apk": report.apk.display().to_string(),
                "ids": report.ids_path.display().to_string(),
                "sizeBytes": report.size_bytes,
                "dexBytes": report.dex_bytes,
                "elapsedMs": report.elapsed_ms,
            })
        );
    } else {
        println!();
        println!("完成: {}", report.apk.display());
        println!("  サイズ   : {:.1} KB", report.size_bytes as f64 / 1024.0);
        println!("  DEX      : {:.1} KB", report.dex_bytes as f64 / 1024.0);
        println!("  所要時間 : {:.1} 秒", report.elapsed_ms as f64 / 1000.0);
        println!("  リソース ID: {}", report.ids_path.display());
        println!();
        println!("実行ファイルへ埋め込むには、続けて次を実行してください:");
        println!("  cargo build --release");
    }
    Ok(())
}

/// `core/prebuilt` relative to this source tree, so the default works when the
/// command is run from anywhere inside the repository.
fn default_prebuilt_dir() -> PathBuf {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    manifest_dir.join("prebuilt")
}

fn cmd_permissions(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    if args.has("json") {
        let items: Vec<_> = catalog::PERMISSIONS
            .iter()
            .map(|p| {
                serde_json::json!({
                    "id": p.id,
                    "manifest": p.manifest,
                    "runtime": p.runtime,
                    "special": p.special,
                    "group": p.group,
                    "description": p.desc_ja,
                    "minSdk": p.min_sdk,
                    "maxSdk": p.max_sdk,
                })
            })
            .collect();
        println!("{}", serde_json::json!({"permissions": items}));
        return Ok(());
    }
    println!("{BRAND} — 選べる権限 ({} 件)\n", catalog::PERMISSIONS.len());
    for group in catalog::groups() {
        println!("【{group}】");
        for p in catalog::PERMISSIONS.iter().filter(|p| p.group == group) {
            let kind = if p.special {
                "設定画面"
            } else if p.runtime {
                "実行時確認"
            } else {
                "インストール時"
            };
            println!("  {:<38} [{kind}] {}", p.id, p.desc_ja);
        }
        println!();
    }
    Ok(())
}

fn cmd_modules(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    if args.has("json") {
        let items: Vec<_> = catalog::MODULES
            .iter()
            .map(|m| {
                serde_json::json!({
                    "name": m.name,
                    "description": m.desc_ja,
                    "wants": m.wants,
                })
            })
            .collect();
        println!("{}", serde_json::json!({"modules": items}));
        return Ok(());
    }
    println!("{BRAND} — JS ブリッジのモジュール\n");
    for m in catalog::MODULES {
        println!("  Android.{:<11} {}", m.name, m.desc_ja);
        if !m.wants.is_empty() {
            println!("               └ 推奨権限: {}", m.wants.join(", "));
        }
    }
    Ok(())
}

fn cmd_levels(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    if args.has("json") {
        let items: Vec<_> = FileAccess::all()
            .iter()
            .map(|l| {
                serde_json::json!({
                    "id": level_id(*l),
                    "label": l.label_ja(),
                    "impliedPermissions": l.implied_permissions(),
                })
            })
            .collect();
        println!("{}", serde_json::json!({"levels": items}));
        return Ok(());
    }
    println!("{BRAND} — ファイルアクセスレベル\n");
    for l in FileAccess::all() {
        println!("  {:<14} {}", level_id(*l), l.label_ja());
        let implied = l.implied_permissions();
        if !implied.is_empty() {
            println!("                 └ 自動で付く権限: {}", implied.join(", "));
        }
    }
    Ok(())
}

fn cmd_install(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let apk = args
        .positional
        .first()
        .map(PathBuf::from)
        .ok_or_else(|| "インストールする APK を指定してください".to_string())?;
    if !apk.is_file() {
        return Err(format!("APK が見つかりません: {}", apk.display()));
    }
    let mut progress = progress_for(&args);
    let output = fastbuild::install(&apk, progress.as_mut())?;
    if !args.has("json") {
        println!("{output}");
    }
    Ok(())
}

fn cmd_schema(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let mut cfg = AppConfig::default();
    cfg.web_root = PathBuf::from("www");
    let text =
        serde_json::to_string_pretty(&cfg).map_err(|e| format!("雛形を作れません: {e}"))?;
    match args.get("out") {
        Some(path) => {
            std::fs::write(Path::new(path), &text)
                .map_err(|e| format!("書けません {path}: {e}"))?;
            println!("作成: {path}");
        }
        None => println!("{text}"),
    }
    Ok(())
}

/// Repacks and re-signs an existing APK with the built-in signer.
///
/// Kept as a way to exercise the zip writer and the v2/v3 signer against a real
/// APK without going through a full build.
fn cmd_resign(input: &[String]) -> Result<(), String> {
    let args = Args::parse(input);
    let source = args
        .positional
        .first()
        .map(PathBuf::from)
        .ok_or_else(|| "署名し直す APK を指定してください".to_string())?;
    let bytes = std::fs::read(&source)
        .map_err(|e| format!("APK を読めません {}: {e}", source.display()))?;

    let template = apk::Template::read(&bytes)?;
    let mut builder = apk::Builder::new();
    let mut dropped = 0;
    for entry in template.entries {
        if is_signature_entry(&entry.name) {
            dropped += 1;
            continue;
        }
        builder.add(entry);
    }

    let key_path = args
        .get("key")
        .map(PathBuf::from)
        .unwrap_or_else(|| source.with_extension("sakiika-key.pem"));
    let signer = sign::Signer::load_or_create(&key_path, DEFAULT_CERT_SUBJECT)?;
    let unsigned = builder.finish()?;
    let min_sdk: u32 = args.parse_num("min-sdk")?.unwrap_or(26);
    let signed = sign::sign(&unsigned, &signer, min_sdk)?;

    let dest = args
        .get("out")
        .map(PathBuf::from)
        .unwrap_or_else(|| source.with_extension("resigned.apk"));
    std::fs::write(&dest, &signed)
        .map_err(|e| format!("APK を書けません {}: {e}", dest.display()))?;

    println!("元の署名を {dropped} 件削除して署名し直しました");
    println!("  出力     : {}", dest.display());
    println!("  鍵       : {}", key_path.display());
    println!("  証明書   : SHA-256 {}", signer.certificate_fingerprint());
    println!("  サイズ   : {:.2} MB", signed.len() as f64 / 1024.0 / 1024.0);
    Ok(())
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
