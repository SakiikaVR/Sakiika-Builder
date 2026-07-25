//! APK Signature Scheme v2, implemented here so signing needs no JVM.
//!
//! `apksigner` and `keytool` are Java programs. Reimplementing the parts we need
//! is what lets the finished tool run on a machine with no JDK at all.
//!
//! Layout of a signed APK:
//!
//! ```text
//!   [ entry data ............ ]  <- digested as section 1
//!   [ APK Signing Block ..... ]  <- inserted here, not digested
//!   [ central directory ..... ]  <- digested as section 2
//!   [ end of central directory]  <- digested as section 3, with its
//!                                   "offset of central directory" field
//!                                   rewritten to point at the signing block
//! ```
//!
//! Reference: https://source.android.com/docs/security/features/apksigning/v2

use std::fs;
use std::path::Path;
use std::time::Duration;

use der::Encode;
use p256::ecdsa::{DerSignature, SigningKey};
use p256::pkcs8::{DecodePrivateKey, EncodePrivateKey, EncodePublicKey};
use sha2::{Digest, Sha256};

/// Chunk size the spec mandates for the chunked digests.
const CHUNK_SIZE: usize = 1024 * 1024;

/// "APK Sig Block 42"
const MAGIC: &[u8; 16] = b"APK Sig Block 42";

/// ID of the APK Signature Scheme v2 block.
const V2_BLOCK_ID: u32 = 0x7109_871a;
/// ID of the APK Signature Scheme v3 block.
const V3_BLOCK_ID: u32 = 0xf053_68c0;

/// SIGNATURE_ECDSA_WITH_SHA256. Understood by Android 7.0 (API 24) and later,
/// which is why the no-JVM path requires minSdk >= 24.
const SIG_ALGO_ECDSA_SHA256: u32 = 0x0201;

pub const MIN_SDK_FOR_V2_ONLY: u32 = 24;

/// A signing identity: an EC P-256 key plus its self-signed certificate.
pub struct Signer {
    key: SigningKey,
    /// DER-encoded X.509 certificate.
    cert_der: Vec<u8>,
    /// DER-encoded SubjectPublicKeyInfo.
    public_key_der: Vec<u8>,
    /// DER-encoded issuer `Name`, needed by PKCS#7 for JAR signing.
    issuer_der: Vec<u8>,
    /// Serial number as the raw INTEGER contents.
    serial: Vec<u8>,
}

