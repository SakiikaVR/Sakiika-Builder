//! Builds the prebuilt runtime template. Developer-only.
//!
//! This is the one place the Android SDK and a JDK are still needed, and it runs
//! once per release rather than once per app:
//!
//! ```text
//!   aapt2 compile   res/**                -> compiled resources
//!   aapt2 link      + template manifest   -> resource table + R.java
//!   javac           the whole runtime     -> .class
//!   d8              .class                -> classes.dex
//!   (this module)   resources + dex       -> prebuilt/template.apk
//! ```
//!
//! The result is embedded into the binary, after which a user's build needs no
//! external tool at all — see `fastbuild`.

use crate::apk;
use crate::fastbuild::Progress;
use crate::manifest::TemplateIds;
use crate::toolchain::Toolchain;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Instant;

pub struct TemplateReport {
    pub apk: PathBuf,
    /// Protobuf resource table plus `res/**`, for building AABs.
    pub proto: PathBuf,
    pub ids_path: PathBuf,
    pub size_bytes: u64,
    pub proto_bytes: u64,
    pub dex_bytes: u64,
    pub elapsed_ms: u128,
    pub ids: TemplateIds,
}

struct Runner<'a> {
    progress: &'a mut dyn Progress,
}

impl<'a> Runner<'a> {
    /// Runs a tool, streaming its output and turning a non-zero exit into a
    /// message that names the tool and shows what it said.
    fn run(&mut self, label: &str, cmd: &mut Command) -> Result<String, String> {
        let rendered = format!("{cmd:?}");
        self.progress.log(&rendered);
        let output = cmd
            .output()
            .map_err(|e| format!("{label} を起動できません: {e}\n  コマンド: {rendered}"))?;
        let stdout = String::from_utf8_lossy(&output.stdout).to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        for line in stdout.lines().chain(stderr.lines()) {
            if !line.trim().is_empty() {
                self.progress.log(line);
            }
        }
        if !output.status.success() {
            let mut detail = String::new();
            for line in stderr.lines().chain(stdout.lines()) {
                if !line.trim().is_empty() {
                    detail.push_str("  ");
                    detail.push_str(line);
                    detail.push('\n');
                }
            }
            if detail.is_empty() {
                detail.push_str("  （出力なし）\n");
            }
            return Err(format!(
                "{label} が失敗しました（終了コード {}）\n{detail}",
                output.status.code().unwrap_or(-1)
            ));
        }
        Ok(stdout)
    }
}

