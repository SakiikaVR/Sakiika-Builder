//! Locates everything the build needs: an Android SDK (build-tools + a
//! platform android.jar) and a JDK (javac + keytool). No Gradle, no Android
//! Studio, no AGP — just the raw tools that ship in the SDK's build-tools.

use std::env;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
pub struct Toolchain {
    pub sdk_root: PathBuf,
    pub build_tools: PathBuf,
    pub build_tools_version: String,
    pub platform_jar: PathBuf,
    pub platform_version: u32,
    pub jdk_home: PathBuf,

    pub aapt2: PathBuf,
    pub aapt: PathBuf,
    pub d8: PathBuf,
    pub zipalign: PathBuf,
    pub apksigner: PathBuf,
    pub javac: PathBuf,
    pub keytool: PathBuf,
    pub adb: Option<PathBuf>,
}

fn exe(dir: &Path, stem: &str) -> Option<PathBuf> {
    for ext in ["exe", "bat", "cmd", ""] {
        let candidate = if ext.is_empty() {
            dir.join(stem)
        } else {
            dir.join(format!("{stem}.{ext}"))
        };
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

/// Parses "35.0.0" style names so the newest build-tools wins.
fn parse_version(name: &str) -> (u32, u32, u32) {
    let mut it = name.split('.').map(|p| {
        p.chars()
            .take_while(|c| c.is_ascii_digit())
            .collect::<String>()
            .parse::<u32>()
            .unwrap_or(0)
    });
    (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    )
}

fn sdk_candidates() -> Vec<PathBuf> {
    let mut v = Vec::new();
    for key in ["SAKIIKA_SDK", "ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Ok(p) = env::var(key) {
            if !p.trim().is_empty() {
                v.push(PathBuf::from(p));
            }
        }
    }
    if let Ok(local) = env::var("LOCALAPPDATA") {
        v.push(PathBuf::from(&local).join("Android").join("Sdk"));
    }
    if let Ok(home) = env::var("USERPROFILE") {
        v.push(PathBuf::from(&home).join("AppData\\Local\\Android\\Sdk"));
    }
    for root in ["C:\\AndroidSDK", "C:\\AndroidSdk", "C:\\Android\\Sdk", "C:\\Android\\android-sdk"] {
        v.push(PathBuf::from(root));
    }
    v
}

fn jdk_candidates() -> Vec<PathBuf> {
    let mut v = Vec::new();
    for key in ["SAKIIKA_JDK", "JAVA_HOME"] {
        if let Ok(p) = env::var(key) {
            if !p.trim().is_empty() {
                v.push(PathBuf::from(p));
            }
        }
    }
    // javac sitting on PATH tells us the JDK home two levels up.
    if let Ok(path) = env::var("PATH") {
        for dir in env::split_paths(&path) {
            if exe(&dir, "javac").is_some() {
                if let Some(parent) = dir.parent() {
                    v.push(parent.to_path_buf());
                }
            }
        }
    }
    for base in [
        "C:\\Program Files\\Eclipse Adoptium",
        "C:\\Program Files\\Java",
        "C:\\Program Files\\Microsoft",
        "C:\\Program Files\\Amazon Corretto",
        "C:\\Program Files\\Zulu",
        "C:\\Program Files\\Android\\Android Studio\\jbr",
    ] {
        let base = PathBuf::from(base);
        if base.join("bin").join("javac.exe").is_file() {
            v.push(base.clone());
        }
        if let Ok(entries) = std::fs::read_dir(&base) {
            let mut dirs: Vec<PathBuf> = entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.join("bin").join("javac.exe").is_file())
                .collect();
            // Newest JDK first.
            dirs.sort();
            dirs.reverse();
            v.extend(dirs);
        }
    }
    v
}

/// Finds `adb` without needing a full SDK.
///
/// Installing over USB is the only step that still wants an external tool, and
/// it is optional — a user can always copy the APK to the device instead.
pub fn find_adb() -> Option<PathBuf> {
    for cand in sdk_candidates() {
        if let Some(adb) = exe(&cand.join("platform-tools"), "adb") {
            return Some(adb);
        }
    }
    if let Ok(path) = env::var("PATH") {
        if let Some(adb) = env::split_paths(&path).find_map(|dir| exe(&dir, "adb")) {
            return Some(adb);
        }
    }
    None
}

fn pick_build_tools(sdk: &Path, wanted: Option<&str>) -> Option<(PathBuf, String)> {
    let dir = sdk.join("build-tools");
    let entries = std::fs::read_dir(&dir).ok()?;
    let mut versions: Vec<(PathBuf, String)> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .filter_map(|p| {
            let name = p.file_name()?.to_string_lossy().to_string();
            // A build-tools dir is only usable if it has the four tools we drive.
            if exe(&p, "aapt2").is_some() && exe(&p, "d8").is_some() && exe(&p, "zipalign").is_some()
            {
                Some((p, name))
            } else {
                None
            }
        })
        .collect();
    if let Some(w) = wanted {
        if let Some(hit) = versions.iter().find(|(_, n)| n == w) {
            return Some(hit.clone());
        }
    }
    versions.sort_by_key(|(_, n)| parse_version(n));
    versions.pop()
}

