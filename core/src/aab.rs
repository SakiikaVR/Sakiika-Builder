//! Builds an Android App Bundle (`.aab`) — the format Google Play accepts.
//!
//! An AAB is not an APK with a different name. Its manifest and resource table
//! are protobuf, its files sit under a module directory, and it is signed the JAR
//! way rather than with the APK signature schemes:
//!
//! ```text
//!   BundleConfig.pb
//!   base/manifest/AndroidManifest.xml   protobuf XmlNode
//!   base/dex/classes.dex
//!   base/resources.pb                   protobuf resource table
//!   base/res/**
//!   base/assets/**
//!   META-INF/MANIFEST.MF, *.SF, *.EC    JAR signature
//! ```
//!
//! Both the proto resource table and the dex come from the same prebuilt
//! template as the APK path, so an app built either way behaves identically.

use crate::apk::{self, Entry};
use crate::config::AppConfig;
use crate::jarsign;
use crate::manifest::TemplateIds;
use crate::pbxml;
use crate::project::{self, AssetStats};
use crate::protobuf::Message;
use crate::sign::Signer;

/// Module directory inside the bundle. Only a base module is produced: dynamic
/// feature modules would need per-module manifests and a split config, which a
/// single HTML folder has no way to express.
const MODULE: &str = "base";

/// BundleConfig { Bundletool bundletool = 1 }, Bundletool { string version = 2 }
fn bundle_config() -> Vec<u8> {
    let mut bundletool = Message::new();
    // The version bundletool records as having produced the bundle. Reporting a
    // recent release keeps its own compatibility checks satisfied.
    bundletool.string(2, "1.18.3");
    let mut config = Message::new();
    config.message(1, &bundletool);
    config.into_bytes()
}

pub struct BundleParts {
    pub bytes: Vec<u8>,
    pub entry_count: usize,
    pub stats: AssetStats,
}

/// Assembles and signs the bundle.
///
/// `apk_template` supplies `classes.dex`; `proto_template` supplies
/// `resources.pb` and the `res/**` files in protobuf form.
pub fn build(
    cfg: &AppConfig,
    ids: &TemplateIds,
    apk_template: &apk::Template,
    proto_template: &apk::Template,
    signer: &Signer,
) -> Result<BundleParts, String> {
    // Collected as plain bytes first: JAR signing has to digest every entry
    // before any of them can be written.
    let mut entries: Vec<(String, Vec<u8>)> = Vec::new();

    entries.push(("BundleConfig.pb".to_string(), bundle_config()));

    let manifest_tree = crate::manifest::build(cfg, ids);
    entries.push((
        format!("{MODULE}/manifest/AndroidManifest.xml"),
        pbxml::encode(&manifest_tree),
    ));

    // Dex comes from the binary template; it is format-independent.
    let mut dex_count = 0;
    for entry in &apk_template.entries {
        if entry.name.ends_with(".dex") {
            entries.push((
                format!("{MODULE}/dex/{}", entry.name),
                decompress(entry)?,
            ));
            dex_count += 1;
        }
    }
    if dex_count == 0 {
        return Err("テンプレートに classes.dex がありません".to_string());
    }

    // Resource table and resource files, in protobuf form.
    let icon_layers = project::icon_layers(cfg, ids)?;
    let mut has_resources = false;
    for entry in &proto_template.entries {
        if entry.name == "resources.pb" {
            entries.push((format!("{MODULE}/resources.pb"), decompress(entry)?));
            has_resources = true;
            continue;
        }
        if !entry.name.starts_with("res/") {
            continue;
        }
        // Icon layers are swapped for the project's own colours or PNG.
        let replacement = icon_layers
            .iter()
            .find(|(name, _)| *name == entry.name)
            .map(|(_, data)| data.clone());
        let data = match replacement {
            Some(data) => data,
            None => decompress(entry)?,
        };
        entries.push((format!("{MODULE}/{}", entry.name), data));
    }
    if !has_resources {
        return Err(
            "テンプレートに resources.pb がありません。`sakiika devtemplate` で作り直してください。"
                .to_string(),
        );
    }

    let mut stats = AssetStats::default();
    for asset in project::collect_assets(cfg, &mut stats)? {
        // collect_assets names things `assets/…`; a bundle wants `base/assets/…`.
        entries.push((format!("{MODULE}/{}", asset.name), asset.data));
    }

    let signature = jarsign::sign(&entries, signer)?;

    // Resolve the names before consuming the byte buffers.
    let signature_file_path = signature.signature_file_path();
    let signature_block_path = signature.signature_block_path();

    let mut builder = apk::Builder::new();
    // The manifest has to be the first entry for a JAR to be readable as one.
    builder.add(Entry::new(jarsign::MANIFEST_PATH, signature.manifest));
    builder.add(Entry::new(signature_file_path, signature.signature_file));
    builder.add(Entry::new(signature_block_path, signature.signature_block));
    for (name, data) in entries {
        builder.add(Entry::new(name, data));
    }

    let entry_count = builder.len();
    let bytes = builder.finish()?.to_bytes();
    Ok(BundleParts { bytes, entry_count, stats })
}

/// Template entries hold their compressed bytes; a bundle needs the real
/// content so it can be digested and recompressed.
fn decompress(entry: &Entry) -> Result<Vec<u8>, String> {
    let raw = entry
        .raw
        .as_ref()
        .ok_or_else(|| format!("{} の内容がありません", entry.name))?;
    match raw.method {
        apk::Compress::Store => Ok(raw.compressed.clone()),
        apk::Compress::Deflate => {
            use std::io::Read;
            let mut decoder = flate2::read::DeflateDecoder::new(raw.compressed.as_slice());
            let mut out = Vec::with_capacity(raw.uncompressed_size as usize);
            decoder
                .read_to_end(&mut out)
                .map_err(|e| format!("{} を展開できません: {e}", entry.name))?;
            Ok(out)
        }
    }
}
