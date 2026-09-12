//! Minimal parser for the Java Object Serialization Stream Protocol, sufficient
//! to read `it.unimi.dsi.util.FrontCodedStringList` written via `BinIO.storeObject`.

use std::collections::HashMap;

#[derive(Debug, thiserror::Error)]
pub enum JavaSerError {
    #[error("truncated java serialization stream")]
    Truncated,
    #[error("bad magic/version in java serialization stream")]
    BadMagic,
    #[error("unsupported type code 0x{0:02x} at offset {1}")]
    Unsupported(u8, usize),
    #[error("unresolved handle {0}")]
    BadHandle(u32),
    #[error("{0}")]
    Other(String),
}

type R<T> = Result<T, JavaSerError>;

#[derive(Debug, Clone)]
pub struct FieldDesc {
    pub type_code: u8,
    pub name: String,
    pub class_name: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ClassDesc {
    pub name: String,
    pub flags: u8,
    pub fields: Vec<FieldDesc>,
    pub super_desc: Option<Box<ClassDesc>>,
}

#[derive(Debug, Clone)]
pub enum Value {
    Null,
    Str(String),
    Object {
        class: String,
        fields: HashMap<String, Value>,
    },
    CharArray(Vec<u16>),
    ByteArray(Vec<u8>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
    ObjArray(Vec<Value>),
    Bool(bool),
    Byte(i8),
    Char(u16),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
}

impl Value {
    pub fn field(&self, name: &str) -> Option<&Value> {
        match self {
            Value::Object { fields, .. } => fields.get(name),
            _ => None,
        }
    }
}

#[derive(Clone)]
enum Handle {
    Class(ClassDesc),
    Value(Value),
}

struct Parser<'a> {
    data: &'a [u8],
    pos: usize,
    handles: Vec<Handle>,
}

const TC_NULL: u8 = 0x70;
const TC_REFERENCE: u8 = 0x71;
const TC_CLASSDESC: u8 = 0x72;
const TC_OBJECT: u8 = 0x73;
const TC_STRING: u8 = 0x74;
const TC_ARRAY: u8 = 0x75;
const TC_CLASS: u8 = 0x76;
const TC_BLOCKDATA: u8 = 0x77;
const TC_ENDBLOCKDATA: u8 = 0x78;
const TC_RESET: u8 = 0x79;
const TC_BLOCKDATALONG: u8 = 0x7A;
const TC_LONGSTRING: u8 = 0x7C;
const TC_PROXYCLASSDESC: u8 = 0x7D;
const TC_ENUM: u8 = 0x7E;
const BASE_HANDLE: u32 = 0x7E0000;
const SC_WRITE_METHOD: u8 = 0x01;
const SC_BLOCK_DATA: u8 = 0x08;
const SC_EXTERNALIZABLE: u8 = 0x04;

impl<'a> Parser<'a> {
    fn u8(&mut self) -> R<u8> {
        let b = *self.data.get(self.pos).ok_or(JavaSerError::Truncated)?;
        self.pos += 1;
        Ok(b)
    }
    fn u16(&mut self) -> R<u16> {
        Ok(((self.u8()? as u16) << 8) | self.u8()? as u16)
    }
    fn i32(&mut self) -> R<i32> {
        Ok(((self.u16()? as i32) << 16) | self.u16()? as i32)
    }
    fn i64(&mut self) -> R<i64> {
        Ok(((self.i32()? as i64) << 32) | (self.i32()? as u32 as i64))
    }
    fn bytes(&mut self, n: usize) -> R<&'a [u8]> {
        let end = self.pos.checked_add(n).ok_or(JavaSerError::Truncated)?;
        let s = self
            .data
            .get(self.pos..end)
            .ok_or(JavaSerError::Truncated)?;
        self.pos = end;
        Ok(s)
    }
    fn utf(&mut self) -> R<String> {
        let n = self.u16()? as usize;
        let b = self.bytes(n)?;
        Ok(decode_modified_utf8(b))
    }
    fn long_utf(&mut self) -> R<String> {
        let n = self.i64()? as usize;
        let b = self.bytes(n)?;
        Ok(decode_modified_utf8(b))
    }
    fn new_handle(&mut self, h: Handle) -> u32 {
        self.handles.push(h);
        BASE_HANDLE + (self.handles.len() as u32 - 1)
    }
    fn handle(&self, h: u32) -> R<&Handle> {
        self.handles
            .get(h.wrapping_sub(BASE_HANDLE) as usize)
            .ok_or(JavaSerError::BadHandle(h))
    }

