//! Reader for the Java WebGraph `BVGraph` format (`basename.graph`,
//! `basename.properties`, `basename.offsets`) with random access.

use crate::bits::{nat2int, BitReader};
use memmap2::Mmap;
use std::collections::HashMap;
use std::fs::File;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum BvError {
    #[error("io error on {0}: {1}")]
    Io(String, std::io::Error),
    #[error("invalid property file: {0}")]
    Properties(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Code {
    Unary,
    Gamma,
    Delta,
    Zeta(u32),
    Nibble,
}

impl Code {
    fn parse(flag: &str, zeta_k: u32) -> Option<Code> {
        Some(match flag {
            "UNARY" => Code::Unary,
            "GAMMA" => Code::Gamma,
            "DELTA" => Code::Delta,
            "ZETA" => Code::Zeta(zeta_k),
            "NIBBLE" => Code::Nibble,
            _ => return None,
        })
    }
}

#[inline]
fn read_code(r: &mut BitReader, code: Code) -> u64 {
    match code {
        Code::Unary => r.read_unary(),
        Code::Gamma => r.read_gamma(),
        Code::Delta => r.read_delta(),
        Code::Zeta(k) => r.read_zeta(k),
        Code::Nibble => {
            // Nibble code: groups of 4 bits, MSB continuation flag.
            let mut v = 0u64;
            loop {
                let cont = r.read_bit();
                v = (v << 3) | r.read_int(3);
                if cont == 0 {
                    break;
                }
            }
            v
        }
    }
}

#[derive(Debug, Clone)]
pub struct BvProperties {
    pub nodes: usize,
    pub arcs: u64,
    pub window_size: usize,
    pub max_ref_count: usize,
    pub min_interval_length: usize,
    pub zeta_k: u32,
    pub outdegree_code: Code,
    pub reference_code: Code,
    pub block_code: Code,
    pub block_count_code: Code,
    pub interval_code: Code,
    pub residual_code: Code,
    pub offset_code: Code,
}

impl BvProperties {
    pub fn parse(text: &str) -> Result<Self, BvError> {
        let mut map: HashMap<String, String> = HashMap::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
                continue;
            }
            let (k, v) = match line.split_once('=').or_else(|| line.split_once(':')) {
                Some(kv) => kv,
                None => continue,
            };
            map.insert(k.trim().to_string(), v.trim().to_string());
        }
        let get_usize = |k: &str, default: usize| -> Result<usize, BvError> {
            match map.get(k) {
                Some(v) => v
                    .parse::<usize>()
                    .map_err(|_| BvError::Properties(format!("bad {k}: {v}"))),
                None => Ok(default),
            }
        };
        let nodes = get_usize("nodes", 0)?;
        let arcs = map
            .get("arcs")
            .map(|v| v.parse::<u64>().unwrap_or(0))
            .unwrap_or(0);
        let window_size = get_usize("windowsize", 7)?;
        let max_ref_count = get_usize("maxrefcount", 3)?;
        let min_interval_length = get_usize("minintervallength", 4)?;
        let zeta_k = get_usize("zetak", 3)? as u32;
        let mut p = BvProperties {
            nodes,
            arcs,
            window_size,
            max_ref_count,
            min_interval_length,
            zeta_k,
            outdegree_code: Code::Gamma,
            reference_code: Code::Unary,
            block_code: Code::Gamma,
            block_count_code: Code::Gamma,
            interval_code: Code::Gamma,
            residual_code: Code::Zeta(zeta_k),
            offset_code: Code::Gamma,
        };
        if let Some(flags) = map.get("compressionflags") {
            for flag in flags.split('|').map(str::trim).filter(|s| !s.is_empty()) {
                let (what, code) = match flag.rsplit_once('_') {
                    Some(x) => x,
                    None => continue,
                };
                let code = Code::parse(code, zeta_k)
                    .ok_or_else(|| BvError::Properties(format!("unknown code {flag}")))?;
                match what {
                    "OUTDEGREES" => p.outdegree_code = code,
                    "REFERENCES" => p.reference_code = code,
                    "BLOCKS" => p.block_code = code,
                    "BLOCK_COUNT" => p.block_count_code = code,
                    "INTERVALS" => p.interval_code = code,
                    "RESIDUALS" => p.residual_code = code,
                    "OFFSETS" => p.offset_code = code,
                    _ => {}
                }
            }
        }
        Ok(p)
    }
}

/// A random-access BVGraph backed by a memory-mapped bitstream.
pub struct BvGraph {
    props: BvProperties,
    graph: Mmap,
    /// Bit offset of each node's successor list (nodes + 1 entries).
    offsets: Vec<u64>,
}

