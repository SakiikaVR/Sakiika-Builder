//! A minimal RGBA PNG encoder.
//!
//! Launcher icons have to be real bitmaps: the adaptive-icon layers are resource
//! entries, and replacing a bitmap entry in the template APK is what lets the
//! icon and its background colour change per project without touching the
//! resource table. Encoding them here avoids an image dependency.

use crc32fast::Hasher as Crc32;
use flate2::write::ZlibEncoder;
use flate2::Compression;
use std::io::Write;

/// Straight (non-premultiplied) RGBA, 8 bits per channel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rgba {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl Rgba {
    pub const TRANSPARENT: Rgba = Rgba { r: 0, g: 0, b: 0, a: 0 };

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

    /// Rough perceived brightness, used to pick a contrasting foreground.
    pub fn is_light(self) -> bool {
        let luma = 0.299 * self.r as f32 + 0.587 * self.g as f32 + 0.114 * self.b as f32;
        luma > 150.0
    }
}

/// An RGBA image being drawn into.
pub struct Canvas {
    pub width: u32,
    pub height: u32,
    pixels: Vec<Rgba>,
}

impl Canvas {
    pub fn new(width: u32, height: u32, fill: Rgba) -> Canvas {
        Canvas {
            width,
            height,
            pixels: vec![fill; (width * height) as usize],
        }
    }

    fn blend(&mut self, x: u32, y: u32, color: Rgba, coverage: f32) {
        if x >= self.width || y >= self.height || coverage <= 0.0 {
            return;
        }
        let alpha = color.a as f32 / 255.0 * coverage.min(1.0);
        if alpha <= 0.0 {
            return;
        }
        let index = (y * self.width + x) as usize;
        let dst = self.pixels[index];
        let dst_alpha = dst.a as f32 / 255.0;
        let out_alpha = alpha + dst_alpha * (1.0 - alpha);
        if out_alpha <= 0.0 {
            self.pixels[index] = Rgba::TRANSPARENT;
            return;
        }
        let mix = |s: u8, d: u8| {
            let value = (s as f32 * alpha + d as f32 * dst_alpha * (1.0 - alpha)) / out_alpha;
            value.round().clamp(0.0, 255.0) as u8
        };
        self.pixels[index] = Rgba::new(
            mix(color.r, dst.r),
            mix(color.g, dst.g),
            mix(color.b, dst.b),
            (out_alpha * 255.0).round().clamp(0.0, 255.0) as u8,
        );
    }

    /// Filled circle with a 1px antialiased edge.
    pub fn circle(&mut self, cx: f32, cy: f32, radius: f32, color: Rgba) {
        let min_x = ((cx - radius - 1.0).floor().max(0.0)) as u32;
        let max_x = ((cx + radius + 1.0).ceil().min(self.width as f32)) as u32;
        let min_y = ((cy - radius - 1.0).floor().max(0.0)) as u32;
        let max_y = ((cy + radius + 1.0).ceil().min(self.height as f32)) as u32;
        for y in min_y..max_y {
            for x in min_x..max_x {
                let dx = x as f32 + 0.5 - cx;
                let dy = y as f32 + 0.5 - cy;
                let distance = (dx * dx + dy * dy).sqrt();
                let coverage = (radius - distance + 0.5).clamp(0.0, 1.0);
                self.blend(x, y, color, coverage);
            }
        }
    }

    /// Axis-aligned rectangle with rounded corners.
    pub fn rounded_rect(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, radius: f32, color: Rgba) {
        let min_x = (x0.floor().max(0.0)) as u32;
        let max_x = (x1.ceil().min(self.width as f32)) as u32;
        let min_y = (y0.floor().max(0.0)) as u32;
        let max_y = (y1.ceil().min(self.height as f32)) as u32;
        for y in min_y..max_y {
            for x in min_x..max_x {
                let px = x as f32 + 0.5;
                let py = y as f32 + 0.5;
                // Distance to the rounded rectangle, negative inside.
                let dx = (x0 + radius - px).max(px - (x1 - radius)).max(0.0);
                let dy = (y0 + radius - py).max(py - (y1 - radius)).max(0.0);
                let outside = (dx * dx + dy * dy).sqrt() - radius;
                let inside_box = px >= x0 && px <= x1 && py >= y0 && py <= y1;
                if !inside_box {
                    continue;
                }
                let coverage = (-outside + 0.5).clamp(0.0, 1.0);
                self.blend(x, y, color, coverage);
            }
        }
    }

    pub fn encode(&self) -> Result<Vec<u8>, String> {
        // Filter type 0 (None) per scanline: the images are tiny and flat, so a
        // smarter filter would not pay for the complexity.
        let mut raw = Vec::with_capacity((self.width * self.height * 4 + self.height) as usize);
        for y in 0..self.height {
            raw.push(0);
            for x in 0..self.width {
                let pixel = self.pixels[(y * self.width + x) as usize];
                raw.extend_from_slice(&[pixel.r, pixel.g, pixel.b, pixel.a]);
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
        ihdr.extend_from_slice(&self.width.to_be_bytes());
        ihdr.extend_from_slice(&self.height.to_be_bytes());
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
    Canvas::new(size, size, color).encode()
}

/// Draws the squid mark centred on the canvas.
///
/// `head_ratio` sets the scale: an adaptive-icon foreground has to survive the
/// launcher's mask, so it uses a smaller mark than the legacy square icon.
fn draw_mark(canvas: &mut Canvas, color: Rgba, head_ratio: f32) {
    let s = canvas.width as f32;
    let cx = s / 2.0;
    let head_r = s * head_ratio;
    let head_cy = s * 0.41;

    canvas.circle(cx, head_cy, head_r, color);
    // Body, tapering down from the head.
    canvas.rounded_rect(
        cx - head_r * 0.86,
        head_cy,
        cx + head_r * 0.86,
        head_cy + head_r * 1.15,
        head_r * 0.5,
        color,
    );
    // Three tentacles.
    let tentacle_top = head_cy + head_r * 1.0;
    let tentacle_width = head_r * 0.15;
    for offset in [-1.0f32, 0.0, 1.0] {
        let x = cx + offset * head_r * 0.62;
        canvas.rounded_rect(
            x - tentacle_width,
            tentacle_top,
            x + tentacle_width,
            tentacle_top + head_r * 0.95,
            tentacle_width,
            color,
        );
    }
    // Eyes: a translucent dark wash reads correctly on any mark colour.
    let eye = Rgba::new(0, 0, 0, 90);
    canvas.circle(cx - head_r * 0.42, head_cy - head_r * 0.05, head_r * 0.2, eye);
    canvas.circle(cx + head_r * 0.42, head_cy - head_r * 0.05, head_r * 0.2, eye);
}

/// The adaptive-icon foreground layer: the mark on transparency.
pub fn default_foreground(size: u32, color: Rgba) -> Result<Vec<u8>, String> {
    let mut canvas = Canvas::new(size, size, Rgba::TRANSPARENT);
    draw_mark(&mut canvas, color, 0.155);
    canvas.encode()
}

/// The legacy square launcher icon: the mark on its background colour.
pub fn default_legacy(size: u32, background: Rgba, mark: Rgba) -> Result<Vec<u8>, String> {
    let mut canvas = Canvas::new(size, size, background);
    draw_mark(&mut canvas, mark, 0.19);
    canvas.encode()
}