fn pick_platform(sdk: &Path, target_sdk: u32) -> Option<(PathBuf, u32)> {
    let dir = sdk.join("platforms");
    let entries = std::fs::read_dir(&dir).ok()?;
    let mut found: Vec<(PathBuf, u32)> = entries
        .flatten()
        .map(|e| e.path())
        .filter_map(|p| {
            let jar = p.join("android.jar");
            if !jar.is_file() {
                return None;
            }
            let name = p.file_name()?.to_string_lossy().to_string();
            let api: u32 = name.strip_prefix("android-")?.parse().ok()?;
            Some((jar, api))
        })
        .collect();
    if found.is_empty() {
        return None;
    }
    found.sort_by_key(|(_, api)| *api);
    // Prefer the exact target, else the lowest platform that is still >= target,
    // else the newest available (compiling against an older platform than the
    // declared targetSdk is the one case that breaks source compatibility).
    if let Some(hit) = found.iter().find(|(_, api)| *api == target_sdk) {
        return Some(hit.clone());
    }
    if let Some(hit) = found.iter().find(|(_, api)| *api >= target_sdk) {
        return Some(hit.clone());
    }
    found.pop()
}

impl Toolchain {
    pub fn detect(target_sdk: u32) -> Result<Toolchain, String> {
        let wanted_bt = env::var("SAKIIKA_BUILD_TOOLS").ok();
        let mut tried: Vec<String> = Vec::new();

        let (sdk_root, build_tools, build_tools_version) = 'found: {
            for cand in sdk_candidates() {
                if !cand.is_dir() {
                    continue;
                }
                match pick_build_tools(&cand, wanted_bt.as_deref()) {
                    Some((bt, ver)) => break 'found (cand, bt, ver),
                    None => tried.push(format!("  {} （build-tools が無い/不完全）", cand.display())),
                }
            }
            return Err(format!(
                "Android SDK の build-tools が見つかりません。\n探した場所:\n{}\n\n\
                 対処: Android SDK Command-line Tools を入れて `sdkmanager \"build-tools;34.0.0\" \"platforms;android-34\"` を実行するか、\n\
                 環境変数 SAKIIKA_SDK に SDK のパスを設定してください。",
                if tried.is_empty() { "  （候補なし）".to_string() } else { tried.join("\n") }
            ));
        };

        let (platform_jar, platform_version) = pick_platform(&sdk_root, target_sdk).ok_or_else(|| {
            format!(
                "android.jar が見つかりません（{}\\platforms\\android-*）。\n\
                 対処: `sdkmanager \"platforms;android-{target_sdk}\"`",
                sdk_root.display()
            )
        })?;

        let jdk_home = jdk_candidates()
            .into_iter()
            .find(|p| exe(&p.join("bin"), "javac").is_some())
            .ok_or_else(|| {
                "JDK が見つかりません（javac が必要）。\n\
                 対処: JDK 17 以上を入れて JAVA_HOME を設定するか、SAKIIKA_JDK を設定してください。"
                    .to_string()
            })?;
        let jbin = jdk_home.join("bin");

        let missing = |name: &str| format!("{} が build-tools {} にありません", name, build_tools_version);

        let tc = Toolchain {
            aapt2: exe(&build_tools, "aapt2").ok_or_else(|| missing("aapt2"))?,
            aapt: exe(&build_tools, "aapt").ok_or_else(|| missing("aapt"))?,
            d8: exe(&build_tools, "d8").ok_or_else(|| missing("d8"))?,
            zipalign: exe(&build_tools, "zipalign").ok_or_else(|| missing("zipalign"))?,
            apksigner: exe(&build_tools, "apksigner").ok_or_else(|| missing("apksigner"))?,
            javac: exe(&jbin, "javac").ok_or_else(|| "javac が見つかりません".to_string())?,
            keytool: exe(&jbin, "keytool").ok_or_else(|| "keytool が見つかりません".to_string())?,
            adb: exe(&sdk_root.join("platform-tools"), "adb").or_else(|| {
                env::var("PATH").ok().and_then(|path| {
                    env::split_paths(&path).find_map(|d| exe(&d, "adb"))
                })
            }),
            sdk_root,
            build_tools,
            build_tools_version,
            platform_jar,
            platform_version,
            jdk_home,
        };
        Ok(tc)
    }

    pub fn describe(&self) -> String {
        let mut s = String::new();
        s.push_str(&format!("Android SDK      : {}\n", self.sdk_root.display()));
        s.push_str(&format!("build-tools      : {}\n", self.build_tools_version));
        s.push_str(&format!(
            "platform         : android-{} ({})\n",
            self.platform_version,
            self.platform_jar.display()
        ));
        s.push_str(&format!("JDK              : {}\n", self.jdk_home.display()));
        s.push_str(&format!("aapt2            : {}\n", self.aapt2.display()));
        s.push_str(&format!("d8               : {}\n", self.d8.display()));
        s.push_str(&format!("zipalign         : {}\n", self.zipalign.display()));
        s.push_str(&format!("apksigner        : {}\n", self.apksigner.display()));
        s.push_str(&format!(
            "adb              : {}\n",
            self.adb
                .as_ref()
                .map(|p| p.display().to_string())
                .unwrap_or_else(|| "（未検出・インストールは手動）".to_string())
        ));
        s
    }
}
