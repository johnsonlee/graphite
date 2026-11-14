//! A conservative build heap default from class count, excluding archive resources.
use std::ffi::OsString;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::Path;

const GIB: u64 = 1024 * 1024 * 1024;
pub(super) const MAX_HEAP_GIB: u64 = 31;

#[derive(Default)]
struct CodeSize {
    classes: u64,
    bytes: u64,
}

impl CodeSize {
    fn add(&mut self, classes: u64, bytes: u64) {
        self.classes = self.classes.saturating_add(classes);
        self.bytes = self.bytes.saturating_add(bytes);
    }

    fn heap_gib(&self) -> u64 {
        // Keep the existing 8 GiB floor, with 112 KiB per class rounded to 2 GiB.
        // This is a conservative default, not a fitted minimum heap: method
        // bodies, dependency analysis and graph features also affect live memory.
        // Do not multiply bytecode size: class files and DEX have different
        // encodings, and that rule inflated already-supported real JAR inputs.
        // Bound automatic sizing at 31 GiB; explicit options remain caller-owned.
        let bytes = (8 * GIB).max(self.classes.saturating_mul(112 * 1024));
        (bytes.div_ceil(2 * GIB) * 2).min(MAX_HEAP_GIB)
    }
}

// Nested libraries need a seekable ZIP reader. Spool into an anonymous tempfile,
// with bounded inflation per library and per build rather than retaining bytes in RAM.
const MAX_NESTED_BYTES: u64 = 256 * 1024 * 1024;
const MAX_TOTAL_NESTED_BYTES: u64 = 512 * 1024 * 1024;

#[derive(Clone, Copy)]
enum ArchiveKind {
    Classes,
    Apk,
    Boot,
    War,
}

pub(super) fn heap_gib(args: &[OsString]) -> u64 {
    let mut size = CodeSize::default();
    let include_libraries = crate::build_args::walk(args)
        .any(|arg| arg.flag("--include-libs") || arg.flag("--include-libs=true"));
    if let Some(input) = crate::build_args::walk(args).find(|arg| arg.positional) {
        // Preserve the frontend's own error reporting. Valid entries counted
        // before a malformed/unreadable sibling must not be discarded.
        if let Err(error) = inspect(Path::new(input.raw), &mut size, include_libraries) {
            eprintln!("Warning: JVM heap input inspection was incomplete: {error}");
        }
    }
    size.heap_gib()
}

fn inspect(path: &Path, size: &mut CodeSize, include_libraries: bool) -> io::Result<()> {
    let metadata = path.metadata()?;
    let mut nested_budget = MAX_TOTAL_NESTED_BYTES;
    if metadata.is_dir() {
        let mut pending = vec![path.to_owned()];
        while let Some(directory) = pending.pop() {
            for entry in std::fs::read_dir(directory)? {
                let entry = entry?;
                let kind = entry.file_type()?;
                // Match Files.walk's lack of directory-symlink traversal.
                if kind.is_dir() {
                    pending.push(entry.path());
                } else if kind.is_file() || (kind.is_symlink() && entry.path().is_file()) {
                    let path = entry.path();
                    if extension_is(&path, "class") {
                        size.add(1, entry.metadata()?.len());
                    } else if extension_is(&path, "jar") {
                        // Directory jars are application inputs even without
                        // --include-libs. Count all conservatively: package and
                        // dependency scopes can only reduce the loaded subset.
                        if let Ok(file) = File::open(&path) {
                            let _ = inspect_archive(
                                file,
                                ArchiveKind::Classes,
                                size,
                                false,
                                &mut nested_budget,
                            );
                        }
                    }
                }
            }
        }
    } else if metadata.is_file() {
        let kind = if extension_is(path, "apk") {
            ArchiveKind::Apk
        } else if extension_is(path, "war") {
            ArchiveKind::War
        } else if extension_is(path, "jar") {
            ArchiveKind::Boot
        } else {
            ArchiveKind::Classes
        };
        inspect_archive(
            File::open(path)?,
            kind,
            size,
            include_libraries,
            &mut nested_budget,
        )?;
    }
    Ok(())
}

fn extension_is(path: &Path, extension: &str) -> bool {
    path.extension()
        .and_then(|s| s.to_str())
        .is_some_and(|s| s.eq_ignore_ascii_case(extension))
}