pub fn build_template(
    tc: &Toolchain,
    work_dir: &Path,
    out_dir: &Path,
    progress: &mut dyn Progress,
) -> Result<TemplateReport, String> {
    let started = Instant::now();
    let (min_sdk, target_sdk) = crate::project::template_sdk_range();

    progress.step("1/6", "テンプレートプロジェクトを生成");
    let layout = crate::project::generate_template(work_dir)?;
    let mut runner = Runner { progress };

    progress_step(&mut runner, "2/6", "リソースをコンパイル (aapt2 compile)");
    let compiled_res = work_dir.join("res.zip");
    let mut cmd = Command::new(&tc.aapt2);
    cmd.arg("compile")
        .arg("--dir")
        .arg(&layout.res_dir)
        .arg("-o")
        .arg(&compiled_res);
    runner.run("aapt2 compile", &mut cmd)?;

    progress_step(&mut runner, "3/6", "リソーステーブルをリンク (aapt2 link)");
    let linked = work_dir.join("template-linked.apk");
    let mut cmd = Command::new(&tc.aapt2);
    cmd.arg("link")
        .arg("-o")
        .arg(&linked)
        .arg("-I")
        .arg(&tc.platform_jar)
        .arg("--manifest")
        .arg(&layout.manifest)
        .arg("-R")
        .arg(&compiled_res)
        .arg("--java")
        .arg(&layout.gen_dir)
        .arg("--min-sdk-version")
        .arg(min_sdk.to_string())
        .arg("--target-sdk-version")
        .arg(target_sdk.to_string())
        .arg("--auto-add-overlay");
    runner.run("aapt2 link", &mut cmd)?;

    // An AAB needs the same resources in protobuf form, which aapt2 only emits
    // as a separate link pass.
    let linked_proto = work_dir.join("template-proto.apk");
    let mut cmd = Command::new(&tc.aapt2);
    cmd.arg("link")
        .arg("--proto-format")
        .arg("-o")
        .arg(&linked_proto)
        .arg("-I")
        .arg(&tc.platform_jar)
        .arg("--manifest")
        .arg(&layout.manifest)
        .arg("-R")
        .arg(&compiled_res)
        .arg("--min-sdk-version")
        .arg(min_sdk.to_string())
        .arg("--target-sdk-version")
        .arg(target_sdk.to_string())
        .arg("--auto-add-overlay");
    runner.run("aapt2 link --proto-format", &mut cmd)?;

    let sources = collect_files(&layout.java_dir, "java")?
        .into_iter()
        .chain(collect_files(&layout.gen_dir, "java")?)
        .collect::<Vec<_>>();
    progress_step(
        &mut runner,
        "4/6",
        &format!("Java をコンパイル ({} ファイル, javac)", sources.len()),
    );
    let argfile = work_dir.join("javac-sources.txt");
    // Windows caps a command line at ~32k characters; an @argfile sidesteps it.
    let listing = sources
        .iter()
        .map(|p| format!("\"{}\"", p.display().to_string().replace('\\', "/")))
        .collect::<Vec<_>>()
        .join("\n");
    fs::write(&argfile, listing).map_err(|e| format!("javac の引数ファイルを書けません: {e}"))?;
    let mut cmd = Command::new(&tc.javac);
    cmd.arg("-nowarn")
        .arg("-encoding")
        .arg("UTF-8")
        .arg("-source")
        .arg("17")
        .arg("-target")
        .arg("17")
        .arg("-classpath")
        .arg(&tc.platform_jar)
        .arg("-d")
        .arg(&layout.classes_dir)
        .arg(format!("@{}", argfile.display()));
    runner.run("javac", &mut cmd)?;

    let classes = collect_files(&layout.classes_dir, "class")?;
    progress_step(
        &mut runner,
        "5/6",
        &format!("DEX に変換 ({} クラス, d8)", classes.len()),
    );
    let mut cmd = Command::new(&tc.d8);
    cmd.arg("--output")
        .arg(&layout.dex_dir)
        .arg("--lib")
        .arg(&tc.platform_jar)
        .arg("--min-api")
        .arg(min_sdk.to_string())
        .arg("--release");
    for class in &classes {
        cmd.arg(class);
    }
    runner.run("d8", &mut cmd)?;

    progress_step(&mut runner, "6/6", "テンプレート APK を組み立て");
    let linked_bytes = fs::read(&linked)
        .map_err(|e| format!("リンク結果を読めません {}: {e}", linked.display()))?;
    let linked_template = apk::Template::read(&linked_bytes)?;

    let mut builder = apk::Builder::new();
    let mut icon_entries = (String::new(), String::new(), String::new());
    for entry in linked_template.entries {
        // The per-project manifest replaces this one, so it is not worth
        // carrying; everything else (resources.arsc, res/**) is.
        if entry.name == "AndroidManifest.xml" {
            continue;
        }
        if entry.name.ends_with("/ic_launcher_bg.png") {
            icon_entries.0 = entry.name.clone();
        } else if entry.name.ends_with("/ic_launcher_fg.png") {
            icon_entries.1 = entry.name.clone();
        } else if entry.name.ends_with("/ic_launcher.png") {
            icon_entries.2 = entry.name.clone();
        }
        builder.add(entry);
    }
    for (label, name) in [
        ("ic_launcher_bg.png", &icon_entries.0),
        ("ic_launcher_fg.png", &icon_entries.1),
        ("ic_launcher.png", &icon_entries.2),
    ] {
        if name.is_empty() {
            return Err(format!(
                "テンプレートに {label} が見つかりません。リソース生成を確認してください。"
            ));
        }
    }

    let dex_files = collect_files(&layout.dex_dir, "dex")?;
    if dex_files.is_empty() {
        return Err("d8 が classes.dex を出力しませんでした".to_string());
    }
    let mut dex_bytes = 0u64;
    for dex in &dex_files {
        let data = fs::read(dex).map_err(|e| format!("{} を読めません: {e}", dex.display()))?;
        dex_bytes += data.len() as u64;
        let name = dex
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "classes.dex".to_string());
        builder.add(apk::Entry::new(name, data));
    }

    let mut ids = parse_resource_ids(&layout.gen_dir)?;
    ids.icon_background_entry = icon_entries.0;
    ids.icon_foreground_entry = icon_entries.1;
    ids.icon_legacy_entry = icon_entries.2;

    fs::create_dir_all(out_dir)
        .map_err(|e| format!("出力フォルダーを作れません {}: {e}", out_dir.display()))?;
    let apk_path = out_dir.join("template.apk");
    let bytes = builder.finish()?.to_bytes();
    fs::write(&apk_path, &bytes)
        .map_err(|e| format!("テンプレートを書けません {}: {e}", apk_path.display()))?;

    // Proto artifacts: only the resource table and the resource files. The proto
    // manifest is not kept — each project gets its own, generated by `pbxml`.
    let proto_bytes_in = fs::read(&linked_proto)
        .map_err(|e| format!("proto リンク結果を読めません {}: {e}", linked_proto.display()))?;
    let proto_linked = apk::Template::read(&proto_bytes_in)?;
    let mut proto_builder = apk::Builder::new();
    let mut saw_resources_pb = false;
    for entry in proto_linked.entries {
        if entry.name == "resources.pb" {
            saw_resources_pb = true;
        } else if !entry.name.starts_with("res/") {
            continue;
        }
        proto_builder.add(entry);
    }
    if !saw_resources_pb {
        return Err("aapt2 が resources.pb を出力しませんでした".to_string());
    }
    let proto_path = out_dir.join("template-proto.zip");
    let proto_out = proto_builder.finish()?.to_bytes();
    fs::write(&proto_path, &proto_out)
        .map_err(|e| format!("proto テンプレートを書けません {}: {e}", proto_path.display()))?;

    let ids_path = out_dir.join("template-ids.json");
    let ids_json = serde_json::to_string_pretty(&ids)
        .map_err(|e| format!("リソース ID を書き出せません: {e}"))?;
    fs::write(&ids_path, format!("{ids_json}\n"))
        .map_err(|e| format!("リソース ID を書けません {}: {e}", ids_path.display()))?;

    Ok(TemplateReport {
        apk: apk_path,
        proto: proto_path,
        ids_path,
        size_bytes: bytes.len() as u64,
        proto_bytes: proto_out.len() as u64,
        dex_bytes,
        elapsed_ms: started.elapsed().as_millis(),
        ids,
    })
}

