//! Single-file persisted graph: a STORED (uncompressed) zip archive.
//!
//! `graphite build -o app.graphite` packs the directory the frontend wrote into one
//! file, so a graph is one thing to copy, hash and replace. The container is a plain
//! zip so `unzip -l`, `jar tf` and `python3 -m zipfile` all read it; what this module
//! adds on top is what the reader relies on:
//!
//! - every entry is STORED, so an entry is a byte range of the file and the graph is
//!   served from one memory map, sliced per entry, exactly as it is from a directory;
//! - every entry's data starts on a 4 KiB boundary (an `0xD935` padding extra field,
//!   the one `zipalign -p` writes), so a slice is page-aligned like a mapped file;
//! - `META-INF/graphite.manifest` lists every other entry with its size and SHA-256,
//!   and the manifest's own SHA-256 is the graph's fingerprint; `verify` checks each
//!   entry's CRC-32 against the central directory and its SHA-256 against the manifest;
//! - the central directory and the end record are written last, so a file cut short
//!   by a crash or a copy in progress does not open at all: completeness is not a
//!   separate check;
//! - entries are sorted by name and timestamps are fixed, so packing the same
//!   directory twice gives the same bytes, and the fingerprint identifies content.
//!
//! Only the subset this module writes is read: one disk, no encryption, no
//! compression. Zip64 sizes and offsets are supported, since a node data entry of a
//! large graph exceeds 4 GiB.

use memmap2::Mmap;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const EXTENSION: &str = "graphite";
pub const MANIFEST_NAME: &str = "META-INF/graphite.manifest";
pub const MANIFEST_HEADER: &str = "graphite-graph 1";
pub const ARCHIVE_COMMENT: &str = "graphite-graph/1";
/// Entry data alignment: a page, so a sliced entry behaves like a mapped file.
pub const ALIGNMENT: u64 = 4096;

const SIG_LOCAL: u32 = 0x0403_4b50;
const SIG_CENTRAL: u32 = 0x0201_4b50;
const SIG_EOCD: u32 = 0x0605_4b50;
const SIG_EOCD64: u32 = 0x0606_4b50;
const SIG_EOCD64_LOCATOR: u32 = 0x0706_4b50;
const EXTRA_ZIP64: u16 = 0x0001;
/// The padding extra field id `zipalign` uses; readers skip unknown ids.
const EXTRA_PADDING: u16 = 0xd935;
const VERSION_STORED: u16 = 20;
const VERSION_ZIP64: u16 = 45;
/// Version made by: zip64 spec, Unix host (external attributes carry a mode).
const VERSION_MADE_BY: u16 = 0x0300 | VERSION_ZIP64;
const FLAG_UTF8: u16 = 1 << 11;
const FLAG_ENCRYPTED: u16 = 1;
/// 1980-01-01 00:00:00, the earliest DOS time: fixed so packing is reproducible.
const DOS_TIME: u16 = 0;
const DOS_DATE: u16 = (1 << 5) | 1;
const EOCD_LEN: usize = 22;
const MAX_COMMENT: usize = 0xffff;

#[derive(Debug, thiserror::Error)]
pub enum ContainerError {
    #[error("io error on {0}: {1}")]
    Io(String, std::io::Error),
    #[error("{0}: not a graphite container: {1}")]
    Format(String, String),
    #[error("{0}: entry {1}: {2}")]
    Entry(String, String, String),
    #[error("{0}: {1}")]
    Corrupt(String, String),
}

/// A read-only byte range: a slice of one shared memory map, or owned bytes.
#[derive(Clone)]
pub enum Bytes {
    Mapped {
        map: Arc<Mmap>,
        start: usize,
        end: usize,
    },
    Owned(Arc<Vec<u8>>),
}

impl Bytes {
    pub fn whole(map: Mmap) -> Bytes {
        let end = map.len();
        Bytes::Mapped {
            map: Arc::new(map),
            start: 0,
            end,
        }
    }
    pub fn owned(v: Vec<u8>) -> Bytes {
        Bytes::Owned(Arc::new(v))
    }
}

impl std::ops::Deref for Bytes {
    type Target = [u8];
    #[inline]
    fn deref(&self) -> &[u8] {
        match self {
            Bytes::Mapped { map, start, end } => &map[*start..*end],
            Bytes::Owned(v) => v,
        }
    }
}

impl std::fmt::Debug for Bytes {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Bytes::Mapped { start, end, .. } => write!(f, "Bytes::Mapped({start}..{end})"),
            Bytes::Owned(v) => write!(f, "Bytes::Owned({} bytes)", v.len()),
        }
    }
}