fn inspect_archive<R: Read + Seek>(
    reader: R,
    mut kind: ArchiveKind,
    size: &mut CodeSize,
    include_libraries: bool,
    nested_budget: &mut u64,
) -> io::Result<()> {
    let mut archive = zip::ZipArchive::new(reader)?;
    if matches!(kind, ArchiveKind::Boot) && archive.index_for_name("BOOT-INF/classes/").is_none() {
        kind = ArchiveKind::Classes;
    }
    for index in 0..archive.len() {
        let entry = match archive.by_index_raw(index) {
            Ok(entry) if !entry.is_dir() => entry,
            _ => continue,
        };
        let name = entry.name().to_owned();
        let bytes = entry.size();
        drop(entry);
        if matches!(kind, ArchiveKind::Apk) {
            // DexFileFactory's ZipDexContainer checks magic in every entry,
            // including assets and non-.dex names. Never trust a suffix alone.
            if bytes < 112 {
                continue;
            }
            if let Ok(mut entry) = archive.by_index(index) {
                let mut header = [0; 112];
                if entry.read_exact(&mut header).is_ok() {
                    if let Some(classes) = dex_classes(&header, bytes) {
                        size.add(classes, bytes);
                    }
                }
            }
            continue;
        }
        let prefix = match kind {
            ArchiveKind::Boot => "BOOT-INF/classes/",
            ArchiveKind::War => "WEB-INF/classes/",
            _ => "",
        };
        if name.starts_with(prefix) && name.ends_with(".class") {
            size.add(1, bytes);
        }
        let lib_prefix = match kind {
            ArchiveKind::Boot => "BOOT-INF/lib/",
            ArchiveKind::War => "WEB-INF/lib/",
            _ => continue,
        };
        if include_libraries && name.starts_with(lib_prefix) && name.ends_with(".jar") {
            // Conservative superset of --lib-filter/--include/dependency scopes.
            // The actual loader does not recurse into jars inside these jars.
            let nested = if bytes > MAX_NESTED_BYTES || bytes > *nested_budget {
                Err(io::Error::other(
                    "nested archive inspection budget exceeded",
                ))
            } else {
                *nested_budget -= bytes;
                (|| -> io::Result<CodeSize> {
                    let mut entry = archive.by_index(index)?;
                    let mut file = tempfile::tempfile()?;
                    let copied =
                        io::copy(&mut entry.by_ref().take(bytes.saturating_add(1)), &mut file)?;
                    if copied != bytes {
                        return Err(io::Error::other("nested archive size mismatch"));
                    }
                    file.seek(SeekFrom::Start(0))?;
                    let mut nested = CodeSize::default();
                    inspect_archive(
                        file,
                        ArchiveKind::Classes,
                        &mut nested,
                        false,
                        nested_budget,
                    )?;
                    Ok(nested)
                })()
            };
            match nested {
                Ok(nested) => size.add(nested.classes, nested.bytes),
                Err(error) => {
                    // Each ZIP class requires local and central entry headers
                    // (>64 bytes). Bound conservatively instead of dropping
                    // an enabled library when inspection is unavailable.
                    size.add(bytes.div_ceil(64), bytes);
                    eprintln!("Warning: JVM heap inspection of {name} is incomplete ({error}); using a conservative archive bound");
                }
            }
        }
    }
    Ok(())
}

