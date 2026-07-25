//! A tiny protobuf writer.
//!
//! Android App Bundles store the manifest and the resource table as protobuf
//! rather than the binary formats an APK uses, so building an AAB means encoding
//! protobuf. Only the encoding side is needed, and only for a handful of
//! messages, so the wire format is written directly instead of pulling in a
//! code generator.
//!
//! Wire format: each field is a varint tag of `(field_number << 3) | wire_type`,
//! followed by the payload. Wire type 0 is a varint, 2 is length-delimited, 5 is
//! a fixed 32-bit value.

const WIRE_VARINT: u32 = 0;
const WIRE_LENGTH: u32 = 2;
const WIRE_FIXED32: u32 = 5;

#[derive(Default)]
pub struct Message {
    bytes: Vec<u8>,
}

impl Message {
    pub fn new() -> Message {
        Message { bytes: Vec::new() }
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.bytes
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    fn tag(&mut self, field: u32, wire: u32) {
        self.varint(((field << 3) | wire) as u64);
    }

    fn varint(&mut self, mut value: u64) {
        loop {
            let byte = (value & 0x7f) as u8;
            value >>= 7;
            if value == 0 {
                self.bytes.push(byte);
                return;
            }
            self.bytes.push(byte | 0x80);
        }
    }

    /// `uint32` / `uint64` / `bool` / enum.
    pub fn u32(&mut self, field: u32, value: u32) {
        self.tag(field, WIRE_VARINT);
        self.varint(value as u64);
    }

    /// `int32`. Negative values are sign-extended to 64 bits, exactly as the
    /// protobuf spec requires — a naive 32-bit varint would be rejected.
    pub fn i32(&mut self, field: u32, value: i32) {
        self.tag(field, WIRE_VARINT);
        self.varint(value as i64 as u64);
    }

    pub fn bool(&mut self, field: u32, value: bool) {
        self.tag(field, WIRE_VARINT);
        self.varint(u64::from(value));
    }

    pub fn f32(&mut self, field: u32, value: f32) {
        self.tag(field, WIRE_FIXED32);
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    pub fn string(&mut self, field: u32, value: &str) {
        self.bytes_field(field, value.as_bytes());
    }

    pub fn bytes_field(&mut self, field: u32, value: &[u8]) {
        self.tag(field, WIRE_LENGTH);
        self.varint(value.len() as u64);
        self.bytes.extend_from_slice(value);
    }

    /// A nested message. Empty messages still need to be written when their
    /// presence is the signal — `Primitive.null_value` for instance.
    pub fn message(&mut self, field: u32, value: &Message) {
        self.bytes_field(field, value.as_bytes());
    }
}
