//! Encodes an XML tree as aapt2's protobuf form (`aapt.pb.XmlNode`).
//!
//! An APK carries `AndroidManifest.xml` as binary AXML; an AAB carries the same
//! tree as protobuf. Both are generated from the one [`crate::axml::Element`]
//! tree, so a project's manifest cannot drift between the two outputs.
//!
//! Field numbers come from `frameworks/base/tools/aapt2/Resources.proto`:
//!
//! ```text
//! XmlNode      { XmlElement element = 1; string text = 2; }
//! XmlElement   { XmlNamespace namespace_declaration = 1; string namespace_uri = 2;
//!                string name = 3; XmlAttribute attribute = 4; XmlNode child = 5; }
//! XmlNamespace { string prefix = 1; string uri = 2; }
//! XmlAttribute { string namespace_uri = 1; string name = 2; string value = 3;
//!                uint32 resource_id = 5; Item compiled_item = 6; }
//! Item         { Reference ref = 1; String str = 2; Primitive prim = 7; }
//! Reference    { Type type = 1; uint32 id = 2; }
//! String       { string value = 1; }
//! Primitive    { int32 int_decimal_value = 6; uint32 int_hexadecimal_value = 7;
//!                bool boolean_value = 8; }
//! ```

use crate::axml::{Element, Value, ANDROID_NS};
use crate::protobuf::Message;

const ANDROID_PREFIX: &str = "android";

// XmlElement
const EL_NAMESPACE_DECLARATION: u32 = 1;
const EL_NAME: u32 = 3;
const EL_ATTRIBUTE: u32 = 4;
const EL_CHILD: u32 = 5;

// XmlNamespace
const NS_PREFIX: u32 = 1;
const NS_URI: u32 = 2;

// XmlAttribute
const ATTR_NAMESPACE_URI: u32 = 1;
const ATTR_NAME: u32 = 2;
const ATTR_VALUE: u32 = 3;
const ATTR_RESOURCE_ID: u32 = 5;
const ATTR_COMPILED_ITEM: u32 = 6;

// Item
const ITEM_REF: u32 = 1;
const ITEM_STR: u32 = 2;
const ITEM_PRIM: u32 = 7;

// Reference
const REF_ID: u32 = 2;

// String
const STRING_VALUE: u32 = 1;

// Primitive
const PRIM_INT_DECIMAL: u32 = 6;
const PRIM_INT_HEX: u32 = 7;
const PRIM_BOOLEAN: u32 = 8;

// XmlNode
const NODE_ELEMENT: u32 = 1;

/// Encodes the tree as a serialised `XmlNode`, ready to be written to
/// `base/manifest/AndroidManifest.xml` inside a bundle.
pub fn encode(root: &Element) -> Vec<u8> {
    let mut node = Message::new();
    node.message(NODE_ELEMENT, &element(root, true));
    node.into_bytes()
}

fn element(source: &Element, is_root: bool) -> Message {
    let mut out = Message::new();

    // The android namespace is declared once, on the root element.
    if is_root {
        let mut ns = Message::new();
        ns.string(NS_PREFIX, ANDROID_PREFIX);
        ns.string(NS_URI, ANDROID_NS);
        out.message(EL_NAMESPACE_DECLARATION, &ns);
    }

    out.string(EL_NAME, &source.name);

    // Sorted by resource id, matching what aapt2 emits.
    let mut attrs: Vec<&crate::axml::Attr> = source.attrs.iter().collect();
    attrs.sort_by_key(|a| a.id.unwrap_or(0));
    for attr in attrs {
        out.message(EL_ATTRIBUTE, &attribute(attr));
    }

    for child in &source.children {
        let mut node = Message::new();
        node.message(NODE_ELEMENT, &element(child, false));
        out.message(EL_CHILD, &node);
    }
    out
}

fn attribute(source: &crate::axml::Attr) -> Message {
    let mut out = Message::new();
    if source.id.is_some() {
        out.string(ATTR_NAMESPACE_URI, ANDROID_NS);
    }
    out.string(ATTR_NAME, &source.name);

    match &source.value {
        // Plain strings keep the raw text; readers use it directly.
        Value::Str(text) => {
            out.string(ATTR_VALUE, text);
            if let Some(id) = source.id {
                out.u32(ATTR_RESOURCE_ID, id);
                let mut string_value = Message::new();
                string_value.string(STRING_VALUE, text);
                let mut item = Message::new();
                item.message(ITEM_STR, &string_value);
                out.message(ATTR_COMPILED_ITEM, &item);
            }
        }
        // Typed values live in compiled_item; `value` is left out so nothing has
        // to re-parse the text to learn the type.
        Value::Bool(flag) => {
            write_id(&mut out, source.id);
            let mut prim = Message::new();
            prim.bool(PRIM_BOOLEAN, *flag);
            out.message(ATTR_COMPILED_ITEM, &wrap_prim(prim));
        }
        Value::Int(number) => {
            write_id(&mut out, source.id);
            let mut prim = Message::new();
            prim.i32(PRIM_INT_DECIMAL, *number);
            out.message(ATTR_COMPILED_ITEM, &wrap_prim(prim));
        }
        Value::Hex(number) => {
            write_id(&mut out, source.id);
            let mut prim = Message::new();
            prim.u32(PRIM_INT_HEX, *number);
            out.message(ATTR_COMPILED_ITEM, &wrap_prim(prim));
        }
        Value::Ref(id) => {
            write_id(&mut out, source.id);
            let mut reference = Message::new();
            // Reference.type defaults to REFERENCE (0), so it is omitted.
            reference.u32(REF_ID, *id);
            let mut item = Message::new();
            item.message(ITEM_REF, &reference);
            out.message(ATTR_COMPILED_ITEM, &item);
        }
    }
    out
}

fn write_id(out: &mut Message, id: Option<u32>) {
    if let Some(id) = id {
        out.u32(ATTR_RESOURCE_ID, id);
    }
}

fn wrap_prim(prim: Message) -> Message {
    let mut item = Message::new();
    item.message(ITEM_PRIM, &prim);
    item
}