impl Signer {
    /// Loads the key from `path`, creating it on first use.
    ///
    /// Keeping the same key across builds matters: Android only accepts an update
    /// signed by the same certificate as the installed app.
    pub fn load_or_create(path: &Path, subject: &str) -> Result<Signer, String> {
        if path.is_file() {
            return Signer::load(path, subject);
        }
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("鍵の保存先を作れません {}: {e}", parent.display()))?;
        }
        let key = SigningKey::random(&mut rand::thread_rng());
        let pem = key
            .to_pkcs8_pem(der::pem::LineEnding::LF)
            .map_err(|e| format!("鍵を書き出せません: {e}"))?;
        fs::write(path, pem.as_bytes())
            .map_err(|e| format!("鍵を保存できません {}: {e}", path.display()))?;
        Signer::from_key(key, subject)
    }

    fn load(path: &Path, subject: &str) -> Result<Signer, String> {
        let pem = fs::read_to_string(path)
            .map_err(|e| format!("鍵を読めません {}: {e}", path.display()))?;
        let key = SigningKey::from_pkcs8_pem(&pem).map_err(|e| {
            format!(
                "鍵の形式が不正です {}: {e}\n\
                 さきいかビルダーが作った鍵 (.pem) を指定してください。",
                path.display()
            )
        })?;
        Signer::from_key(key, subject)
    }

    fn from_key(key: SigningKey, subject: &str) -> Result<Signer, String> {
        let certificate = self_signed_cert(&key, subject)?;
        let cert_der = certificate
            .to_der()
            .map_err(|e| format!("証明書を DER にできません: {e}"))?;
        let issuer_der = certificate
            .tbs_certificate
            .issuer
            .to_der()
            .map_err(|e| format!("発行者名を DER にできません: {e}"))?;
        let serial = certificate.tbs_certificate.serial_number.as_bytes().to_vec();
        let public_key_der = key
            .verifying_key()
            .to_public_key_der()
            .map_err(|e| format!("公開鍵を書き出せません: {e}"))?
            .as_bytes()
            .to_vec();
        Ok(Signer {
            key,
            cert_der,
            public_key_der,
            issuer_der,
            serial,
        })
    }

    pub fn certificate_der(&self) -> &[u8] {
        &self.cert_der
    }

    /// Raw DER ECDSA signature over `data`.
    pub fn sign_der(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        sign_bytes(self, data)
    }

    /// A detached PKCS#7 `SignedData`, which is what a JAR signature block is.
    ///
    /// Used for AAB signing: an App Bundle is signed the JAR way
    /// (`META-INF/*.SF` plus a signature block), not with the APK signing
    /// schemes, so this cannot reuse the v2 path.
    pub fn pkcs7_detached(&self, content: &[u8]) -> Result<Vec<u8>, String> {
        let signature = self.sign_der(content)?;

        // AlgorithmIdentifier SEQUENCE { OID sha256 }  (absent params, as X.509
        // requires for SHA-2 — an explicit NULL is tolerated but not canonical)
        let sha256_algo = der_seq(oid(&OID_SHA256));
        let ecdsa_algo = der_seq(oid(&OID_ECDSA_SHA256));

        let mut signer_info = Vec::new();
        signer_info.extend(der_integer_u32(1));
        // IssuerAndSerialNumber SEQUENCE { Name, CertificateSerialNumber }
        let mut issuer_and_serial = Vec::new();
        issuer_and_serial.extend_from_slice(&self.issuer_der);
        issuer_and_serial.extend(tlv(0x02, self.serial.clone()));
        signer_info.extend(der_seq(issuer_and_serial));
        signer_info.extend(sha256_algo.clone());
        signer_info.extend(ecdsa_algo);
        signer_info.extend(tlv(0x04, signature)); // encryptedDigest OCTET STRING

        let mut signed_data = Vec::new();
        signed_data.extend(der_integer_u32(1)); // version
        signed_data.extend(tlv(0x31, sha256_algo)); // digestAlgorithms SET
        // Detached: contentInfo names the type but carries no content.
        signed_data.extend(der_seq(oid(&OID_PKCS7_DATA)));
        signed_data.extend(tlv(0xa0, self.cert_der.clone())); // [0] certificates
        signed_data.extend(tlv(0x31, der_seq(signer_info))); // signerInfos SET

        let mut content_info = Vec::new();
        content_info.extend(oid(&OID_PKCS7_SIGNED_DATA));
        content_info.extend(tlv(0xa0, der_seq(signed_data)));
        Ok(der_seq(content_info))
    }

    /// SHA-256 fingerprint of the certificate, as apksigner prints it.
    pub fn certificate_fingerprint(&self) -> String {
        let digest = Sha256::digest(&self.cert_der);
        digest.iter().map(|b| format!("{b:02x}")).collect()
    }
}

// ------------------------------------------------------------- DER helpers
//
// Just enough ASN.1 to assemble a PKCS#7 SignedData. Hand-written rather than
// pulled from a CMS library because the structure is small, fixed, and easier to
// audit against the spec in this form.

const OID_SHA256: [u8; 9] = [0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01];
const OID_ECDSA_SHA256: [u8; 8] = [0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02];
const OID_PKCS7_DATA: [u8; 9] = [0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x07, 0x01];
const OID_PKCS7_SIGNED_DATA: [u8; 9] = [0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x07, 0x02];

