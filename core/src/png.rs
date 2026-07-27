//! Launcher icon bitmaps.
//!
//! Adaptive icon layers have to be real bitmaps: they are resource entries, and
//! replacing a bitmap entry in the template APK is what lets the icon change per
//! project without touching the resource table.
//!
//! The default icon is a prepared image embedded at compile time; the background
//! layer is a solid colour generated here, which needs only a PNG encoder.

use crc32fast::Hasher as Crc32;
use flate2::write::ZlibEncoder;
use flate2::Compression;
use std::io::Write;

/// The default icon, already scaled for an adaptive-icon foreground.
///
/// A launcher shows only the central 72dp of a 108dp layer, so the artwork is
/// pre-scaled to two thirds and centred — at full bleed its edges would be
/// cropped away.
const DEFAULT_FOREGROUND: &[u8] = include_bytes!("../assets/icon-foreground.png");

/// The pre-API-26 square icon. Vestigial, since the minimum is API 26, but the
/// resource entry exists and something has to fill it.
const DEFAULT_LEGACY: &[u8] = include_bytes!("../assets/icon-legacy.png");

/// Straight (non-premultiplied) RGBA, 8 bits per channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rgba {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl Rgba {
    pub const fn new(r: u8, g: u8, b: u8, a: u8) -> Rgba {
        Rgba { r, g, b, a }
    }

    /// Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` — the same forms Android accepts.
    pub fn parse(text: &str, fallback: Rgba) -> Rgba {
        let hex: String = text
            .trim()
            .trim_start_matches('#')
            .chars()
            .filter(|c| c.is_ascii_hexdigit())
            .collect();
        let byte = |slice: &str| u8::from_str_radix(slice, 16).unwrap_or(0);
        match hex.len() {
            3 => {
                let expand = |c: char| byte(&format!("{c}{c}"));
                let chars: Vec<char> = hex.chars().collect();
                Rgba::new(expand(chars[0]), expand(chars[1]), expand(chars[2]), 255)
            }
            6 => Rgba::new(byte(&hex[0..2]), byte(&hex[2..4]), byte(&hex[4..6]), 255),
            8 => Rgba::new(
                byte(&hex[2..4]),
                byte(&hex[4..6]),
                byte(&hex[6..8]),
                byte(&hex[0..2]),
            ),
            _ => fallback,
        }
    }
}

/// Encodes a single-colour RGBA image.
fn encode_solid(size: u32, color: Rgba) -> Result<Vec<u8>, String> {
    // Filter type 0 (None) per scanline: a flat image gains nothing from a
    // smarter filter, and zlib collapses the repetition anyway.
    let mut raw = Vec::with_capacity((size * size * 4 + size) as usize);
    for _ in 0..size {
        raw.push(0);
        for _ in 0..size {
            raw.extend_from_slice(&[color.r, color.g, color.b, color.a]);
        }
    }

    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::new(6));
    encoder
        .write_all(&raw)
        .map_err(|e| format!("PNG を圧縮できません: {e}"))?;
    let compressed = encoder
        .finish()
        .map_err(|e| format!("PNG を圧縮できません: {e}"))?;

    let mut out = Vec::with_capacity(compressed.len() + 64);
    out.extend_from_slice(&[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a]);

    let mut ihdr = Vec::with_capacity(13);
    ihdr.extend_from_slice(&size.to_be_bytes());
    ihdr.extend_from_slice(&size.to_be_bytes());
    ihdr.push(8); // bit depth
    ihdr.push(6); // colour type: RGBA
    ihdr.push(0); // deflate
    ihdr.push(0); // adaptive filtering
    ihdr.push(0); // no interlace
    write_chunk(&mut out, b"IHDR", &ihdr);
    write_chunk(&mut out, b"IDAT", &compressed);
    write_chunk(&mut out, b"IEND", &[]);
    Ok(out)
}

fn write_chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
    out.extend_from_slice(kind);
    out.extend_from_slice(data);
    let mut hasher = Crc32::new();
    hasher.update(kind);
    hasher.update(data);
    out.extend_from_slice(&hasher.finalize().to_be_bytes());
}

/// A solid colour image, used for the adaptive icon's background layer.
pub fn solid(size: u32, color: Rgba) -> Result<Vec<u8>, String> {
    encode_solid(size, color)
}

/// The adaptive-icon foreground layer of the default icon.
pub fn default_foreground() -> &'static [u8] {
    DEFAULT_FOREGROUND
}

/// The legacy square launcher icon of the default icon.
pub fn default_legacy() -> &'static [u8] {
    DEFAULT_LEGACY
}
