//! Writes Android's binary XML (AXML) format, so `AndroidManifest.xml` can be
//! produced without `aapt2`.
//!
//! A manifest inside an APK is not text — it is a chunked binary encoding with a
//! shared string pool and typed attribute values. Generating it here is what lets
//! a build reuse one prebuilt template APK and still change the package name,
//! label, permissions and so on.
//!
//! Layout:
//!
//! ```text
//!   RES_XML_TYPE
//!     RES_STRING_POOL_TYPE          all strings, attribute names first
//!     RES_XML_RESOURCE_MAP_TYPE     string index -> android attr resource id
//!     RES_XML_START_NAMESPACE_TYPE  xmlns:android
//!       RES_XML_START_ELEMENT_TYPE  … nested elements …
//!       RES_XML_END_ELEMENT_TYPE
//!     RES_XML_END_NAMESPACE_TYPE
//! ```
//!
//! Reference: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h

use std::collections::HashMap;

pub const ANDROID_NS: &str = "http://schemas.android.com/apk/res/android";
const ANDROID_PREFIX: &str = "android";

const RES_STRING_POOL_TYPE: u16 = 0x0001;
const RES_XML_TYPE: u16 = 0x0003;
const RES_XML_START_NAMESPACE_TYPE: u16 = 0x0100;
const RES_XML_END_NAMESPACE_TYPE: u16 = 0x0101;
const RES_XML_START_ELEMENT_TYPE: u16 = 0x0102;
const RES_XML_END_ELEMENT_TYPE: u16 = 0x0103;
const RES_XML_RESOURCE_MAP_TYPE: u16 = 0x0180;

const TYPE_REFERENCE: u8 = 0x01;
const TYPE_STRING: u8 = 0x03;
const TYPE_INT_DEC: u8 = 0x10;
const TYPE_INT_HEX: u8 = 0x11;
const TYPE_INT_BOOLEAN: u8 = 0x12;

const NO_ENTRY: u32 = 0xFFFF_FFFF;

/// Android attribute resource IDs, read out of `android.jar`'s `R$attr`.
/// These are public API and stable across platform versions.
pub mod attr {
    pub const THEME: u32 = 0x0101_0000;
    pub const LABEL: u32 = 0x0101_0001;
    pub const ICON: u32 = 0x0101_0002;
    pub const NAME: u32 = 0x0101_0003;
    pub const DEBUGGABLE: u32 = 0x0101_000f;
    pub const EXPORTED: u32 = 0x0101_0010;
    pub const AUTHORITIES: u32 = 0x0101_0018;
    pub const GRANT_URI_PERMISSIONS: u32 = 0x0101_001b;
    pub const LAUNCH_MODE: u32 = 0x0101_001d;
    pub const SCREEN_ORIENTATION: u32 = 0x0101_001e;
    pub const CONFIG_CHANGES: u32 = 0x0101_001f;
    pub const MIN_SDK_VERSION: u32 = 0x0101_020c;
    pub const VERSION_CODE: u32 = 0x0101_021b;
    pub const VERSION_NAME: u32 = 0x0101_021c;
    pub const TARGET_SDK_VERSION: u32 = 0x0101_0270;
    pub const MAX_SDK_VERSION: u32 = 0x0101_0271;
    pub const ALLOW_BACKUP: u32 = 0x0101_0280;
    pub const REQUIRED: u32 = 0x0101_028e;
    pub const HARDWARE_ACCELERATED: u32 = 0x0101_02d3;
    pub const SUPPORTS_RTL: u32 = 0x0101_03af;
    pub const EXTRACT_NATIVE_LIBS: u32 = 0x0101_04ea;
    pub const USES_CLEARTEXT_TRAFFIC: u32 = 0x0101_04ec;
    pub const RESIZEABLE_ACTIVITY: u32 = 0x0101_04f6;
    pub const ROUND_ICON: u32 = 0x0101_052c;
    pub const REQUEST_LEGACY_EXTERNAL_STORAGE: u32 = 0x0101_0603;
    pub const PRESERVE_LEGACY_EXTERNAL_STORAGE: u32 = 0x0101_0614;
    pub const SCHEME: u32 = 0x0101_0027;
    pub const MIME_TYPE: u32 = 0x0101_0026;
}

