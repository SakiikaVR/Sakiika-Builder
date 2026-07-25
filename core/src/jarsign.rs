//! JAR signing (`META-INF/MANIFEST.MF` + `.SF` + signature block).
//!
//! An Android App Bundle is signed the JAR way, not with the APK signature
//! schemes — Play validates the upload with `jarsigner` semantics. So this exists
//! alongside [`crate::sign`] rather than replacing it.
//!
//! Layout produced:
//!
//! ```text
//!   META-INF/MANIFEST.MF   per-entry SHA-256 digests
//!   META-INF/SAKIIKA.SF    digests of MANIFEST.MF and of each of its sections
//!   META-INF/SAKIIKA.EC    detached PKCS#7 signature over the .SF bytes
//! ```
//!
//! The block is named `.EC` because the key is an EC key; `jarsigner` picks the
//! extension from the algorithm and verifiers look for any of `.RSA`/`.DSA`/`.EC`.

use crate::sign::Signer;
use sha2::{Digest, Sha256};

pub const SIGNATURE_NAME: &str = "SAKIIKA";
pub const MANIFEST_PATH: &str = "META-INF/MANIFEST.MF";

pub struct JarSignature {
    pub manifest: Vec<u8>,
    pub signature_file: Vec<u8>,
    pub signature_block: Vec<u8>,
}

impl JarSignature {
    pub fn signature_file_path(&self) -> String {
        format!("META-INF/{SIGNATURE_NAME}.SF")
    }

    pub fn signature_block_path(&self) -> String {
        format!("META-INF/{SIGNATURE_NAME}.EC")
    }
}

fn base64(data: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[(triple >> 18) as usize & 0x3f] as char);
        out.push(ALPHABET[(triple >> 12) as usize & 0x3f] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(triple >> 6) as usize & 0x3f] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[triple as usize & 0x3f] as char
        } else {
            '='
        });
    }
    out
}

/// Appends a manifest header, wrapping at 72 bytes as the JAR spec requires.
///
/// Continuation lines start with a single space, and the wrap is counted in
/// bytes: a verifier re-reads these exact bytes to check the `.SF` digests, so
/// the width has to match the spec rather than merely look tidy.
fn write_header(out: &mut Vec<u8>, key: &str, value: &str) {
    let line = format!("{key}: {value}");
    let bytes = line.as_bytes();
    let mut start = 0usize;
    let mut limit = 70usize; // 70 + CRLF = 72
    while start < bytes.len() {
        let end = (start + limit).min(bytes.len());
        if start > 0 {
            out.push(b' ');
        }
        out.extend_from_slice(&bytes[start..end]);
        out.extend_from_slice(b"\r\n");
        start = end;
        // Continuation lines spend one byte on the leading space.
        limit = 69;
    }
}

fn section(name: &str, digest_b64: &str) -> Vec<u8> {
    let mut out = Vec::new();
    write_header(&mut out, "Name", name);
    write_header(&mut out, "SHA-256-Digest", digest_b64);
    out.extend_from_slice(b"\r\n");
    out
}

/// Builds the three signature files for `entries`, which must be every entry the
/// archive will contain apart from the signature files themselves.
pub fn sign(entries: &[(String, Vec<u8>)], signer: &Signer) -> Result<JarSignature, String> {
    let created_by = format!("{} (Sakiika Builder)", env!("CARGO_PKG_VERSION"));

    let mut main_attributes = Vec::new();
    write_header(&mut main_attributes, "Manifest-Version", "1.0");
    write_header(&mut main_attributes, "Created-By", &created_by);
    main_attributes.extend_from_slice(b"\r\n");

    let mut manifest = main_attributes.clone();
    // Each entry's section is kept so the .SF can digest it individually.
    let mut sections: Vec<(String, Vec<u8>)> = Vec::with_capacity(entries.len());
    for (name, data) in entries {
        let digest = base64(&Sha256::digest(data));
        let body = section(name, &digest);
        manifest.extend_from_slice(&body);
        sections.push((name.clone(), body));
    }

    let mut signature_file = Vec::new();
    write_header(&mut signature_file, "Signature-Version", "1.0");
    write_header(
        &mut signature_file,
        "SHA-256-Digest-Manifest-Main-Attributes",
        &base64(&Sha256::digest(&main_attributes)),
    );
    write_header(
        &mut signature_file,
        "SHA-256-Digest-Manifest",
        &base64(&Sha256::digest(&manifest)),
    );
    write_header(&mut signature_file, "Created-By", &created_by);
    signature_file.extend_from_slice(b"\r\n");
    for (name, body) in &sections {
        write_header(&mut signature_file, "Name", name);
        write_header(
            &mut signature_file,
            "SHA-256-Digest",
            &base64(&Sha256::digest(body)),
        );
        signature_file.extend_from_slice(b"\r\n");
    }

    let signature_block = signer.pkcs7_detached(&signature_file)?;

    Ok(JarSignature {
        manifest,
        signature_file,
        signature_block,
    })
}