/// Wraps `body` in a DER tag-length-value, using the definite-length form.
fn tlv(tag: u8, body: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 6);
    out.push(tag);
    let length = body.len();
    if length < 0x80 {
        out.push(length as u8);
    } else {
        // Long form: 0x80 | number-of-length-bytes, then the length big-endian.
        let bytes = length.to_be_bytes();
        let first = bytes.iter().position(|b| *b != 0).unwrap_or(bytes.len() - 1);
        let significant = &bytes[first..];
        out.push(0x80 | significant.len() as u8);
        out.extend_from_slice(significant);
    }
    out.extend_from_slice(&body);
    out
}

fn der_seq(body: Vec<u8>) -> Vec<u8> {
    tlv(0x30, body)
}

fn oid(bytes: &[u8]) -> Vec<u8> {
    tlv(0x06, bytes.to_vec())
}

fn der_integer_u32(value: u32) -> Vec<u8> {
    let bytes = value.to_be_bytes();
    let first = bytes.iter().position(|b| *b != 0).unwrap_or(3);
    let mut significant = bytes[first..].to_vec();
    // A leading bit of 1 would read as negative, so pad with a zero byte.
    if significant[0] & 0x80 != 0 {
        significant.insert(0, 0);
    }
    tlv(0x02, significant)
}

/// Builds a self-signed certificate for the key.
///
/// Android never validates the chain — the certificate only has to be
/// well-formed and stay identical between updates.
fn self_signed_cert(
    key: &SigningKey,
    subject: &str,
) -> Result<x509_cert::Certificate, String> {
    use x509_cert::builder::{Builder, CertificateBuilder, Profile};
    use x509_cert::name::Name;
    use x509_cert::serial_number::SerialNumber;
    use x509_cert::time::Validity;

    let name: Name = subject
        .parse()
        .map_err(|e| format!("証明書の名前が不正です '{subject}': {e}"))?;
    // 30 years: an APK signed with an expired certificate still installs, but a
    // long validity avoids surprises with tooling that checks.
    let validity = Validity::from_now(Duration::from_secs(60 * 60 * 24 * 365 * 30))
        .map_err(|e| format!("証明書の有効期間を作れません: {e}"))?;
    let serial = SerialNumber::from(1u32);
    let verifying_key = *key.verifying_key();
    let spki = verifying_key
        .to_public_key_der()
        .map_err(|e| format!("公開鍵を書き出せません: {e}"))?;
    let spki_ref = spki
        .decode_msg::<x509_cert::spki::SubjectPublicKeyInfoOwned>()
        .map_err(|e| format!("公開鍵を解釈できません: {e}"))?;

    // A leaf profile, self-issued. The root profile would set KeyUsage to
    // keyCertSign|cRLSign, and `jarsigner` then refuses the certificate with
    // "Key usage restricted: cannot be used for digital signatures" — which
    // breaks AAB signing even though Android's APK schemes do not check.
    let builder = CertificateBuilder::new(
        Profile::Leaf {
            issuer: name.clone(),
            enable_key_agreement: false,
            enable_key_encipherment: false,
        },
        serial,
        validity,
        name,
        spki_ref,
        key,
    )
    .map_err(|e| format!("証明書を作れません: {e}"))?;

    builder
        .build::<DerSignature>()
        .map_err(|e| format!("証明書に署名できません: {e}"))
}

// ------------------------------------------------------------- digest helpers

fn chunk_digest(chunk: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update([0xa5u8]);
    hasher.update((chunk.len() as u32).to_le_bytes());
    hasher.update(chunk);
    hasher.finalize().into()
}

/// The chunked-SHA256 content digest over the three APK sections.
fn content_digest(sections: [&[u8]; 3]) -> [u8; 32] {
    let mut chunk_digests: Vec<[u8; 32]> = Vec::new();
    for section in sections {
        for chunk in section.chunks(CHUNK_SIZE) {
            chunk_digests.push(chunk_digest(chunk));
        }
    }
    let mut hasher = Sha256::new();
    hasher.update([0x5au8]);
    hasher.update((chunk_digests.len() as u32).to_le_bytes());
    for digest in &chunk_digests {
        hasher.update(digest);
    }
    hasher.finalize().into()
}