/// `android:configChanges` flag values.
pub mod config_changes {
    pub const LOCALE: u32 = 0x0004;
    pub const KEYBOARD_HIDDEN: u32 = 0x0020;
    pub const ORIENTATION: u32 = 0x0080;
    pub const SCREEN_LAYOUT: u32 = 0x0100;
    pub const UI_MODE: u32 = 0x0200;
    pub const SCREEN_SIZE: u32 = 0x0400;
    pub const SMALLEST_SCREEN_SIZE: u32 = 0x0800;
    pub const DENSITY: u32 = 0x1000;
    pub const FONT_SCALE: u32 = 0x4000_0000;
}

/// `android:screenOrientation` enum values.
pub mod orientation {
    pub const UNSPECIFIED: i32 = -1;
    pub const LANDSCAPE: i32 = 0;
    pub const PORTRAIT: i32 = 1;
    pub const SENSOR: i32 = 4;
}

#[derive(Debug, Clone)]
pub enum Value {
    Str(String),
    Bool(bool),
    Int(i32),
    Hex(u32),
    /// A reference to a resource in the APK, e.g. `@style/AppTheme`.
    Ref(u32),
}

#[derive(Debug, Clone)]
pub struct Attr {
    pub name: String,
    /// None for attributes with no namespace, such as `package`.
    pub id: Option<u32>,
    pub value: Value,
}

impl Attr {
    /// An `android:`-namespaced attribute.
    pub fn android(name: &str, id: u32, value: Value) -> Attr {
        Attr { name: name.to_string(), id: Some(id), value }
    }

    /// A bare attribute with no namespace (only `package` in practice).
    pub fn plain(name: &str, value: Value) -> Attr {
        Attr { name: name.to_string(), id: None, value }
    }
}

#[derive(Debug, Clone)]
pub struct Element {
    pub name: String,
    pub attrs: Vec<Attr>,
    pub children: Vec<Element>,
}

impl Element {
    pub fn new(name: &str) -> Element {
        Element { name: name.to_string(), attrs: Vec::new(), children: Vec::new() }
    }

    pub fn attr(mut self, attr: Attr) -> Element {
        self.attrs.push(attr);
        self
    }

    pub fn child(mut self, child: Element) -> Element {
        self.children.push(child);
        self
    }
}

/// Builds the string pool, keeping attribute names in the leading indices.
///
/// The resource map is a flat array indexed by string index, so every attribute
/// name has to come before any other string or the mapping cannot be expressed.
struct Interner {
    /// Attribute names, in first-use order, paired with their resource id.
    attr_names: Vec<(String, u32)>,
    attr_index: HashMap<String, usize>,
    others: Vec<String>,
    other_index: HashMap<String, usize>,
}

impl Interner {
    fn new() -> Interner {
        Interner {
            attr_names: Vec::new(),
            attr_index: HashMap::new(),
            others: Vec::new(),
            other_index: HashMap::new(),
        }
    }

    fn intern_attr(&mut self, name: &str, id: Option<u32>) {
        if let Some(&existing) = self.attr_index.get(name) {
            // A later use with a real id wins over an earlier placeholder.
            if let Some(id) = id {
                self.attr_names[existing].1 = id;
            }
            return;
        }
        self.attr_index.insert(name.to_string(), self.attr_names.len());
        self.attr_names.push((name.to_string(), id.unwrap_or(0)));
    }

    fn intern(&mut self, text: &str) {
        if self.attr_index.contains_key(text) || self.other_index.contains_key(text) {
            return;
        }
        self.other_index.insert(text.to_string(), self.others.len());
        self.others.push(text.to_string());
    }

