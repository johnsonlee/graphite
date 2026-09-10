//! Big-endian, MSB-first bit reader compatible with `it.unimi.dsi.io.InputBitStream`.

#[derive(Clone)]
pub struct BitReader<'a> {
    data: &'a [u8],
    /// Absolute bit position.
    pos: u64,
}

impl<'a> BitReader<'a> {
    #[inline]
    pub fn new(data: &'a [u8], bit_pos: u64) -> Self {
        Self { data, pos: bit_pos }
    }

    #[inline]
    pub fn position(&self) -> u64 {
        self.pos
    }

    /// Peek up to 64 bits starting at the current position without advancing.
    /// Bits beyond the end of the data are read as zeros.
    #[inline]
    fn peek64(&self) -> u64 {
        let byte = (self.pos >> 3) as usize;
        let shift = (self.pos & 7) as u32;
        let mut buf = [0u8; 16];
        let end = (byte + 9).min(self.data.len());
        if byte < self.data.len() {
            buf[..end - byte].copy_from_slice(&self.data[byte..end]);
        }
        let hi = u64::from_be_bytes(buf[0..8].try_into().unwrap());
        if shift == 0 {
            hi
        } else {
            let lo = buf[8] as u64;
            (hi << shift) | (lo >> (8 - shift))
        }
    }

    #[inline]
    pub fn read_bit(&mut self) -> u64 {
        let byte = (self.pos >> 3) as usize;
        let bit = if byte < self.data.len() {
            ((self.data[byte] >> (7 - (self.pos & 7))) & 1) as u64
        } else {
            0
        };
        self.pos += 1;
        bit
    }

    /// Read `n` (<= 64) bits as an unsigned integer.
    #[inline]
    pub fn read_int(&mut self, n: u32) -> u64 {
        if n == 0 {
            return 0;
        }
        let v = self.peek64() >> (64 - n);
        self.pos += n as u64;
        v
    }

    /// Unary code: number of zeros before the terminating one.
    #[inline]
    pub fn read_unary(&mut self) -> u64 {
        let mut count = 0u64;
        loop {
            let w = self.peek64();
            if w != 0 {
                let lz = w.leading_zeros() as u64;
                self.pos += lz + 1;
                return count + lz;
            }
            // 64 zero bits (or end of data)
            if (self.pos >> 3) as usize >= self.data.len() {
                // Out of data: treat as terminator to avoid infinite loop.
                self.pos += 1;
                return count;
            }
            count += 64;
            self.pos += 64;
        }
    }

    #[inline]
    pub fn read_gamma(&mut self) -> u64 {
        let msb = self.read_unary() as u32;
        ((1u64 << msb) | self.read_int(msb)) - 1
    }

    #[inline]
    pub fn read_delta(&mut self) -> u64 {
        let msb = self.read_gamma() as u32;
        ((1u64 << msb) | self.read_int(msb)) - 1
    }

    #[inline]
    pub fn read_zeta(&mut self, k: u32) -> u64 {
        let h = self.read_unary() as u32;
        let left = 1u64 << (h * k);
        let m = self.read_int(h * k + k - 1);
        if m < left {
            m + left - 1
        } else {
            (m << 1) + self.read_bit() - 1
        }
    }

    #[inline]
    pub fn read_zeta3(&mut self) -> u64 {
        self.read_zeta(3)
    }
}

/// Standard bijection from naturals to integers used by WebGraph.
#[inline]
pub fn nat2int(n: u64) -> i64 {
    if n & 1 == 0 {
        (n >> 1) as i64
    } else {
        -((n >> 1) as i64) - 1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Minimal MSB-first bit writer to produce test vectors.
    struct W {
        bytes: Vec<u8>,
        nbits: usize,
    }
    impl W {
        fn new() -> Self {
            Self { bytes: vec![], nbits: 0 }
        }
        fn bit(&mut self, b: u64) {
            if self.nbits % 8 == 0 {
                self.bytes.push(0);
            }
            if b != 0 {
                let i = self.nbits / 8;
                self.bytes[i] |= 1 << (7 - (self.nbits % 8));
            }
            self.nbits += 1;
        }
        fn int(&mut self, v: u64, n: u32) {
            for i in (0..n).rev() {
                self.bit((v >> i) & 1);
            }
        }
        fn unary(&mut self, x: u64) {
            for _ in 0..x {
                self.bit(0);
            }
            self.bit(1);
        }
        fn gamma(&mut self, x: u64) {
            let x1 = x + 1;
            let msb = 63 - x1.leading_zeros();
            self.unary(msb as u64);
            self.int(x1 & ((1 << msb) - 1), msb);
        }
        fn zeta(&mut self, x: u64, k: u32) {
            let x1 = x + 1;
            let msb = 63 - x1.leading_zeros();
            let h = msb / k;
            self.unary(h as u64);
            let left = 1u64 << (h * k);
            if x1 - left < left {
                self.int(x1 - left, h * k + k - 1);
            } else {
                self.int(x1, h * k + k);
            }
        }
    }

    #[test]
    fn roundtrip_codes() {
        let vals = [0u64, 1, 2, 3, 7, 8, 100, 1000, 65535, 1 << 20, (1 << 33) + 5];
        let mut w = W::new();
        for &v in &vals {
            w.gamma(v);
            w.zeta(v, 3);
            w.unary(v % 70);
            w.int(v, 40);
        }
        let mut r = BitReader::new(&w.bytes, 0);
        for &v in &vals {
            assert_eq!(r.read_gamma(), v);
            assert_eq!(r.read_zeta3(), v);
            assert_eq!(r.read_unary(), v % 70);
            assert_eq!(r.read_int(40), v);
        }
    }

    #[test]
    fn nat2int_bijection() {
        assert_eq!(nat2int(0), 0);
        assert_eq!(nat2int(1), -1);
        assert_eq!(nat2int(2), 1);
        assert_eq!(nat2int(3), -2);
    }
}
