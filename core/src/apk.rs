//! Reads and writes APK archives directly.
//!
//! An APK is a ZIP with two extra rules: stored entries must sit on 4-byte
//! boundaries (what `zipalign` does), and the signing block goes between the
//! entry data and the central directory (what `apksigner` does). Writing the
//! archive ourselves covers both and removes `aapt add` and `zipalign` from the
//! toolchain.

use crc32fast::Hasher as Crc32;
use flate2::write::DeflateEncoder;
use flate2::Compression;
use std::collections::HashMap;
use std::io::Write;

use crate::sign::UnsignedApk;

/// Extensions Android expects to find uncompressed. Resources it memory-maps
/// must be stored, and already-compressed media gains nothing from deflate.
const STORE_EXTENSIONS: &[&str] = &[
    "arsc", "png", "jpg", "jpeg", "gif", "webp", "wav", "mp2", "mp3", "ogg", "aac", "mpg", "mpeg",
    "mid", "midi", "smf", "jet", "rtttl", "imy", "xmf", "mp4", "m4a", "m4v", "3gp", "3gpp", "3g2",
    "3gpp2", "amr", "awb", "wma", "wmv", "webm", "mkv", "opus", "zip", "apk", "jar", "so", "woff",
    "woff2", "ttf", "otf", "bin",
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compress {
    Store,
    Deflate,
}

/// One entry queued for the output archive.
pub struct Entry {
    pub name: String,
    /// Uncompressed bytes. Ignored when `raw` is set.
    pub data: Vec<u8>,
    pub method: Compress,
    /// Pre-compressed payload copied straight through from a source archive,
    /// with the metadata it needs. Avoids recompressing the template.
    pub raw: Option<RawEntry>,
}

pub struct RawEntry {
    pub compressed: Vec<u8>,
    pub crc32: u32,
    pub uncompressed_size: u32,
    pub method: Compress,
}

impl Entry {
    pub fn new(name: impl Into<String>, data: Vec<u8>) -> Entry {
        let name = name.into();
        let method = if should_store(&name) {
            Compress::Store
        } else {
            Compress::Deflate
        };
        Entry { name, data, method, raw: None }
    }

    pub fn stored(name: impl Into<String>, data: Vec<u8>) -> Entry {
        Entry { name: name.into(), data, method: Compress::Store, raw: None }
    }
}

fn should_store(name: &str) -> bool {
    // resources.arsc must be stored and 4-byte aligned from Android 11 onwards.
    if name == "resources.arsc" {
        return true;
    }
    match name.rsplit_once('.') {
        Some((_, ext)) => STORE_EXTENSIONS
            .iter()
            .any(|candidate| candidate.eq_ignore_ascii_case(ext)),
        None => false,
    }
}

/// Everything read out of a template APK, ready to be rewritten.
pub struct Template {
    pub entries: Vec<Entry>,
}

impl Template {
    /// Reads every entry, keeping the compressed bytes so nothing is recompressed.
    pub fn read(bytes: &[u8]) -> Result<Template, String> {
        let cursor = std::io::Cursor::new(bytes);
        let mut archive = zip::ZipArchive::new(cursor)
            .map_err(|e| format!("テンプレート APK を開けません: {e}"))?;

        let mut entries = Vec::with_capacity(archive.len());
        for i in 0..archive.len() {
            let mut file = archive
                .by_index_raw(i)
                .map_err(|e| format!("テンプレートの {i} 番目を読めません: {e}"))?;
            if file.is_dir() {
                continue;
            }
            let name = file.name().to_string();
            let method = match file.compression() {
                zip::CompressionMethod::Stored => Compress::Store,
                _ => Compress::Deflate,
            };
            let crc32 = file.crc32();
            let uncompressed_size = file.size() as u32;
            let mut compressed = Vec::with_capacity(file.compressed_size() as usize);
            std::io::copy(&mut file, &mut compressed)
                .map_err(|e| format!("テンプレートの {name} を読めません: {e}"))?;
            entries.push(Entry {
                name,
                data: Vec::new(),
                method,
                raw: Some(RawEntry { compressed, crc32, uncompressed_size, method }),
            });
        }
        Ok(Template { entries })
    }

    pub fn contains(&self, name: &str) -> bool {
        self.entries.iter().any(|e| e.name == name)
    }
}

/// Builds the output archive, in the order entries are added.
pub struct Builder {
    entries: Vec<Entry>,
    seen: HashMap<String, usize>,
}

impl Builder {
    pub fn new() -> Builder {
        Builder { entries: Vec::new(), seen: HashMap::new() }
    }

    /// Adds an entry, replacing any earlier one with the same name.
    ///
    /// Replacing rather than appending is what lets a caller override a template
    /// entry — a duplicate name in an APK is a hard error on some Android
    /// versions and silently ambiguous on others.
    pub fn add(&mut self, entry: Entry) {
        match self.seen.get(&entry.name) {
            Some(&index) => self.entries[index] = entry,
            None => {
                self.seen.insert(entry.name.clone(), self.entries.len());
                self.entries.push(entry);
            }
        }
    }

    pub fn contains(&self, name: &str) -> bool {
        self.seen.contains_key(name)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Serialises the archive into the three sections the signer digests.
    pub fn finish(self) -> Result<UnsignedApk, String> {
        if self.entries.len() > u16::MAX as usize {
            return Err(format!(
                "ファイル数が多すぎます ({} 件、上限 {})",
                self.entries.len(),
                u16::MAX
            ));
        }

        let mut body: Vec<u8> = Vec::new();
        let mut central: Vec<u8> = Vec::new();

        for entry in &self.entries {
            let (payload, crc32, uncompressed_size, method) = match &entry.raw {
                Some(raw) => (
                    raw.compressed.clone(),
                    raw.crc32,
                    raw.uncompressed_size,
                    raw.method,
                ),
                None => {
                    let mut hasher = Crc32::new();
                    hasher.update(&entry.data);
                    let crc = hasher.finalize();
                    let size = entry.data.len() as u32;
                    match entry.method {
                        Compress::Store => (entry.data.clone(), crc, size, Compress::Store),
                        Compress::Deflate => {
                            let mut encoder =
                                DeflateEncoder::new(Vec::new(), Compression::new(6));
                            encoder
                                .write_all(&entry.data)
                                .map_err(|e| format!("{} を圧縮できません: {e}", entry.name))?;
                            let compressed = encoder
                                .finish()
                                .map_err(|e| format!("{} を圧縮できません: {e}", entry.name))?;
                            // Deflate can grow tiny or random data; store it then.
                            if compressed.len() >= entry.data.len() {
                                (entry.data.clone(), crc, size, Compress::Store)
                            } else {
                                (compressed, crc, size, Compress::Deflate)
                            }
                        }
                    }
                }
            };

            let name_bytes = entry.name.as_bytes();
            if name_bytes.len() > u16::MAX as usize {
                return Err(format!("ファイル名が長すぎます: {}", entry.name));
            }

            // Stored entries must start on a 4-byte boundary so Android can
            // mmap them; padding goes in the local header's extra field.
            let header_start = body.len();
            let extra_len = if method == Compress::Store {
                let data_start = header_start + 30 + name_bytes.len();
                let misalignment = data_start % 4;
                if misalignment == 0 { 0 } else { 4 - misalignment }
            } else {
                0
            };

            let method_code: u16 = match method {
                Compress::Store => 0,
                Compress::Deflate => 8,
            };

            // Local file header.
            body.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
            body.extend_from_slice(&20u16.to_le_bytes()); // version needed
            body.extend_from_slice(&0u16.to_le_bytes()); // flags
            body.extend_from_slice(&method_code.to_le_bytes());
            body.extend_from_slice(&0u16.to_le_bytes()); // mod time
            body.extend_from_slice(&0x21u16.to_le_bytes()); // mod date: 1980-01-01
            body.extend_from_slice(&crc32.to_le_bytes());
            body.extend_from_slice(&(payload.len() as u32).to_le_bytes());
            body.extend_from_slice(&uncompressed_size.to_le_bytes());
            body.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
            body.extend_from_slice(&(extra_len as u16).to_le_bytes());
            body.extend_from_slice(name_bytes);
            body.extend(std::iter::repeat(0u8).take(extra_len));
            body.extend_from_slice(&payload);

            // Central directory record.
            central.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
            central.extend_from_slice(&20u16.to_le_bytes()); // version made by
            central.extend_from_slice(&20u16.to_le_bytes()); // version needed
            central.extend_from_slice(&0u16.to_le_bytes()); // flags
            central.extend_from_slice(&method_code.to_le_bytes());
            central.extend_from_slice(&0u16.to_le_bytes());
            central.extend_from_slice(&0x21u16.to_le_bytes());
            central.extend_from_slice(&crc32.to_le_bytes());
            central.extend_from_slice(&(payload.len() as u32).to_le_bytes());
            central.extend_from_slice(&uncompressed_size.to_le_bytes());
            central.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
            central.extend_from_slice(&0u16.to_le_bytes()); // extra length
            central.extend_from_slice(&0u16.to_le_bytes()); // comment length
            central.extend_from_slice(&0u16.to_le_bytes()); // disk number
            central.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
            central.extend_from_slice(&0u32.to_le_bytes()); // external attrs
            central.extend_from_slice(&(header_start as u32).to_le_bytes());
            central.extend_from_slice(name_bytes);
        }

        let mut eocd = Vec::with_capacity(22);
        eocd.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        eocd.extend_from_slice(&0u16.to_le_bytes()); // this disk
        eocd.extend_from_slice(&0u16.to_le_bytes()); // disk with central dir
        eocd.extend_from_slice(&(self.entries.len() as u16).to_le_bytes());
        eocd.extend_from_slice(&(self.entries.len() as u16).to_le_bytes());
        eocd.extend_from_slice(&(central.len() as u32).to_le_bytes());
        // Points at the end of the entries for now; the signer rewrites this
        // once it knows how long the signing block is.
        eocd.extend_from_slice(&(body.len() as u32).to_le_bytes());
        eocd.extend_from_slice(&0u16.to_le_bytes()); // comment length

        Ok(UnsignedApk {
            entries: body,
            central_directory: central,
            end_of_central_directory: eocd,
        })
    }
}

impl Default for Builder {
    fn default() -> Self {
        Builder::new()
    }
}