fn length_prefixed(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 4);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    out
}

// -------------------------------------------------------------- signing block

/// The unsigned APK, split at the places the spec digests separately.
pub struct UnsignedApk {
    /// Everything from offset 0 up to the start of the central directory.
    pub entries: Vec<u8>,
    pub central_directory: Vec<u8>,
    /// 22 bytes (no zip comment), with the central-directory offset still
    /// pointing at `entries.len()`.
    pub end_of_central_directory: Vec<u8>,
}

impl UnsignedApk {
    /// A plain, unsigned zip. Used for the template APK, which is only ever read
    /// back by this tool and so needs no signature.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(
            self.entries.len() + self.central_directory.len() + self.end_of_central_directory.len(),
        );
        out.extend_from_slice(&self.entries);
        out.extend_from_slice(&self.central_directory);
        out.extend_from_slice(&self.end_of_central_directory);
        out
    }
}

/// Produces the final signed APK bytes.
pub fn sign(apk: &UnsignedApk, signer: &Signer, min_sdk: u32) -> Result<Vec<u8>, String> {
    if min_sdk < MIN_SDK_FOR_V2_ONLY {
        return Err(format!(
            "minSdk {min_sdk} は v2 署名だけでは足りません（Android 7.0 / API {MIN_SDK_FOR_V2_ONLY} 以上が必要）。\n\
             minSdk を {MIN_SDK_FOR_V2_ONLY} 以上にしてください。",
        ));
    }

    // The EOCD used for the digest must claim the central directory starts where
    // the signing block will be inserted, which is the current end of the entries.
    let digest = content_digest([
        &apk.entries,
        &apk.central_directory,
        &apk.end_of_central_directory,
    ]);

    let signed_data = build_signed_data(&digest, &signer.cert_der, None);
    let signature = sign_bytes(signer, &signed_data)?;
    let v2_signer_block = build_signer(&signed_data, &signature, &signer.public_key_der);
    let v2_value = length_prefixed(&length_prefixed(&v2_signer_block));

    // v3 repeats the signature with an SDK range, which is what lets newer
    // platforms support key rotation. Same digest, different signed data.
    let signed_data_v3 = build_signed_data(&digest, &signer.cert_der, Some((min_sdk, i32::MAX)));
    let signature_v3 = sign_bytes(signer, &signed_data_v3)?;
    let v3_signer_block =
        build_signer_v3(&signed_data_v3, &signature_v3, &signer.public_key_der, min_sdk);
    let v3_value = length_prefixed(&length_prefixed(&v3_signer_block));

    let block = build_apk_signing_block(&[(V2_BLOCK_ID, v2_value), (V3_BLOCK_ID, v3_value)]);

    let mut out =
        Vec::with_capacity(apk.entries.len() + block.len() + apk.central_directory.len() + 32);
    out.extend_from_slice(&apk.entries);
    out.extend_from_slice(&block);
    out.extend_from_slice(&apk.central_directory);

    // The real EOCD points past the signing block.
    let mut eocd = apk.end_of_central_directory.clone();
    let new_offset = (apk.entries.len() + block.len()) as u32;
    if eocd.len() < 22 {
        return Err("EOCD が壊れています".to_string());
    }
    eocd[16..20].copy_from_slice(&new_offset.to_le_bytes());
    out.extend_from_slice(&eocd);
    Ok(out)
}

fn sign_bytes(signer: &Signer, data: &[u8]) -> Result<Vec<u8>, String> {
    use p256::ecdsa::signature::Signer as _;
    let signature: DerSignature = signer
        .key
        .try_sign(data)
        .map_err(|e| format!("署名に失敗しました: {e}"))?;
    Ok(signature.as_bytes().to_vec())
}