impl BvGraph {
    pub fn load(basename: &Path) -> Result<Self, BvError> {
        let props_path = basename.with_extension("properties");
        let text = std::fs::read_to_string(&props_path)
            .map_err(|e| BvError::Io(props_path.display().to_string(), e))?;
        let props = BvProperties::parse(&text)?;

        let graph_path = basename.with_extension("graph");
        let file = File::open(&graph_path)
            .map_err(|e| BvError::Io(graph_path.display().to_string(), e))?;
        // SAFETY: the file is opened read-only and is not expected to be modified
        // while mapped; a modification would at worst yield garbage successors.
        let graph = unsafe { Mmap::map(&file) }
            .map_err(|e| BvError::Io(graph_path.display().to_string(), e))?;

        let offsets_path = basename.with_extension("offsets");
        let offsets_file = File::open(&offsets_path)
            .map_err(|e| BvError::Io(offsets_path.display().to_string(), e))?;
        let offsets_map = unsafe { Mmap::map(&offsets_file) }
            .map_err(|e| BvError::Io(offsets_path.display().to_string(), e))?;
        let mut offsets = Vec::with_capacity(props.nodes + 1);
        let mut r = BitReader::new(&offsets_map, 0);
        let mut acc = 0u64;
        for _ in 0..=props.nodes {
            acc += read_code(&mut r, props.offset_code);
            offsets.push(acc);
        }
        Ok(BvGraph {
            props,
            graph,
            offsets,
        })
    }

    #[inline]
    pub fn num_nodes(&self) -> usize {
        self.props.nodes
    }

    #[inline]
    pub fn num_arcs(&self) -> u64 {
        self.props.arcs
    }

    pub fn properties(&self) -> &BvProperties {
        &self.props
    }

    #[inline]
    pub fn outdegree(&self, node: usize) -> usize {
        if node >= self.props.nodes {
            return 0;
        }
        let mut r = BitReader::new(&self.graph, self.offsets[node]);
        read_code(&mut r, self.props.outdegree_code) as usize
    }

    /// Successors of `node`, in increasing order.
    pub fn successors(&self, node: usize) -> Vec<u32> {
        let mut out = Vec::new();
        self.successors_into(node, &mut out);
        out
    }

    /// Append the successors of `node` (ascending) to `out`.
    pub fn successors_into(&self, node: usize, out: &mut Vec<u32>) {
        out.clear();
        if node >= self.props.nodes {
            return;
        }
        self.decode(node, out);
    }

    fn decode(&self, node: usize, out: &mut Vec<u32>) {
        let p = &self.props;
        let mut r = BitReader::new(&self.graph, self.offsets[node]);
        let degree = read_code(&mut r, p.outdegree_code) as usize;
        if degree == 0 {
            return;
        }
        out.reserve(degree);
        let mut left = degree;

        // Reference part.
        let mut copied: Vec<u32> = Vec::new();
        if p.window_size != 0 {
            let reference = read_code(&mut r, p.reference_code) as usize;
            if reference != 0 {
                let mut ref_succ = Vec::new();
                self.decode(node - reference, &mut ref_succ);
                let block_count = read_code(&mut r, p.block_count_code) as usize;
                let mut blocks = Vec::with_capacity(block_count);
                for i in 0..block_count {
                    let b = read_code(&mut r, p.block_code) as usize;
                    blocks.push(if i == 0 { b } else { b + 1 });
                }
                let mut pos = 0usize;
                let mut copy = true;
                for &b in &blocks {
                    let end = (pos + b).min(ref_succ.len());
                    if copy {
                        copied.extend_from_slice(&ref_succ[pos..end]);
                    }
                    pos = end;
                    copy = !copy;
                }
                if copy && pos < ref_succ.len() {
                    copied.extend_from_slice(&ref_succ[pos..]);
                }
                left -= copied.len();
            }
        }

        // Intervals.
        let mut intervals: Vec<(u32, u32)> = Vec::new();
        if left != 0 && p.min_interval_length != 0 {
            let count = read_code(&mut r, p.interval_code) as usize;
            if count != 0 {
                let first = nat2int(read_code(&mut r, p.interval_code));
                let mut start = (node as i64 + first) as u64;
                let mut len = read_code(&mut r, p.interval_code) as usize + p.min_interval_length;
                intervals.push((start as u32, len as u32));
                start += len as u64;
                left -= len;
                for _ in 1..count {
                    start += 1 + read_code(&mut r, p.interval_code);
                    len = read_code(&mut r, p.interval_code) as usize + p.min_interval_length;
                    intervals.push((start as u32, len as u32));
                    start += len as u64;
                    left -= len;
                }
            }
        }

        // Residuals.
        let mut residuals: Vec<u32> = Vec::with_capacity(left);
        if left != 0 {
            let first = nat2int(read_code(&mut r, p.residual_code));
            let mut prev = (node as i64 + first) as u64;
            residuals.push(prev as u32);
            for _ in 1..left {
                prev += read_code(&mut r, p.residual_code) + 1;
                residuals.push(prev as u32);
            }
        }

        // Merge the three sorted sources.
        let mut iv: Vec<u32> = Vec::new();
        for (s, l) in intervals {
            for x in s..s + l {
                iv.push(x);
            }
        }
        merge3(&copied, &iv, &residuals, out);
    }
}

fn merge3(a: &[u32], b: &[u32], c: &[u32], out: &mut Vec<u32>) {
    let (mut i, mut j, mut k) = (0, 0, 0);
    loop {
        let va = a.get(i).copied();
        let vb = b.get(j).copied();
        let vc = c.get(k).copied();
        let m = match (va, vb, vc) {
            (None, None, None) => break,
            _ => [va, vb, vc].into_iter().flatten().min().unwrap(),
        };
        if va == Some(m) {
            i += 1;
        } else if vb == Some(m) {
            j += 1;
        } else {
            k += 1;
        }
        out.push(m);
    }
}