fn progress_step(runner: &mut Runner, name: &str, detail: &str) {
    runner.progress.step(name, detail);
}

/// Reads the resource IDs out of the `R.java` aapt2 generated.
///
/// The manifest written for each project references the theme and icon by
/// numeric ID, so these have to be captured while the table is being built.
fn parse_resource_ids(gen_dir: &Path) -> Result<TemplateIds, String> {
    let files = collect_files(gen_dir, "java")?;
    let r_java = files
        .iter()
        .find(|p| p.file_name().map(|n| n == "R.java").unwrap_or(false))
        .ok_or_else(|| format!("R.java が見つかりません（{}）", gen_dir.display()))?;
    let text = fs::read_to_string(r_java)
        .map_err(|e| format!("R.java を読めません {}: {e}", r_java.display()))?;

    let mut current_type = String::new();
    let mut found: HashMap<String, u32> = HashMap::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix("public static final class ") {
            current_type = rest.trim_end_matches(" {").trim().to_string();
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("public static final int ") {
            let Some((name, value)) = rest.trim_end_matches(';').split_once('=') else {
                continue;
            };
            let value = value.trim();
            let Some(hex) = value.strip_prefix("0x") else { continue };
            if let Ok(id) = u32::from_str_radix(hex, 16) {
                found.insert(format!("{current_type}/{}", name.trim()), id);
            }
        }
    }

    let pick = |key: &str| -> Result<u32, String> {
        found
            .get(key)
            .copied()
            .ok_or_else(|| format!("R.java に {key} がありません"))
    };

    Ok(TemplateIds {
        icon: pick("mipmap/ic_launcher")?,
        theme_auto: pick("style/AppTheme")?,
        theme_light: pick("style/AppThemeLight")?,
        theme_dark: pick("style/AppThemeDark")?,
        theme_auto_no_splash: pick("style/AppThemeNoSplash")?,
        theme_light_no_splash: pick("style/AppThemeLightNoSplash")?,
        theme_dark_no_splash: pick("style/AppThemeDarkNoSplash")?,
        // Filled in by the caller, which has the archive to hand.
        icon_background_entry: String::new(),
        icon_foreground_entry: String::new(),
        icon_legacy_entry: String::new(),
    })
}

fn collect_files(dir: &Path, extension: &str) -> Result<Vec<PathBuf>, String> {
    let mut out = Vec::new();
    if !dir.exists() {
        return Ok(out);
    }
    let mut stack = vec![dir.to_path_buf()];
    while let Some(current) = stack.pop() {
        let entries = fs::read_dir(&current)
            .map_err(|e| format!("フォルダーを読めません {}: {e}", current.display()))?;
        for entry in entries {
            let entry = entry.map_err(|e| format!("読み取りエラー: {e}"))?;
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if path.extension().and_then(|e| e.to_str()) == Some(extension) {
                out.push(path);
            }
        }
    }
    out.sort();
    Ok(out)
}