    fn index_of(&self, text: &str) -> u32 {
        if let Some(&i) = self.attr_index.get(text) {
            return i as u32;
        }
        match self.other_index.get(text) {
            Some(&i) => (self.attr_names.len() + i) as u32,
            // Interning happens in a full pass before encoding, so a miss here
            // would be a bug in this module rather than bad input.
            None => panic!("文字列が string pool にありません: {text}"),
        }
    }

    fn all(&self) -> Vec<&str> {
        self.attr_names
            .iter()
            .map(|(name, _)| name.as_str())
            .chain(self.others.iter().map(|s| s.as_str()))
            .collect()
    }
}

fn collect(element: &Element, interner: &mut Interner) {
    for attr in &element.attrs {
        interner.intern_attr(&attr.name, attr.id);
    }
    for child in &element.children {
        collect(child, interner);
    }
}

fn collect_values(element: &Element, interner: &mut Interner) {
    interner.intern(&element.name);
    for attr in &element.attrs {
        if let Value::Str(text) = &attr.value {
            interner.intern(text);
        }
    }
    for child in &element.children {
        collect_values(child, interner);
    }
}

pub fn encode(root: &Element) -> Result<Vec<u8>, String> {
    let mut interner = Interner::new();
    // Attribute names first, then everything else.
    collect(root, &mut interner);
    interner.intern(ANDROID_PREFIX);
    interner.intern(ANDROID_NS);
    collect_values(root, &mut interner);

    let pool = encode_string_pool(&interner.all());
    let resource_map = encode_resource_map(&interner.attr_names);

    let mut nodes = Vec::new();
    encode_namespace(&mut nodes, RES_XML_START_NAMESPACE_TYPE, &interner);
    encode_element(&mut nodes, root, &interner)?;
    encode_namespace(&mut nodes, RES_XML_END_NAMESPACE_TYPE, &interner);

    let total = 8 + pool.len() + resource_map.len() + nodes.len();
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(&RES_XML_TYPE.to_le_bytes());
    out.extend_from_slice(&8u16.to_le_bytes());
    out.extend_from_slice(&(total as u32).to_le_bytes());
    out.extend_from_slice(&pool);
    out.extend_from_slice(&resource_map);
    out.extend_from_slice(&nodes);
    Ok(out)
}

