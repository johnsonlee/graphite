//! The persisted graph as one packed file: `graphite pack`, `unpack`, `verify`, `info`.
//!
//! `pack` turns the directory a frontend writes (or an explicit list of files) into a
//! `.graphite` container, `unpack` turns it back, `verify` checks a container against
//! its central directory, manifest and `.sha256` file, and `info` lists what a container
//! holds. The container is a STORED zip; `graphite_storage::container` says what the
//! reader relies on.

use graphite_storage::container::{self, Container, MANIFEST_NAME};
use serde_json::json;
use std::path::{Path, PathBuf};

#[derive(clap::Args, Debug)]
pub struct PackArgs {
    /// A saved graph directory (what `graphite build -o <dir>` writes), or the files
    /// to pack, each stored under its file name
    #[arg(required = true)]
    inputs: Vec<PathBuf>,
    /// The .graphite file to write; complete or absent, with a .sha256 file beside it
    #[arg(long, short = 'o')]
    output: PathBuf,
}

#[derive(clap::Args, Debug)]
pub struct UnpackArgs {
    /// A .graphite file
    input: PathBuf,
    /// Directory to unpack into, created if absent (default: the current directory)
    output: Option<PathBuf>,
}

#[derive(clap::Args, Debug)]
pub struct VerifyArgs {
    /// A .graphite file
    input: PathBuf,
    /// Print one line per entry
    #[arg(long, short = 'v')]
    verbose: bool,
}

#[derive(clap::Args, Debug)]
pub struct InfoArgs {
    /// A .graphite file
    input: PathBuf,
}

pub fn pack(args: PackArgs) -> Result<(), String> {
    let report = match args.inputs.as_slice() {
        [dir] if dir.is_dir() => container::pack(dir, &args.output),
        inputs => {
            let mut files = Vec::with_capacity(inputs.len());
            for path in inputs {
                if path.is_dir() {
                    return Err(format!(
                        "{}: pack takes one directory, or files; not both",
                        path.display()
                    ));
                }
                let name = path
                    .file_name()
                    .ok_or_else(|| format!("{}: not a file", path.display()))?
                    .to_string_lossy()
                    .into_owned();
                files.push((name, path.clone()));
            }
            container::pack_files(&files, &args.output)
        }
    }
    .map_err(|e| e.to_string())?;
    println!(
        "Packed {} entries ({} bytes) into {}\nfingerprint: {}\nsha256: {} (written to {})",
        report.entries,
        report.bytes,
        args.output.display(),
        report.fingerprint,
        report.file_sha256,
        report.digest_file.display()
    );
    Ok(())
}

pub fn unpack(args: UnpackArgs) -> Result<(), String> {
    let c = Container::open(&args.input).map_err(|e| e.to_string())?;
    let dir = args.output.unwrap_or_else(|| PathBuf::from("."));
    let n = c.unpack(&dir).map_err(|e| e.to_string())?;
    println!("Unpacked {n} entries into {}", dir.display());
    Ok(())
}

pub fn info(args: InfoArgs) -> Result<(), String> {
    let c = Container::open(&args.input).map_err(|e| e.to_string())?;
    let entries: Vec<_> = c
        .entries()
        .map(|e| json!({ "name": e.name, "size": e.size, "offset": e.offset }))
        .collect();
    let text = serde_json::to_string_pretty(&json!({
        "file": args.input.display().to_string(),
        "format": container::ARCHIVE_COMMENT,
        "fingerprint": c.fingerprint(),
        "fileSha256": c.file_sha256(),
        "digestFile": container::digest_path(&args.input).display().to_string(),
        "entries": entries,
    }))
    .map_err(|e| e.to_string())?;
    println!("{text}");
    Ok(())
}

pub fn verify(args: VerifyArgs) -> Result<(), String> {
    verify_file(&args.input, args.verbose)
}