fn dex_classes(header: &[u8; 112], entry_bytes: u64) -> Option<u64> {
    if &header[..4] != b"dex\n"
        || header[7] != 0
        || !matches!(&header[4..7], b"035" | b"037" | b"038" | b"039")
    {
        return None;
    }
    let read = |offset: usize| -> Option<u64> {
        let value: [u8; 4] = header[offset..offset + 4].try_into().ok()?;
        match &header[40..44] {
            [0x78, 0x56, 0x34, 0x12] => Some(u32::from_le_bytes(value).into()),
            [0x12, 0x34, 0x56, 0x78] => Some(u32::from_be_bytes(value).into()),
            _ => None,
        }
    };
    let file_bytes = read(32)?;
    let header_bytes = read(36)?;
    let classes = read(96)?;
    let classes_offset = read(100)?;
    if header_bytes != 112 || file_bytes < header_bytes || file_bytes > entry_bytes {
        return None;
    }
    if classes == 0 {
        return (classes_offset == 0).then_some(0);
    }
    if classes_offset < header_bytes
        || classes_offset % 4 != 0
        || classes_offset.checked_add(classes.checked_mul(32)?)? > file_bytes
    {
        return None;
    }
    Some(classes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::write::SimpleFileOptions;

    fn dex(classes: u32) -> Vec<u8> {
        let file_bytes = 112 + classes * 32;
        let mut header = vec![0; file_bytes as usize];
        header[..8].copy_from_slice(b"dex\n035\0");
        header[32..36].copy_from_slice(&file_bytes.to_le_bytes());
        header[36..40].copy_from_slice(&112u32.to_le_bytes());
        header[40..44].copy_from_slice(&0x12345678u32.to_le_bytes());
        header[96..100].copy_from_slice(&classes.to_le_bytes());
        header[100..104].copy_from_slice(&(if classes == 0 { 0u32 } else { 112u32 }).to_le_bytes());
        header
    }

    fn archive(path: &Path, entries: &[(&str, &[u8])]) {
        let mut writer = zip::ZipWriter::new(File::create(path).unwrap());
        for (name, bytes) in entries {
            writer
                .start_file(
                    *name,
                    SimpleFileOptions::default()
                        .compression_method(zip::CompressionMethod::Deflated),
                )
                .unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap();
    }

    #[test]
    fn class_budget_rounds_up_at_two_gib_boundaries_without_byte_multiplier() {
        assert_eq!(CodeSize::default().heap_gib(), 8);
        for (classes, expected) in [(74_898, 8), (74_899, 10), (131_072, 14), (131_073, 16)] {
            for bytes in [0, u64::MAX] {
                assert_eq!(CodeSize { classes, bytes }.heap_gib(), expected);
            }
        }
        for (classes, expected) in [
            (30 * GIB / (112 * 1024), 30),
            (30 * GIB / (112 * 1024) + 1, 31),
            (u64::MAX, 31),
        ] {
            assert_eq!(CodeSize { classes, bytes: 0 }.heap_gib(), expected);
        }
    }

    #[test]
    fn real_fixture_metadata_preserves_supported_defaults_and_large_apk_budget() {
        // Metadata is observed from real inputs; expectations are configuration
        // defaults, not minimum-heap measurements. Historical 4 GiB whole-JAR
        // gates disable features and cannot calibrate the default CLI's heap.
        for (name, classes, bytes, expected) in [
            ("Kotlin compiler", 24_941, 155_369_431, 8),
            ("Tika", 33_128, 114_602_889, 8),
            ("Hive", 38_999, 192_448_259, 8),
            ("Android-all", 48_226, 184_607_622, 8),
            ("Netflix", 47_987, 39_761_900, 8),
            ("Chrome", 18_571, 11_799_036, 8),
            ("GoTV", 13_612, 16_519_264, 8),
            ("TVBox", 13_072, 13_814_304, 8),
            ("Coupang", 132_532, 140_388_432, 16),
        ] {
            assert_eq!(CodeSize { classes, bytes }.heap_gib(), expected, "{name}");
        }
    }

    #[test]
    fn multidex_counts_classes_and_ignores_assets_and_option_values() {
        let temp = tempfile::tempdir().unwrap();
        let input = temp.path().join("app.apk");
        let a = dex(65_536);
        let b = dex(65_536);
        archive(
            &input,
            &[
                ("classes.dex", &a),
                ("classes2.dex", &b),
                ("assets/ignored", &[0; 1024]),
            ],
        );
        let args = vec![
            "--output".into(),
            "ignored.jar".into(),
            input.into_os_string(),
        ];
        assert_eq!(heap_gib(&args), 14);
    }

    #[test]
    fn jar_and_directory_inspection_count_only_class_code() {
        let temp = tempfile::tempdir().unwrap();
        let jar = temp.path().join("app.jar");
        archive(&jar, &[("a/A.class", &[1, 2, 3]), ("resource", &[0; 100])]);
        let mut size = CodeSize::default();
        inspect(&jar, &mut size, false).unwrap();
        assert_eq!((size.classes, size.bytes), (1, 3));
        std::fs::create_dir(temp.path().join("classes")).unwrap();
        std::fs::write(temp.path().join("classes/B.class"), [0; 7]).unwrap();
        let mut size = CodeSize::default();
        inspect(temp.path(), &mut size, false).unwrap();
        assert_eq!((size.classes, size.bytes), (2, 10));
    }

    #[test]
    fn apk_uses_header_magic_and_ignores_invalid_asset_counts() {
        let temp = tempfile::tempdir().unwrap();
        let input = temp.path().join("app.apk");
        let valid = dex(100_000);
        let mut invalid = dex(1);
        invalid[96..100].copy_from_slice(&u32::MAX.to_le_bytes());
        archive(
            &input,
            &[
                ("assets/real-code.bin", &valid),
                ("assets/encrypted.dex", b"encrypted data that is not DEX"),
                ("assets/truncated.dex", &invalid),
                ("resource.class", &[0; 112]),
            ],
        );
        // Mark the irrelevant asset encrypted in both ZIP headers. The estimator
        // must keep good DEX counts when by_index rejects that entry.
        let mut bytes = std::fs::read(&input).unwrap();
        for offset in 0..bytes.len().saturating_sub(46) {
            let (flags, name_length, name_offset) = match &bytes[offset..offset + 4] {
                b"PK\x03\x04" => (6, 26, 30),
                b"PK\x01\x02" => (8, 28, 46),
                _ => continue,
            };
            let length = u16::from_le_bytes(
                bytes[offset + name_length..offset + name_length + 2]
                    .try_into()
                    .unwrap(),
            ) as usize;
            if bytes.get(offset + name_offset..offset + name_offset + length)
                == Some(b"assets/encrypted.dex")
            {
                bytes[offset + flags] |= 1;
            }
        }
        std::fs::write(&input, bytes).unwrap();
        assert_eq!(heap_gib(&[input.into_os_string()]), 12);
        let good = dex(10);
        assert_eq!(
            dex_classes(good[..112].try_into().unwrap(), good.len() as u64),
            Some(10)
        );
        let mut bad = good.clone();
        bad[100..104].copy_from_slice(&113u32.to_le_bytes());
        assert_eq!(
            dex_classes(bad[..112].try_into().unwrap(), bad.len() as u64),
            None
        );
        assert_eq!(dex_classes(good[..112].try_into().unwrap(), 112), None);
        assert_eq!(
            dex_classes(invalid[..112].try_into().unwrap(), invalid.len() as u64),
            None
        );
    }

    #[test]
    fn boot_and_war_count_enabled_nested_libraries_but_not_nested_assets() {
        let temp = tempfile::tempdir().unwrap();
        let library = temp.path().join("lib.jar");
        archive(&library, &[("dep/A.class", &[1]), ("dep/B.class", &[1, 2])]);
        let library_bytes = std::fs::read(&library).unwrap();
        for (extension, root) in [("jar", "BOOT-INF"), ("war", "WEB-INF")] {
            let path = temp.path().join(format!("app.{extension}"));
            let mut zip = zip::ZipWriter::new(File::create(&path).unwrap());
            zip.add_directory(format!("{root}/classes/"), SimpleFileOptions::default())
                .unwrap();
            for (name, bytes) in [
                (format!("{root}/classes/app/Main.class"), &[0u8][..]),
                (format!("{root}/lib/dep.jar"), library_bytes.as_slice()),
                ("assets/ignored.jar".into(), library_bytes.as_slice()),
                ("launcher/Ignore.class".into(), &[0u8][..]),
            ] {
                zip.start_file(
                    name,
                    SimpleFileOptions::default()
                        .compression_method(zip::CompressionMethod::Deflated),
                )
                .unwrap();
                zip.write_all(bytes).unwrap();
            }
            zip.finish().unwrap();
            let mut size = CodeSize::default();
            inspect(&path, &mut size, false).unwrap();
            assert_eq!(size.classes, 1);
            let mut size = CodeSize::default();
            inspect(&path, &mut size, true).unwrap();
            assert_eq!(size.classes, 3);
            let mut size = CodeSize::default();
            let kind = if extension == "war" {
                ArchiveKind::War
            } else {
                ArchiveKind::Boot
            };
            inspect_archive(File::open(&path).unwrap(), kind, &mut size, true, &mut 0).unwrap();
            assert_eq!(size.classes, 1 + (library_bytes.len() as u64).div_ceil(64));
        }
    }

    #[test]
    fn malformed_or_missing_inputs_keep_floor_and_dex_byte_order_is_respected() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("invalid.apk");
        std::fs::write(&path, b"invalid zip").unwrap();
        assert_eq!(heap_gib(&[path.into_os_string()]), 8);
        assert_eq!(heap_gib(&[temp.path().join("missing").into_os_string()]), 8);
        assert_eq!(heap_gib(&[]), 8);
        let invalid_dex = temp.path().join("invalid-dex.apk");
        archive(
            &invalid_dex,
            &[("classes.dex", &dex(100_000)), ("classes2.dex", &[0; 112])],
        );
        assert_eq!(heap_gib(&[invalid_dex.into_os_string()]), 12);
        assert_eq!(dex_classes(&[0; 112], 112), None);
        let mut header = dex(100_000);
        header[40..44].copy_from_slice(&0x12345678u32.to_be_bytes());
        let file_bytes = header.len() as u32;
        header[32..36].copy_from_slice(&file_bytes.to_be_bytes());
        header[36..40].copy_from_slice(&112u32.to_be_bytes());
        header[96..100].copy_from_slice(&100_000u32.to_be_bytes());
        header[100..104].copy_from_slice(&112u32.to_be_bytes());
        assert_eq!(
            dex_classes(header[..112].try_into().unwrap(), header.len() as u64),
            Some(100_000)
        );
    }
}