fn encode_string_pool(strings: &[&str]) -> Vec<u8> {
    // UTF-16 rather than the UTF-8 variant: it needs no length varints and every
    // Android version reads it, including for non-ASCII app labels.
    let mut offsets: Vec<u32> = Vec::with_capacity(strings.len());
    let mut data: Vec<u8> = Vec::new();
    for text in strings {
        offsets.push(data.len() as u32);
        let units: Vec<u16> = text.encode_utf16().collect();
        if units.len() > 0x7FFF {
            // The long form exists but no manifest string comes close.
            data.extend_from_slice(&0x7FFFu16.to_le_bytes());
        }
        data.extend_from_slice(&(units.len() as u16).to_le_bytes());
        for unit in &units {
            data.extend_from_slice(&unit.to_le_bytes());
        }
        data.extend_from_slice(&0u16.to_le_bytes());
    }
    while data.len() % 4 != 0 {
        data.push(0);
    }

    let header_size = 28usize;
    let strings_start = header_size + offsets.len() * 4;
    let size = strings_start + data.len();

    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&RES_STRING_POOL_TYPE.to_le_bytes());
    out.extend_from_slice(&(header_size as u16).to_le_bytes());
    out.extend_from_slice(&(size as u32).to_le_bytes());
    out.extend_from_slice(&(strings.len() as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // style count
    out.extend_from_slice(&0u32.to_le_bytes()); // flags: UTF-16, unsorted
    out.extend_from_slice(&(strings_start as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // styles start
    for offset in &offsets {
        out.extend_from_slice(&offset.to_le_bytes());
    }
    out.extend_from_slice(&data);
    out
}

fn encode_resource_map(attr_names: &[(String, u32)]) -> Vec<u8> {
    let size = 8 + attr_names.len() * 4;
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&RES_XML_RESOURCE_MAP_TYPE.to_le_bytes());
    out.extend_from_slice(&8u16.to_le_bytes());
    out.extend_from_slice(&(size as u32).to_le_bytes());
    for (_, id) in attr_names {
        out.extend_from_slice(&id.to_le_bytes());
    }
    out
}

fn encode_node_header(out: &mut Vec<u8>, kind: u16, size: usize) {
    out.extend_from_slice(&kind.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes()); // header size
    out.extend_from_slice(&(size as u32).to_le_bytes());
    out.extend_from_slice(&1u32.to_le_bytes()); // line number
    out.extend_from_slice(&NO_ENTRY.to_le_bytes()); // comment
}

fn encode_namespace(out: &mut Vec<u8>, kind: u16, interner: &Interner) {
    encode_node_header(out, kind, 24);
    out.extend_from_slice(&interner.index_of(ANDROID_PREFIX).to_le_bytes());
    out.extend_from_slice(&interner.index_of(ANDROID_NS).to_le_bytes());
}

fn encode_element(out: &mut Vec<u8>, element: &Element, interner: &Interner) -> Result<(), String> {
    // Sorted by resource id, which is the order aapt2 emits and what some
    // third-party manifest readers assume.
    let mut attrs: Vec<&Attr> = element.attrs.iter().collect();
    attrs.sort_by_key(|a| a.id.unwrap_or(0));

    let size = 16 + 20 + attrs.len() * 20;
    encode_node_header(out, RES_XML_START_ELEMENT_TYPE, size);
    out.extend_from_slice(&NO_ENTRY.to_le_bytes()); // namespace
    out.extend_from_slice(&interner.index_of(&element.name).to_le_bytes());
    out.extend_from_slice(&20u16.to_le_bytes()); // attribute start
    out.extend_from_slice(&20u16.to_le_bytes()); // attribute size
    out.extend_from_slice(&(attrs.len() as u16).to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes()); // id index
    out.extend_from_slice(&0u16.to_le_bytes()); // class index
    out.extend_from_slice(&0u16.to_le_bytes()); // style index

    for attr in &attrs {
        let ns = match attr.id {
            Some(_) => interner.index_of(ANDROID_NS),
            None => NO_ENTRY,
        };
        out.extend_from_slice(&ns.to_le_bytes());
        out.extend_from_slice(&interner.index_of(&attr.name).to_le_bytes());

        let (raw_value, data_type, data) = match &attr.value {
            Value::Str(text) => {
                let index = interner.index_of(text);
                (index, TYPE_STRING, index)
            }
            Value::Bool(flag) => (
                NO_ENTRY,
                TYPE_INT_BOOLEAN,
                if *flag { 0xFFFF_FFFF } else { 0 },
            ),
            Value::Int(number) => (NO_ENTRY, TYPE_INT_DEC, *number as u32),
            Value::Hex(number) => (NO_ENTRY, TYPE_INT_HEX, *number),
            Value::Ref(id) => (NO_ENTRY, TYPE_REFERENCE, *id),
        };
        out.extend_from_slice(&raw_value.to_le_bytes());
        out.extend_from_slice(&8u16.to_le_bytes()); // Res_value size
        out.push(0); // res0
        out.push(data_type);
        out.extend_from_slice(&data.to_le_bytes());
    }

    for child in &element.children {
        encode_element(out, child, interner)?;
    }

    encode_node_header(out, RES_XML_END_ELEMENT_TYPE, 24);
    out.extend_from_slice(&NO_ENTRY.to_le_bytes());
    out.extend_from_slice(&interner.index_of(&element.name).to_le_bytes());
    Ok(())
}