    fn class_desc(&mut self) -> R<Option<ClassDesc>> {
        let tc = self.u8()?;
        match tc {
            TC_NULL => Ok(None),
            TC_REFERENCE => {
                let h = self.i32()? as u32;
                match self.handle(h)? {
                    Handle::Class(c) => Ok(Some(c.clone())),
                    _ => Err(JavaSerError::BadHandle(h)),
                }
            }
            TC_CLASSDESC => {
                let name = self.utf()?;
                let _uid = self.i64()?;
                // Reserve handle before reading fields (the JDK assigns it here).
                let idx = self.new_handle(Handle::Class(ClassDesc {
                    name: name.clone(),
                    flags: 0,
                    fields: vec![],
                    super_desc: None,
                }));
                let flags = self.u8()?;
                let nfields = self.u16()? as usize;
                let mut fields = Vec::with_capacity(nfields);
                for _ in 0..nfields {
                    let type_code = self.u8()?;
                    let fname = self.utf()?;
                    let class_name = if type_code == b'L' || type_code == b'[' {
                        match self.value()? {
                            Value::Str(s) => Some(s),
                            _ => return Err(JavaSerError::Other("bad field class name".into())),
                        }
                    } else {
                        None
                    };
                    fields.push(FieldDesc {
                        type_code,
                        name: fname,
                        class_name,
                    });
                }
                self.skip_annotation()?;
                let super_desc = self.class_desc()?.map(Box::new);
                let desc = ClassDesc {
                    name,
                    flags,
                    fields,
                    super_desc,
                };
                self.handles[(idx - BASE_HANDLE) as usize] = Handle::Class(desc.clone());
                Ok(Some(desc))
            }
            TC_PROXYCLASSDESC => Err(JavaSerError::Unsupported(tc, self.pos - 1)),
            _ => Err(JavaSerError::Unsupported(tc, self.pos - 1)),
        }
    }

    fn skip_annotation(&mut self) -> R<()> {
        loop {
            let tc = self.u8()?;
            match tc {
                TC_ENDBLOCKDATA => return Ok(()),
                TC_BLOCKDATA => {
                    let n = self.u8()? as usize;
                    self.bytes(n)?;
                }
                TC_BLOCKDATALONG => {
                    let n = self.i32()? as usize;
                    self.bytes(n)?;
                }
                _ => {
                    self.pos -= 1;
                    self.value()?;
                }
            }
        }
    }

    fn primitive(&mut self, type_code: u8) -> R<Value> {
        Ok(match type_code {
            b'B' => Value::Byte(self.u8()? as i8),
            b'C' => Value::Char(self.u16()?),
            b'D' => Value::Double(f64::from_bits(self.i64()? as u64)),
            b'F' => Value::Float(f32::from_bits(self.i32()? as u32)),
            b'I' => Value::Int(self.i32()?),
            b'J' => Value::Long(self.i64()?),
            b'S' => Value::Short(self.u16()? as i16),
            b'Z' => Value::Bool(self.u8()? != 0),
            _ => return Err(JavaSerError::Other(format!("bad primitive {type_code}"))),
        })
    }