impl AsRef<[u8]> for Bytes {
    fn as_ref(&self) -> &[u8] {
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    /// Offset of the entry's data in the file.
    pub offset: u64,
    pub size: u64,
    pub crc32: u32,
}

/// An open container: one memory map and the central directory.
pub struct Container {
    path: PathBuf,
    map: Arc<Mmap>,
    entries: BTreeMap<String, Entry>,
}

fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}
fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

/// The zip64 extra field values an entry needs, in the order the spec lists them,
/// each present only when the fixed-width field is saturated.
fn zip64_extra(extra: &[u8], want: [bool; 3]) -> Result<[Option<u64>; 3], String> {
    let mut pos = 0;
    while pos + 4 <= extra.len() {
        let id = u16_at(extra, pos);
        let len = u16_at(extra, pos + 2) as usize;
        let data = extra
            .get(pos + 4..pos + 4 + len)
            .ok_or("truncated extra field")?;
        if id == EXTRA_ZIP64 {
            let mut out = [None; 3];
            let mut at = 0;
            for (slot, wanted) in out.iter_mut().zip(want) {
                if wanted {
                    if at + 8 > data.len() {
                        return Err("truncated zip64 extra field".into());
                    }
                    *slot = Some(u64_at(data, at));
                    at += 8;
                }
            }
            return Ok(out);
        }
        pos += 4 + len;
    }
    if want.iter().any(|w| *w) {
        return Err("saturated size without a zip64 extra field".into());
    }
    Ok([None; 3])
}

fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('/')
        && !name.contains('\\')
        && !name.contains('\0')
        && name
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

impl Container {
    pub fn open(path: &Path) -> Result<Container, ContainerError> {
        let shown = path.display().to_string();
        let file = File::open(path).map_err(|e| ContainerError::Io(shown.clone(), e))?;
        // SAFETY: read-only mapping of a file we do not modify.
        let map = unsafe { Mmap::map(&file) }.map_err(|e| ContainerError::Io(shown.clone(), e))?;
        let entries = Self::read_central_directory(&map)
            .map_err(|why| ContainerError::Format(shown.clone(), why))?;
        Ok(Container {
            path: path.to_path_buf(),
            map: Arc::new(map),
            entries,
        })
    }