fn verify_file(file: &Path, verbose: bool) -> Result<(), String> {
    let c = Container::open(file).map_err(|e| e.to_string())?;
    let v = c.verify().map_err(|e| e.to_string())?;
    if verbose {
        for e in &v.entries {
            let sha = match e.sha_ok {
                Some(true) => "sha256 ok",
                Some(false) => "sha256 MISMATCH",
                None if e.name == MANIFEST_NAME => "manifest",
                None => "no manifest",
            };
            println!(
                "{:<9} {:<16} {:>12}  {}",
                if e.crc_ok { "crc ok" } else { "crc BAD" },
                sha,
                e.size,
                e.name
            );
        }
    }
    if !v.has_manifest {
        eprintln!(
            "warning: {} carries no manifest; only CRC-32 was checked",
            file.display()
        );
    }
    match v.digest_file {
        Some(true) => println!("file sha256 ok ({})", v.file_sha256),
        Some(false) => println!("file sha256 MISMATCH ({})", v.file_sha256),
        None => println!(
            "file sha256 {} (no .{} file beside it)",
            v.file_sha256,
            container::DIGEST_EXTENSION
        ),
    }
    if v.ok() {
        println!(
            "OK: {} entries verified in {}\nfingerprint: {}",
            v.entries.len(),
            file.display(),
            v.fingerprint.as_deref().unwrap_or("-")
        );
        Ok(())
    } else {
        Err(format!(
            "{} failed verification:\n  {}",
            file.display(),
            v.failures.join("\n  ")
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use graphite_storage::container::REQUIRED_ENTRIES;

    fn tempdir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("graphite-pack-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Every required entry, tiny, so the directory packs as a graph.
    fn graph_fixture(dir: &Path) {
        std::fs::create_dir_all(dir).unwrap();
        for name in REQUIRED_ENTRIES {
            std::fs::write(dir.join(name), name.as_bytes()).unwrap();
        }
    }

    #[test]
    fn pack_verify_info_and_unpack_round_trip() {
        let root = tempdir("roundtrip");
        let src = root.join("g");
        graph_fixture(&src);
        std::fs::write(src.join("graph.metadata"), b"meta").unwrap();
        std::fs::write(src.join("graph.nodedata"), vec![7u8; 5000]).unwrap();
        let file = root.join("g.graphite");
        pack(PackArgs {
            inputs: vec![src.clone()],
            output: file.clone(),
        })
        .unwrap();
        verify(VerifyArgs {
            input: file.clone(),
            verbose: true,
        })
        .unwrap();
        info(InfoArgs {
            input: file.clone(),
        })
        .unwrap();
        let back = root.join("back");
        unpack(UnpackArgs {
            input: file.clone(),
            output: Some(back.clone()),
        })
        .unwrap();
        assert_eq!(std::fs::read(back.join("graph.metadata")).unwrap(), b"meta");
        assert_eq!(
            std::fs::read(back.join("graph.nodedata")).unwrap().len(),
            5000
        );
        let digest = std::fs::read_to_string(root.join("g.graphite.sha256")).unwrap();
        assert!(digest.ends_with("  g.graphite\n"), "{digest}");

        let mut bytes = std::fs::read(&file).unwrap();
        let at = Container::open(&file)
            .unwrap()
            .entry("graph.nodedata")
            .unwrap()
            .offset as usize;
        bytes[at + 1] = 0;
        std::fs::write(&file, bytes).unwrap();
        let err = verify(VerifyArgs {
            input: file,
            verbose: false,
        })
        .unwrap_err();
        assert!(err.contains("graph.nodedata: CRC-32 mismatch"), "{err}");
        assert!(err.contains("digest file does not match"), "{err}");
        assert!(verify(VerifyArgs {
            input: root.join("missing.graphite"),
            verbose: false
        })
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn pack_takes_files_under_their_names_and_insists_on_a_whole_graph() {
        let root = tempdir("files");
        let src = root.join("g");
        graph_fixture(&src);
        std::fs::write(src.join("graph.nodedata"), b"nodes").unwrap();
        let inputs: Vec<PathBuf> = REQUIRED_ENTRIES.iter().map(|n| src.join(n)).collect();
        let file = root.join("f.graphite");
        pack(PackArgs {
            inputs: inputs.clone(),
            output: file.clone(),
        })
        .unwrap();
        let c = Container::open(&file).unwrap();
        assert_eq!(c.entries().count(), REQUIRED_ENTRIES.len() + 1);
        assert_eq!(&c.bytes("graph.nodedata").unwrap()[..], b"nodes");
        assert!(c.bytes(MANIFEST_NAME).is_some());
        // Files and a directory together, a file twice, and an incomplete set are refused.
        let err = pack(PackArgs {
            inputs: vec![inputs[0].clone(), src.clone()],
            output: root.join("mixed.graphite"),
        })
        .unwrap_err();
        assert!(err.contains("not both"), "{err}");
        let mut twice = inputs.clone();
        twice.push(inputs[0].clone());
        let err = pack(PackArgs {
            inputs: twice,
            output: root.join("dup.graphite"),
        })
        .unwrap_err();
        assert!(err.contains("duplicate entry"), "{err}");
        let err = pack(PackArgs {
            inputs: inputs[1..].to_vec(),
            output: root.join("partial.graphite"),
        })
        .unwrap_err();
        assert!(err.contains("not a graph: missing forward.graph"), "{err}");
        assert!(!root.join("partial.graphite").exists());
        std::fs::remove_dir_all(root).unwrap();
    }
}