    fn value(&mut self) -> R<Value> {
        let tc = self.u8()?;
        match tc {
            TC_NULL => Ok(Value::Null),
            TC_REFERENCE => {
                let h = self.i32()? as u32;
                match self.handle(h)? {
                    Handle::Value(v) => Ok(v.clone()),
                    Handle::Class(c) => Ok(Value::Str(c.name.clone())),
                }
            }
            TC_STRING => {
                let s = self.utf()?;
                self.new_handle(Handle::Value(Value::Str(s.clone())));
                Ok(Value::Str(s))
            }
            TC_LONGSTRING => {
                let s = self.long_utf()?;
                self.new_handle(Handle::Value(Value::Str(s.clone())));
                Ok(Value::Str(s))
            }
            TC_CLASS => {
                let d = self.class_desc()?;
                let name = d.map(|d| d.name).unwrap_or_default();
                self.new_handle(Handle::Value(Value::Str(name.clone())));
                Ok(Value::Str(name))
            }
            TC_ENUM => {
                let _d = self.class_desc()?;
                let idx = self.new_handle(Handle::Value(Value::Null));
                let name = match self.value()? {
                    Value::Str(s) => s,
                    _ => return Err(JavaSerError::Other("bad enum".into())),
                };
                self.handles[(idx - BASE_HANDLE) as usize] =
                    Handle::Value(Value::Str(name.clone()));
                Ok(Value::Str(name))
            }
            TC_ARRAY => {
                let desc = self
                    .class_desc()?
                    .ok_or_else(|| JavaSerError::Other("array without class".into()))?;
                let idx = self.new_handle(Handle::Value(Value::Null));
                let n = self.i32()? as usize;
                let elem = desc.name.as_bytes().get(1).copied().unwrap_or(b'L');
                let v = match elem {
                    b'C' => {
                        let b = self.bytes(n * 2)?;
                        Value::CharArray(
                            b.chunks_exact(2)
                                .map(|c| u16::from_be_bytes([c[0], c[1]]))
                                .collect(),
                        )
                    }
                    b'B' => Value::ByteArray(self.bytes(n)?.to_vec()),
                    b'I' => {
                        let b = self.bytes(n * 4)?;
                        Value::IntArray(
                            b.chunks_exact(4)
                                .map(|c| i32::from_be_bytes([c[0], c[1], c[2], c[3]]))
                                .collect(),
                        )
                    }
                    b'J' => {
                        let b = self.bytes(n * 8)?;
                        Value::LongArray(
                            b.chunks_exact(8)
                                .map(|c| i64::from_be_bytes(c.try_into().unwrap()))
                                .collect(),
                        )
                    }
                    b'Z' | b'S' | b'F' | b'D' => {
                        let mut items = Vec::with_capacity(n);
                        for _ in 0..n {
                            items.push(self.primitive(elem)?);
                        }
                        Value::ObjArray(items)
                    }
                    _ => {
                        let mut items = Vec::with_capacity(n);
                        for _ in 0..n {
                            items.push(self.value()?);
                        }
                        Value::ObjArray(items)
                    }
                };
                self.handles[(idx - BASE_HANDLE) as usize] = Handle::Value(v.clone());
                Ok(v)
            }
            TC_OBJECT => {
                let desc = self
                    .class_desc()?
                    .ok_or_else(|| JavaSerError::Other("object without class".into()))?;
                let idx = self.new_handle(Handle::Value(Value::Null));
                let mut fields = HashMap::new();
                // Collect class chain from the topmost superclass down.
                let mut chain = vec![];
                let mut cur = Some(&desc);
                while let Some(c) = cur {
                    chain.push(c);
                    cur = c.super_desc.as_deref();
                }
                for c in chain.iter().rev() {
                    if c.flags & SC_EXTERNALIZABLE != 0 {
                        return Err(JavaSerError::Unsupported(TC_OBJECT, self.pos));
                    }
                    for f in &c.fields {
                        let v = if f.type_code == b'L' || f.type_code == b'[' {
                            self.value()?
                        } else {
                            self.primitive(f.type_code)?
                        };
                        fields.insert(f.name.clone(), v);
                    }
                    if c.flags & SC_WRITE_METHOD != 0 || c.flags & SC_BLOCK_DATA != 0 {
                        self.skip_annotation()?;
                    }
                }
                let v = Value::Object {
                    class: desc.name.clone(),
                    fields,
                };
                self.handles[(idx - BASE_HANDLE) as usize] = Handle::Value(v.clone());
                Ok(v)
            }
            TC_RESET => {
                self.handles.clear();
                self.value()
            }
            _ => Err(JavaSerError::Unsupported(tc, self.pos - 1)),
        }
    }
}

fn decode_modified_utf8(b: &[u8]) -> String {
    // Modified UTF-8: like UTF-8 but NUL is encoded as C0 80 and supplementary
    // characters as surrogate pairs. Decode into UTF-16 units then to String.
    let mut units: Vec<u16> = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if c & 0x80 == 0 {
            units.push(c as u16);
            i += 1;
        } else if c & 0xE0 == 0xC0 && i + 1 < b.len() {
            units.push((((c & 0x1F) as u16) << 6) | (b[i + 1] & 0x3F) as u16);
            i += 2;
        } else if c & 0xF0 == 0xE0 && i + 2 < b.len() {
            units.push(
                (((c & 0x0F) as u16) << 12)
                    | (((b[i + 1] & 0x3F) as u16) << 6)
                    | (b[i + 2] & 0x3F) as u16,
            );
            i += 3;
        } else {
            units.push(0xFFFD);
            i += 1;
        }
    }
    String::from_utf16_lossy(&units)
}

/// Parse the first object of a Java serialization stream.
pub fn read_object(data: &[u8]) -> R<Value> {
    let mut p = Parser {
        data,
        pos: 0,
        handles: Vec::new(),
    };
    if p.u16()? != 0xACED || p.u16()? != 0x0005 {
        return Err(JavaSerError::BadMagic);
    }
    p.value()
}