    fn read_central_directory(b: &[u8]) -> Result<BTreeMap<String, Entry>, String> {
        if b.len() < EOCD_LEN {
            return Err("file shorter than an end-of-central-directory record".into());
        }
        // The end record is the last thing in the file, followed only by its comment.
        let floor = b.len().saturating_sub(EOCD_LEN + MAX_COMMENT);
        let eocd = (floor..=b.len() - EOCD_LEN)
            .rev()
            .find(|&at| u32_at(b, at) == SIG_EOCD)
            .ok_or("no end-of-central-directory record (truncated or not a zip)")?;
        if u16_at(b, eocd + 4) != 0 || u16_at(b, eocd + 6) != 0 {
            return Err("multi-disk archive".into());
        }
        let mut count = u16_at(b, eocd + 10) as u64;
        let mut cd_size = u32_at(b, eocd + 12) as u64;
        let mut cd_offset = u32_at(b, eocd + 16) as u64;
        if count == 0xffff || cd_size == 0xffff_ffff || cd_offset == 0xffff_ffff {
            let locator = eocd
                .checked_sub(20)
                .filter(|&at| u32_at(b, at) == SIG_EOCD64_LOCATOR)
                .ok_or("zip64 archive without an end-of-central-directory locator")?;
            let at = u64_at(b, locator + 8) as usize;
            if at + 56 > b.len() || u32_at(b, at) != SIG_EOCD64 {
                return Err("bad zip64 end-of-central-directory record".into());
            }
            count = u64_at(b, at + 32);
            cd_size = u64_at(b, at + 40);
            cd_offset = u64_at(b, at + 48);
        }
        let cd_end = cd_offset
            .checked_add(cd_size)
            .filter(|&end| end <= eocd as u64)
            .ok_or("central directory outside the file")?;
        let mut entries = BTreeMap::new();
        let mut pos = cd_offset as usize;
        for _ in 0..count {
            if pos + 46 > cd_end as usize || u32_at(b, pos) != SIG_CENTRAL {
                return Err("bad central directory header".into());
            }
            let flags = u16_at(b, pos + 8);
            let method = u16_at(b, pos + 10);
            let crc32 = u32_at(b, pos + 16);
            let comp = u32_at(b, pos + 20) as u64;
            let uncomp = u32_at(b, pos + 24) as u64;
            let name_len = u16_at(b, pos + 28) as usize;
            let extra_len = u16_at(b, pos + 30) as usize;
            let comment_len = u16_at(b, pos + 32) as usize;
            let local = u32_at(b, pos + 42) as u64;
            let name_at = pos + 46;
            let extra_at = name_at + name_len;
            let next = extra_at + extra_len + comment_len;
            if next > cd_end as usize {
                return Err("central directory header outside the directory".into());
            }
            let name = std::str::from_utf8(&b[name_at..extra_at])
                .map_err(|_| "entry name is not UTF-8")?
                .to_string();
            if !valid_name(&name) {
                return Err(format!("unsafe entry name {name:?}"));
            }
            if flags & FLAG_ENCRYPTED != 0 {
                return Err(format!("entry {name} is encrypted"));
            }
            if method != 0 {
                return Err(format!("entry {name} is compressed (method {method}); only STORED entries can be mapped"));
            }
            let want = [
                uncomp == 0xffff_ffff,
                comp == 0xffff_ffff,
                local == 0xffff_ffff,
            ];
            let z = zip64_extra(&b[extra_at..extra_at + extra_len], want)
                .map_err(|why| format!("entry {name}: {why}"))?;
            let uncomp = z[0].unwrap_or(uncomp);
            let comp = z[1].unwrap_or(comp);
            let local = z[2].unwrap_or(local) as usize;
            if comp != uncomp {
                return Err(format!("entry {name}: stored sizes differ"));
            }
            if local + 30 > b.len() || u32_at(b, local) != SIG_LOCAL {
                return Err(format!("entry {name}: bad local header"));
            }
            let data = local + 30 + u16_at(b, local + 26) as usize + u16_at(b, local + 28) as usize;
            if (data as u64)
                .checked_add(uncomp)
                .filter(|&end| end <= b.len() as u64)
                .is_none()
            {
                return Err(format!("entry {name}: data outside the file"));
            }
            if entries
                .insert(
                    name.clone(),
                    Entry {
                        name: name.clone(),
                        offset: data as u64,
                        size: uncomp,
                        crc32,
                    },
                )
                .is_some()
            {
                return Err(format!("duplicate entry {name}"));
            }
            pos = next;
        }
        Ok(entries)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn entries(&self) -> impl Iterator<Item = &Entry> {
        self.entries.values()
    }

    pub fn entry(&self, name: &str) -> Option<&Entry> {
        self.entries.get(name)
    }

    /// The entry's bytes as a slice of the shared map, or `None` when absent.
    pub fn bytes(&self, name: &str) -> Option<Bytes> {
        self.entries.get(name).map(|e| Bytes::Mapped {
            map: self.map.clone(),
            start: e.offset as usize,
            end: (e.offset + e.size) as usize,
        })
    }

    /// The parsed manifest, or `None` when the archive carries none.
    pub fn manifest(&self) -> Result<Option<Manifest>, ContainerError> {
        let Some(bytes) = self.bytes(MANIFEST_NAME) else {
            return Ok(None);
        };
        Manifest::parse(&bytes)
            .map(Some)
            .map_err(|why| ContainerError::Entry(self.shown(), MANIFEST_NAME.into(), why))
    }

    /// SHA-256 of the manifest, hex: the graph's content fingerprint.
    pub fn fingerprint(&self) -> Option<String> {
        self.bytes(MANIFEST_NAME)
            .map(|b| hex(&Sha256::digest(&b[..])))
    }

    fn shown(&self) -> String {
        self.path.display().to_string()
    }

    /// Check every entry against the central directory (CRC-32) and, when the
    /// archive carries a manifest, against it (presence, size, SHA-256).
    pub fn verify(&self) -> Result<Verification, ContainerError> {
        let manifest = self.manifest()?;
        let mut checks = Vec::with_capacity(self.entries.len());
        let mut failures = Vec::new();
        for entry in self.entries.values() {
            let data = self.bytes(&entry.name).unwrap();
            let crc_ok = crc32fast::hash(&data) == entry.crc32;
            if !crc_ok {
                failures.push(format!("{}: CRC-32 mismatch", entry.name));
            }
            let sha_ok = match &manifest {
                Some(m) if entry.name != MANIFEST_NAME => match m.entries.get(&entry.name) {
                    Some(listed) => {
                        let digest = hex(&Sha256::digest(&data[..]));
                        let ok = listed.size == entry.size && listed.sha256 == digest;
                        if !ok {
                            failures.push(format!("{}: differs from the manifest", entry.name));
                        }
                        Some(ok)
                    }
                    None => {
                        failures.push(format!("{}: not listed in the manifest", entry.name));
                        Some(false)
                    }
                },
                _ => None,
            };
            checks.push(EntryCheck {
                name: entry.name.clone(),
                size: entry.size,
                crc_ok,
                sha_ok,
            });
        }
        if let Some(m) = &manifest {
            for name in m.entries.keys() {
                if !self.entries.contains_key(name) {
                    failures.push(format!("{name}: listed in the manifest but missing"));
                }
            }
        }
        Ok(Verification {
            entries: checks,
            has_manifest: manifest.is_some(),
            fingerprint: self.fingerprint(),
            failures,
        })
    }

    /// Write every entry back out as files under `dir` (created if needed).
    pub fn unpack(&self, dir: &Path) -> Result<usize, ContainerError> {
        let shown = self.shown();
        std::fs::create_dir_all(dir)
            .map_err(|e| ContainerError::Io(dir.display().to_string(), e))?;
        for entry in self.entries.values() {
            let target = dir.join(&entry.name);
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| ContainerError::Io(parent.display().to_string(), e))?;
            }
            let data = self.bytes(&entry.name).unwrap();
            if crc32fast::hash(&data) != entry.crc32 {
                return Err(ContainerError::Entry(
                    shown,
                    entry.name.clone(),
                    "CRC-32 mismatch".into(),
                ));
            }
            std::fs::write(&target, &data[..])
                .map_err(|e| ContainerError::Io(target.display().to_string(), e))?;
        }
        Ok(self.entries.len())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestEntry {
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Manifest {
    pub entries: BTreeMap<String, ManifestEntry>,
}

impl Manifest {
    pub fn parse(text: &[u8]) -> Result<Manifest, String> {
        let text = std::str::from_utf8(text).map_err(|_| "manifest is not UTF-8")?;
        let mut lines = text.lines();
        if lines.next() != Some(MANIFEST_HEADER) {
            return Err(format!("manifest does not start with {MANIFEST_HEADER:?}"));
        }
        let mut entries = BTreeMap::new();
        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let mut parts = line.splitn(3, "  ");
            let (Some(sha256), Some(size), Some(name)) = (parts.next(), parts.next(), parts.next())
            else {
                return Err(format!("malformed manifest line {line:?}"));
            };
            if sha256.len() != 64 || !sha256.bytes().all(|c| c.is_ascii_hexdigit()) {
                return Err(format!("malformed digest in manifest line {line:?}"));
            }
            let size = size
                .parse()
                .map_err(|_| format!("malformed size in manifest line {line:?}"))?;
            entries.insert(
                name.to_string(),
                ManifestEntry {
                    size,
                    sha256: sha256.to_string(),
                },
            );
        }
        Ok(Manifest { entries })
    }

    pub fn render(&self) -> String {
        let mut out = String::from(MANIFEST_HEADER);
        out.push('\n');
        for (name, e) in &self.entries {
            out.push_str(&format!("{}  {}  {}\n", e.sha256, e.size, name));
        }
        out
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryCheck {
    pub name: String,
    pub size: u64,
    pub crc_ok: bool,
    /// `None` when the archive has no manifest, or for the manifest itself.
    pub sha_ok: Option<bool>,
}

#[derive(Debug, Clone)]
pub struct Verification {
    pub entries: Vec<EntryCheck>,
    pub has_manifest: bool,
    pub fingerprint: Option<String>,
    pub failures: Vec<String>,
}

impl Verification {
    pub fn ok(&self) -> bool {
        self.failures.is_empty()
    }
}

#[derive(Debug, Clone)]
pub struct PackReport {
    pub entries: usize,
    pub bytes: u64,
    pub fingerprint: String,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Every regular file under `dir`, as (archive name, path), sorted by name.
fn walk(dir: &Path) -> Result<Vec<(String, PathBuf)>, ContainerError> {
    fn visit(
        root: &Path,
        dir: &Path,
        out: &mut Vec<(String, PathBuf)>,
    ) -> Result<(), ContainerError> {
        let shown = dir.display().to_string();
        for entry in std::fs::read_dir(dir).map_err(|e| ContainerError::Io(shown.clone(), e))? {
            let entry = entry.map_err(|e| ContainerError::Io(shown.clone(), e))?;
            let path = entry.path();
            let kind = entry
                .file_type()
                .map_err(|e| ContainerError::Io(shown.clone(), e))?;
            if kind.is_dir() {
                visit(root, &path, out)?;
            } else if kind.is_file() {
                let rel = path.strip_prefix(root).unwrap();
                let name = rel
                    .components()
                    .map(|c| c.as_os_str().to_string_lossy().into_owned())
                    .collect::<Vec<_>>()
                    .join("/");
                if !valid_name(&name) {
                    return Err(ContainerError::Format(
                        shown.clone(),
                        format!("cannot archive {name:?}"),
                    ));
                }
                out.push((name, path));
            }
        }
        Ok(())
    }
    let mut out = Vec::new();
    visit(dir, dir, &mut out)?;
    out.sort();
    Ok(out)
}

struct Digested {
    crc32: u32,
    sha256: String,
    size: u64,
}

fn digest_file(path: &Path) -> Result<Digested, ContainerError> {
    let shown = path.display().to_string();
    let mut file = File::open(path).map_err(|e| ContainerError::Io(shown.clone(), e))?;
    let mut crc = crc32fast::Hasher::new();
    let mut sha = Sha256::new();
    let mut size = 0u64;
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| ContainerError::Io(shown.clone(), e))?;
        if n == 0 {
            break;
        }
        crc.update(&buf[..n]);
        sha.update(&buf[..n]);
        size += n as u64;
    }
    Ok(Digested {
        crc32: crc.finalize(),
        sha256: hex(&sha.finalize()),
        size,
    })
}

/// A file's data comes from disk or is already in memory (the manifest).
enum Source {
    File(PathBuf),
    Memory(Vec<u8>),
}

struct Planned {
    name: String,
    source: Source,
    crc32: u32,
    size: u64,
}

struct Written {
    name: String,
    crc32: u32,
    size: u64,
    local_offset: u64,
}

struct Writer<W: Write> {
    out: W,
    pos: u64,
}

impl<W: Write> Writer<W> {
    fn put(&mut self, bytes: &[u8]) -> std::io::Result<()> {
        self.out.write_all(bytes)?;
        self.pos += bytes.len() as u64;
        Ok(())
    }
    fn u16(&mut self, v: u16) -> std::io::Result<()> {
        self.put(&v.to_le_bytes())
    }
    fn u32(&mut self, v: u32) -> std::io::Result<()> {
        self.put(&v.to_le_bytes())
    }
    fn u64(&mut self, v: u64) -> std::io::Result<()> {
        self.put(&v.to_le_bytes())
    }

    fn local_header(&mut self, p: &Planned) -> std::io::Result<Written> {
        let local_offset = self.pos;
        let zip64 = p.size >= 0xffff_ffff;
        let name = p.name.as_bytes();
        let mut extra = Vec::new();
        if zip64 {
            extra.extend_from_slice(&EXTRA_ZIP64.to_le_bytes());
            extra.extend_from_slice(&16u16.to_le_bytes());
            extra.extend_from_slice(&p.size.to_le_bytes());
            extra.extend_from_slice(&p.size.to_le_bytes());
        }
        // Pad the extra field so the data starts on the next page boundary. A padding
        // field is at least 4 bytes (its header), so a shortfall of 1 to 3 bytes is
        // padded through to the boundary after.
        let data_at = local_offset + 30 + name.len() as u64 + extra.len() as u64;
        let mut pad = (ALIGNMENT - data_at % ALIGNMENT) % ALIGNMENT;
        if pad > 0 && pad < 4 {
            pad += ALIGNMENT;
        }
        if pad > 0 {
            extra.extend_from_slice(&EXTRA_PADDING.to_le_bytes());
            extra.extend_from_slice(&((pad - 4) as u16).to_le_bytes());
            extra.resize(extra.len() + (pad - 4) as usize, 0);
        }
        self.u32(SIG_LOCAL)?;
        self.u16(if zip64 { VERSION_ZIP64 } else { VERSION_STORED })?;
        self.u16(FLAG_UTF8)?;
        self.u16(0)?;
        self.u16(DOS_TIME)?;
        self.u16(DOS_DATE)?;
        self.u32(p.crc32)?;
        let saturated = if zip64 { 0xffff_ffff } else { p.size as u32 };
        self.u32(saturated)?;
        self.u32(saturated)?;
        self.u16(name.len() as u16)?;
        self.u16(extra.len() as u16)?;
        self.put(name)?;
        self.put(&extra)?;
        debug_assert_eq!(self.pos % ALIGNMENT, 0);
        Ok(Written {
            name: p.name.clone(),
            crc32: p.crc32,
            size: p.size,
            local_offset,
        })
    }

    fn central_header(&mut self, w: &Written) -> std::io::Result<()> {
        let name = w.name.as_bytes();
        let size64 = w.size >= 0xffff_ffff;
        let offset64 = w.local_offset >= 0xffff_ffff;
        let mut extra = Vec::new();
        if size64 || offset64 {
            let mut data = Vec::new();
            if size64 {
                data.extend_from_slice(&w.size.to_le_bytes());
                data.extend_from_slice(&w.size.to_le_bytes());
            }
            if offset64 {
                data.extend_from_slice(&w.local_offset.to_le_bytes());
            }
            extra.extend_from_slice(&EXTRA_ZIP64.to_le_bytes());
            extra.extend_from_slice(&(data.len() as u16).to_le_bytes());
            extra.extend_from_slice(&data);
        }
        self.u32(SIG_CENTRAL)?;
        self.u16(VERSION_MADE_BY)?;
        self.u16(if size64 || offset64 {
            VERSION_ZIP64
        } else {
            VERSION_STORED
        })?;
        self.u16(FLAG_UTF8)?;
        self.u16(0)?;
        self.u16(DOS_TIME)?;
        self.u16(DOS_DATE)?;
        self.u32(w.crc32)?;
        let size = if size64 { 0xffff_ffff } else { w.size as u32 };
        self.u32(size)?;
        self.u32(size)?;
        self.u16(name.len() as u16)?;
        self.u16(extra.len() as u16)?;
        self.u16(0)?;
        self.u16(0)?;
        self.u16(0)?;
        self.u32(0o100644 << 16)?;
        self.u32(if offset64 {
            0xffff_ffff
        } else {
            w.local_offset as u32
        })?;
        self.put(name)?;
        self.put(&extra)
    }

    fn end(&mut self, count: u64, cd_offset: u64, cd_size: u64) -> std::io::Result<()> {
        let zip64 = count >= 0xffff || cd_offset >= 0xffff_ffff || cd_size >= 0xffff_ffff;
        if zip64 {
            let at = self.pos;
            self.u32(SIG_EOCD64)?;
            self.u64(44)?;
            self.u16(VERSION_MADE_BY)?;
            self.u16(VERSION_ZIP64)?;
            self.u32(0)?;
            self.u32(0)?;
            self.u64(count)?;
            self.u64(count)?;
            self.u64(cd_size)?;
            self.u64(cd_offset)?;
            self.u32(SIG_EOCD64_LOCATOR)?;
            self.u32(0)?;
            self.u64(at)?;
            self.u32(1)?;
        }
        self.u32(SIG_EOCD)?;
        self.u16(0)?;
        self.u16(0)?;
        let count16 = if zip64 { 0xffff } else { count as u16 };
        self.u16(count16)?;
        self.u16(count16)?;
        self.u32(if zip64 { 0xffff_ffff } else { cd_size as u32 })?;
        self.u32(if zip64 { 0xffff_ffff } else { cd_offset as u32 })?;
        self.u16(ARCHIVE_COMMENT.len() as u16)?;
        self.put(ARCHIVE_COMMENT.as_bytes())
    }
}

/// Pack every file under `dir` into the container `out`, written to a sibling
/// temporary file and renamed into place, so `out` is complete or absent.
pub fn pack(dir: &Path, out: &Path) -> Result<PackReport, ContainerError> {
    let shown = out.display().to_string();
    let files = walk(dir)?;
    if files.is_empty() {
        return Err(ContainerError::Format(
            dir.display().to_string(),
            "directory has no files to pack".into(),
        ));
    }
    let out_abs = std::fs::canonicalize(out.parent().unwrap_or(Path::new(".")))
        .ok()
        .map(|p| p.join(out.file_name().unwrap_or_default()));
    let mut manifest = Manifest::default();
    let mut planned = Vec::with_capacity(files.len() + 1);
    for (name, path) in files {
        if name == MANIFEST_NAME {
            continue;
        }
        if let (Some(a), Ok(b)) = (&out_abs, std::fs::canonicalize(&path)) {
            if *a == b {
                return Err(ContainerError::Format(
                    shown,
                    "output file lies inside the directory being packed".into(),
                ));
            }
        }
        let d = digest_file(&path)?;
        manifest.entries.insert(
            name.clone(),
            ManifestEntry {
                size: d.size,
                sha256: d.sha256,
            },
        );
        planned.push(Planned {
            name,
            source: Source::File(path),
            crc32: d.crc32,
            size: d.size,
        });
    }
    let text = manifest.render().into_bytes();
    let fingerprint = hex(&Sha256::digest(&text));
    planned.insert(
        0,
        Planned {
            name: MANIFEST_NAME.into(),
            crc32: crc32fast::hash(&text),
            size: text.len() as u64,
            source: Source::Memory(text),
        },
    );

    let tmp = out.with_file_name(format!(
        "{}.tmp-{}",
        out.file_name().unwrap_or_default().to_string_lossy(),
        std::process::id()
    ));
    let result = (|| -> Result<u64, ContainerError> {
        let file =
            File::create(&tmp).map_err(|e| ContainerError::Io(tmp.display().to_string(), e))?;
        let io = |e| ContainerError::Io(tmp.display().to_string(), e);
        let mut w = Writer {
            out: BufWriter::with_capacity(1 << 20, file),
            pos: 0,
        };
        let mut written = Vec::with_capacity(planned.len());
        let mut buf = vec![0u8; 1 << 20];
        for p in &planned {
            let entry = w.local_header(p).map_err(io)?;
            match &p.source {
                Source::Memory(bytes) => w.put(bytes).map_err(io)?,
                Source::File(path) => {
                    let mut f = File::open(path)
                        .map_err(|e| ContainerError::Io(path.display().to_string(), e))?;
                    let mut copied = 0u64;
                    loop {
                        let n = f
                            .read(&mut buf)
                            .map_err(|e| ContainerError::Io(path.display().to_string(), e))?;
                        if n == 0 {
                            break;
                        }
                        w.put(&buf[..n]).map_err(io)?;
                        copied += n as u64;
                    }
                    if copied != p.size {
                        return Err(ContainerError::Format(
                            path.display().to_string(),
                            "file changed while being packed".into(),
                        ));
                    }
                }
            }
            written.push(entry);
        }
        let cd_offset = w.pos;
        for entry in &written {
            w.central_header(entry).map_err(io)?;
        }
        let cd_size = w.pos - cd_offset;
        w.end(written.len() as u64, cd_offset, cd_size)
            .map_err(io)?;
        w.out.flush().map_err(io)?;
        w.out.get_ref().sync_all().map_err(io)?;
        Ok(w.pos)
    })();
    let bytes = match result {
        Ok(bytes) => bytes,
        Err(e) => {
            let _ = std::fs::remove_file(&tmp);
            return Err(e);
        }
    };
    if let Err(e) = std::fs::rename(&tmp, out) {
        let _ = std::fs::remove_file(&tmp);
        return Err(ContainerError::Io(shown, e));
    }
    Ok(PackReport {
        entries: planned.len(),
        bytes,
        fingerprint,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tempdir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("graphite-container-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn fixture(dir: &Path) {
        std::fs::write(dir.join("graph.metadata"), b"metadata bytes").unwrap();
        std::fs::write(
            dir.join("graph.nodedata"),
            (0..10_000u32)
                .flat_map(|i| i.to_be_bytes())
                .collect::<Vec<_>>(),
        )
        .unwrap();
        std::fs::write(dir.join("forward.properties"), b"nodes=3\narcs=2\n").unwrap();
        std::fs::create_dir_all(dir.join("nested")).unwrap();
        std::fs::write(dir.join("nested/empty"), b"").unwrap();
    }

    #[test]
    fn packs_aligned_stored_entries_that_verify_and_unpack_identically() {
        let root = tempdir("roundtrip");
        let src = root.join("src");
        std::fs::create_dir_all(&src).unwrap();
        fixture(&src);
        let out = root.join("g.graphite");
        let report = pack(&src, &out).unwrap();
        assert_eq!(report.entries, 5);
        assert_eq!(report.bytes, std::fs::metadata(&out).unwrap().len());

        let c = Container::open(&out).unwrap();
        let names: Vec<_> = c.entries().map(|e| e.name.clone()).collect();
        assert_eq!(
            names,
            [
                "META-INF/graphite.manifest",
                "forward.properties",
                "graph.metadata",
                "graph.nodedata",
                "nested/empty"
            ]
        );
        for e in c.entries() {
            assert_eq!(e.offset % ALIGNMENT, 0, "{} is not page-aligned", e.name);
        }
        assert_eq!(&c.bytes("graph.metadata").unwrap()[..], b"metadata bytes");
        assert_eq!(c.bytes("nested/empty").unwrap().len(), 0);
        assert!(c.bytes("missing").is_none());
        let manifest = c.manifest().unwrap().unwrap();
        assert_eq!(manifest.entries.len(), 4);
        assert_eq!(manifest.entries["graph.metadata"].size, 14);
        assert_eq!(c.fingerprint().unwrap(), report.fingerprint);

        let v = c.verify().unwrap();
        assert!(v.ok(), "{:?}", v.failures);
        assert!(v.has_manifest);
        assert!(v.entries.iter().all(|e| e.crc_ok));
        assert!(v
            .entries
            .iter()
            .filter(|e| e.name != MANIFEST_NAME)
            .all(|e| e.sha_ok == Some(true)));

        let back = root.join("back");
        assert_eq!(c.unpack(&back).unwrap(), 5);
        for (name, _) in walk(&src).unwrap() {
            assert_eq!(
                std::fs::read(src.join(&name)).unwrap(),
                std::fs::read(back.join(&name)).unwrap(),
                "{name}"
            );
        }
        // Packing again gives the same bytes: the fingerprint identifies content.
        let again = root.join("again.graphite");
        pack(&src, &again).unwrap();
        assert_eq!(std::fs::read(&out).unwrap(), std::fs::read(&again).unwrap());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_flipped_byte_fails_verification_and_a_truncated_file_does_not_open() {
        let root = tempdir("corrupt");
        let src = root.join("src");
        std::fs::create_dir_all(&src).unwrap();
        fixture(&src);
        let out = root.join("g.graphite");
        pack(&src, &out).unwrap();

        let mut bytes = std::fs::read(&out).unwrap();
        let c = Container::open(&out).unwrap();
        let at = c.entry("graph.nodedata").unwrap().offset as usize + 100;
        drop(c);
        bytes[at] ^= 0xff;
        let bad = root.join("bad.graphite");
        std::fs::write(&bad, &bytes).unwrap();
        let v = Container::open(&bad).unwrap().verify().unwrap();
        assert!(!v.ok());
        assert_eq!(
            v.failures,
            [
                "graph.nodedata: CRC-32 mismatch",
                "graph.nodedata: differs from the manifest"
            ]
        );
        assert!(matches!(
            Container::open(&bad).unwrap().unpack(&root.join("x")),
            Err(ContainerError::Entry(..))
        ));

        let cut = root.join("cut.graphite");
        let full = std::fs::read(&out).unwrap();
        std::fs::write(&cut, &full[..full.len() - 40]).unwrap();
        assert!(matches!(
            Container::open(&cut),
            Err(ContainerError::Format(..))
        ));
        std::fs::write(&cut, &full[..full.len() / 2]).unwrap();
        assert!(matches!(
            Container::open(&cut),
            Err(ContainerError::Format(..))
        ));
        std::fs::write(&cut, b"not a zip at all").unwrap();
        assert!(matches!(
            Container::open(&cut),
            Err(ContainerError::Format(..))
        ));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn manifest_round_trips_and_rejects_malformed_text() {
        let mut m = Manifest::default();
        m.entries.insert(
            "b".into(),
            ManifestEntry {
                size: 2,
                sha256: "ab".repeat(32),
            },
        );
        m.entries.insert(
            "a b".into(),
            ManifestEntry {
                size: 0,
                sha256: "00".repeat(32),
            },
        );
        let text = m.render();
        assert!(text.starts_with("graphite-graph 1\n"));
        assert_eq!(Manifest::parse(text.as_bytes()).unwrap(), m);
        assert!(Manifest::parse(b"other 1\n").is_err());
        assert!(Manifest::parse(b"graphite-graph 1\nzz  1  x\n").is_err());
        assert!(Manifest::parse(
            format!("graphite-graph 1\n{}  x  name\n", "00".repeat(32)).as_bytes()
        )
        .is_err());
    }

    #[test]
    fn unsafe_names_and_empty_directories_are_refused() {
        assert!(!valid_name("../x"));
        assert!(!valid_name("/x"));
        assert!(!valid_name("a/../b"));
        assert!(!valid_name("a\\b"));
        assert!(!valid_name(""));
        assert!(valid_name("META-INF/graphite.manifest"));
        let root = tempdir("empty");
        assert!(matches!(
            pack(&root, &root.join("g.graphite")),
            Err(ContainerError::Format(..))
        ));
        std::fs::remove_dir_all(root).unwrap();
    }
}