/// signed data = digests + certificates + additional attributes
fn build_signed_data(
    digest: &[u8; 32],
    cert_der: &[u8],
    sdk_range: Option<(u32, i32)>,
) -> Vec<u8> {
    let mut digest_entry = Vec::new();
    digest_entry.extend_from_slice(&SIG_ALGO_ECDSA_SHA256.to_le_bytes());
    digest_entry.extend_from_slice(&length_prefixed(digest));
    let digests = length_prefixed(&length_prefixed(&digest_entry));

    let certificates = length_prefixed(&length_prefixed(cert_der));

    let mut out = Vec::new();
    out.extend_from_slice(&digests);
    out.extend_from_slice(&certificates);
    match sdk_range {
        // v3 signed data additionally carries the SDK range, before the attributes.
        Some((min_sdk, max_sdk)) => {
            let mut with_range = Vec::new();
            with_range.extend_from_slice(&digests);
            with_range.extend_from_slice(&certificates);
            with_range.extend_from_slice(&min_sdk.to_le_bytes());
            with_range.extend_from_slice(&max_sdk.to_le_bytes());
            // No additional attributes.
            with_range.extend_from_slice(&0u32.to_le_bytes());
            return with_range;
        }
        None => {
            // No additional attributes.
            out.extend_from_slice(&0u32.to_le_bytes());
        }
    }
    out
}

fn build_signer(signed_data: &[u8], signature: &[u8], public_key: &[u8]) -> Vec<u8> {
    let mut signature_entry = Vec::new();
    signature_entry.extend_from_slice(&SIG_ALGO_ECDSA_SHA256.to_le_bytes());
    signature_entry.extend_from_slice(&length_prefixed(signature));

    let mut out = Vec::new();
    out.extend_from_slice(&length_prefixed(signed_data));
    out.extend_from_slice(&length_prefixed(&length_prefixed(&signature_entry)));
    out.extend_from_slice(&length_prefixed(public_key));
    out
}

fn build_signer_v3(
    signed_data: &[u8],
    signature: &[u8],
    public_key: &[u8],
    min_sdk: u32,
) -> Vec<u8> {
    let mut signature_entry = Vec::new();
    signature_entry.extend_from_slice(&SIG_ALGO_ECDSA_SHA256.to_le_bytes());
    signature_entry.extend_from_slice(&length_prefixed(signature));

    let mut out = Vec::new();
    out.extend_from_slice(&length_prefixed(signed_data));
    // The signer-level SDK range must match the one inside signed data.
    out.extend_from_slice(&min_sdk.to_le_bytes());
    out.extend_from_slice(&i32::MAX.to_le_bytes());
    out.extend_from_slice(&length_prefixed(&length_prefixed(&signature_entry)));
    out.extend_from_slice(&length_prefixed(public_key));
    out
}

fn build_apk_signing_block(pairs: &[(u32, Vec<u8>)]) -> Vec<u8> {
    let mut body = Vec::new();
    for (id, value) in pairs {
        let length = (value.len() + 4) as u64;
        body.extend_from_slice(&length.to_le_bytes());
        body.extend_from_slice(&id.to_le_bytes());
        body.extend_from_slice(value);
    }

    // The block must be a multiple of 4096 bytes, padded with a dedicated pair.
    const PADDING_ID: u32 = 0x4272_6577;
    let mut size = body.len() + 8 + 8 + 16; // size field, trailing size, magic
    let remainder = size % 4096;
    if remainder != 0 {
        let mut padding_needed = 4096 - remainder;
        // A pair costs 12 bytes of overhead; if there is not room for one, add a
        // whole extra page so the arithmetic stays simple.
        while padding_needed < 12 {
            padding_needed += 4096;
        }
        let payload = vec![0u8; padding_needed - 12];
        let length = (payload.len() + 4) as u64;
        body.extend_from_slice(&length.to_le_bytes());
        body.extend_from_slice(&PADDING_ID.to_le_bytes());
        body.extend_from_slice(&payload);
        size += padding_needed;
    }

    let size_of_block = (body.len() + 8 + 16) as u64;
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&size_of_block.to_le_bytes());
    out.extend_from_slice(&body);
    out.extend_from_slice(&size_of_block.to_le_bytes());
    out.extend_from_slice(MAGIC);
    out
}
