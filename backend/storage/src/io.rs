//! Big-endian cursor over a byte slice, mirroring `java.io.DataInput`.

#[derive(Debug, thiserror::Error)]
#[error("truncated data at offset {0}")]
pub struct Truncated(pub usize);

#[derive(Clone)]
pub struct Cursor<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    #[inline]
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }
    #[inline]
    pub fn at(data: &'a [u8], pos: usize) -> Self {
        Self { data, pos }
    }
    #[inline]
    pub fn position(&self) -> usize {
        self.pos
    }
    #[inline]
    pub fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }
    #[inline]
    pub fn u8(&mut self) -> Result<u8, Truncated> {
        let b = *self.data.get(self.pos).ok_or(Truncated(self.pos))?;
        self.pos += 1;
        Ok(b)
    }
    #[inline]
    pub fn bool(&mut self) -> Result<bool, Truncated> {
        Ok(self.u8()? != 0)
    }
    #[inline]
    pub fn i32(&mut self) -> Result<i32, Truncated> {
        let s = self
            .data
            .get(self.pos..self.pos + 4)
            .ok_or(Truncated(self.pos))?;
        self.pos += 4;
        Ok(i32::from_be_bytes([s[0], s[1], s[2], s[3]]))
    }
    #[inline]
    pub fn u32(&mut self) -> Result<u32, Truncated> {
        Ok(self.i32()? as u32)
    }
    #[inline]
    pub fn i64(&mut self) -> Result<i64, Truncated> {
        let s = self
            .data
            .get(self.pos..self.pos + 8)
            .ok_or(Truncated(self.pos))?;
        self.pos += 8;
        Ok(i64::from_be_bytes(s.try_into().unwrap()))
    }
    #[inline]
    pub fn f32(&mut self) -> Result<f32, Truncated> {
        Ok(f32::from_bits(self.u32()?))
    }
    #[inline]
    pub fn f64(&mut self) -> Result<f64, Truncated> {
        Ok(f64::from_bits(self.i64()? as u64))
    }
    #[inline]
    pub fn bytes(&mut self, n: usize) -> Result<&'a [u8], Truncated> {
        let end = self.pos.checked_add(n).ok_or(Truncated(self.pos))?;
        let s = self.data.get(self.pos..end).ok_or(Truncated(self.pos))?;
        self.pos = end;
        Ok(s)
    }
    #[inline]
    pub fn skip(&mut self, n: usize) -> Result<(), Truncated> {
        self.bytes(n).map(|_| ())
    }
}

#[inline]
pub fn read_i32_at(data: &[u8], pos: usize) -> i32 {
    i32::from_be_bytes(data[pos..pos + 4].try_into().unwrap())
}

#[inline]
pub fn read_i64_at(data: &[u8], pos: usize) -> i64 {
    i64::from_be_bytes(data[pos..pos + 8].try_into().unwrap())
}

/// Validate a Graphite header word: 3-byte magic + 1-byte version.
pub fn check_header(word: i32, magic: i32) -> Option<u8> {
    if (word as u32) & 0xFFFF_FF00 == (magic as u32) & 0xFFFF_FF00 {
        Some((word & 0xFF) as u8)
    } else {
        None
    }
}

pub const MAGIC_METADATA: i32 = 0x4752_4D00;
pub const MAGIC_NODEDATA: i32 = 0x4752_4E00;
pub const MAGIC_NODEINDEX: i32 = 0x4752_4900;
pub const MAGIC_NODEOFFSETS: i32 = 0x4752_4C00;
pub const MAGIC_TYPEINDEX: i32 = 0x4752_5400;
pub const MAGIC_COMPARISONS: i32 = 0x4752_4300;
pub const MAGIC_CLASS_OVERVIEW: i32 = 0x4752_4F00;
pub const MAGIC_RESOURCES: i32 = 0x4752_5200;
