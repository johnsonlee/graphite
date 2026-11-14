//! A conservative build heap default from class count, excluding archive resources.
use std::ffi::OsString;
use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

const GIB: u64 = 1024 * 1024 * 1024;

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
        let bytes = (8 * GIB).max(self.classes.saturating_mul(112 * 1024));
        bytes.div_ceil(2 * GIB) * 2
    }
}

pub(super) fn heap_gib(args: &[OsString]) -> u64 {
    let mut size = CodeSize::default();
    if let Some(input) = crate::build_args::walk(args).find(|arg| arg.positional) {
        // Sizing is best-effort; preserve the frontend's normal error reporting
        // for missing, unreadable or malformed inputs.
        if inspect(Path::new(input.raw), &mut size).is_err() {
            return 8;
        }
    }
    size.heap_gib()
}

fn inspect(path: &Path, size: &mut CodeSize) -> io::Result<()> {
    let metadata = path.metadata()?;
    if metadata.is_dir() {
        let mut pending = vec![path.to_owned()];
        while let Some(directory) = pending.pop() {
            for entry in std::fs::read_dir(directory)? {
                let entry = entry?;
                let kind = entry.file_type()?;
                // Do not follow directory symlinks: a cycle must not stall launch.
                if kind.is_dir() {
                    pending.push(entry.path());
                } else if kind.is_file()
                    && entry.path().extension().is_some_and(|ext| ext == "class")
                {
                    size.add(1, entry.metadata()?.len());
                }
            }
        }
    } else if metadata.is_file() {
        let mut archive = zip::ZipArchive::new(File::open(path)?)?;
        for index in 0..archive.len() {
            let entry = archive.by_index_raw(index)?;
            if entry.is_dir() {
                continue;
            }
            if entry.name().ends_with(".class") {
                size.add(1, entry.size());
            } else if entry.name().ends_with(".dex") {
                drop(entry);
                let mut entry = archive.by_index(index)?;
                // Read only the fixed header, including class_defs_size at 96.
                // Never inflate the whole DEX or retain its code in the launcher.
                let mut header = [0; 112];
                entry.read_exact(&mut header)?;
                let classes = dex_classes(&header).ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "invalid DEX header")
                })?;
                size.add(classes, entry.size());
            }
        }
    }
    Ok(())
}

fn dex_classes(header: &[u8; 112]) -> Option<u64> {
    if &header[..4] != b"dex\n" || header[7] != 0 {
        return None;
    }
    let count: [u8; 4] = header[96..100].try_into().ok()?;
    match &header[40..44] {
        [0x78, 0x56, 0x34, 0x12] => Some(u32::from_le_bytes(count).into()),
        [0x12, 0x34, 0x56, 0x78] => Some(u32::from_be_bytes(count).into()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::write::SimpleFileOptions;

    fn dex(classes: u32) -> [u8; 112] {
        let mut header = [0; 112];
        header[..8].copy_from_slice(b"dex\n035\0");
        header[40..44].copy_from_slice(&0x12345678u32.to_le_bytes());
        header[96..100].copy_from_slice(&classes.to_le_bytes());
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
        assert!(
            CodeSize {
                classes: u64::MAX,
                bytes: 0
            }
            .heap_gib()
                > 8
        );
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
        inspect(&jar, &mut size).unwrap();
        assert_eq!((size.classes, size.bytes), (1, 3));
        std::fs::create_dir(temp.path().join("classes")).unwrap();
        std::fs::write(temp.path().join("classes/B.class"), [0; 7]).unwrap();
        let mut size = CodeSize::default();
        inspect(temp.path(), &mut size).unwrap();
        assert_eq!((size.classes, size.bytes), (1, 7));
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
        assert_eq!(heap_gib(&[invalid_dex.into_os_string()]), 8);
        assert_eq!(dex_classes(&[0; 112]), None);
        let mut header = dex(100_000);
        header[40..44].copy_from_slice(&0x12345678u32.to_be_bytes());
        header[96..100].copy_from_slice(&100_000u32.to_be_bytes());
        assert_eq!(dex_classes(&header), Some(100_000));
    }
}
