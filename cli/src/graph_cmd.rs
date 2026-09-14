//! `graphite graph`: the persisted graph as one file.
//!
//! `pack` turns the directory a frontend writes into a `.graphite` container, `unpack`
//! turns it back, `verify` checks a container against its central directory and
//! manifest, and `info` lists what a container holds. The container is a STORED zip;
//! `graphite_storage::container` says what the reader relies on.

use graphite_storage::container::{self, Container, MANIFEST_NAME};
use serde_json::json;
use std::path::{Path, PathBuf};

#[derive(clap::Subcommand, Debug)]
pub enum GraphCommand {
    /// Pack a saved graph directory into one .graphite file
    Pack {
        /// Saved graph directory (what `graphite build -o <dir>` writes)
        dir: PathBuf,
        /// Output file; written completely or not at all
        file: PathBuf,
    },
    /// Unpack a .graphite file into a directory of the original files
    Unpack {
        file: PathBuf,
        /// Target directory, created if absent
        dir: PathBuf,
    },
    /// Check every entry of a .graphite file against its CRC-32 and manifest SHA-256
    Verify {
        file: PathBuf,
        /// Print one line per entry
        #[arg(long, short = 'v')]
        verbose: bool,
    },
    /// Describe a .graphite file as JSON: entries, sizes, fingerprint
    Info { file: PathBuf },
}

pub fn run(command: GraphCommand) -> Result<(), String> {
    match command {
        GraphCommand::Pack { dir, file } => {
            let report = container::pack(&dir, &file).map_err(|e| e.to_string())?;
            println!(
                "Packed {} entries ({} bytes) into {}\nfingerprint: {}",
                report.entries,
                report.bytes,
                file.display(),
                report.fingerprint
            );
            Ok(())
        }
        GraphCommand::Unpack { file, dir } => {
            let c = Container::open(&file).map_err(|e| e.to_string())?;
            let n = c.unpack(&dir).map_err(|e| e.to_string())?;
            println!("Unpacked {n} entries into {}", dir.display());
            Ok(())
        }
        GraphCommand::Verify { file, verbose } => verify(&file, verbose),
        GraphCommand::Info { file } => {
            let c = Container::open(&file).map_err(|e| e.to_string())?;
            let entries: Vec<_> = c
                .entries()
                .map(|e| json!({ "name": e.name, "size": e.size, "offset": e.offset }))
                .collect();
            let text = serde_json::to_string_pretty(&json!({
                "file": file.display().to_string(),
                "format": container::ARCHIVE_COMMENT,
                "fingerprint": c.fingerprint(),
                "entries": entries,
            }))
            .map_err(|e| e.to_string())?;
            println!("{text}");
            Ok(())
        }
    }
}

fn verify(file: &Path, verbose: bool) -> Result<(), String> {
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

    fn tempdir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("graphite-graph-cmd-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn pack_verify_info_and_unpack_round_trip() {
        let root = tempdir("roundtrip");
        let src = root.join("g");
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("graph.metadata"), b"meta").unwrap();
        std::fs::write(src.join("graph.nodedata"), vec![7u8; 5000]).unwrap();
        let file = root.join("g.graphite");
        run(GraphCommand::Pack {
            dir: src.clone(),
            file: file.clone(),
        })
        .unwrap();
        run(GraphCommand::Verify {
            file: file.clone(),
            verbose: true,
        })
        .unwrap();
        run(GraphCommand::Info { file: file.clone() }).unwrap();
        let back = root.join("back");
        run(GraphCommand::Unpack {
            file: file.clone(),
            dir: back.clone(),
        })
        .unwrap();
        assert_eq!(std::fs::read(back.join("graph.metadata")).unwrap(), b"meta");
        assert_eq!(
            std::fs::read(back.join("graph.nodedata")).unwrap().len(),
            5000
        );

        let mut bytes = std::fs::read(&file).unwrap();
        let at = Container::open(&file)
            .unwrap()
            .entry("graph.nodedata")
            .unwrap()
            .offset as usize;
        bytes[at + 1] = 0;
        std::fs::write(&file, bytes).unwrap();
        let err = run(GraphCommand::Verify {
            file,
            verbose: false,
        })
        .unwrap_err();
        assert!(err.contains("graph.nodedata: CRC-32 mismatch"), "{err}");
        assert!(run(GraphCommand::Verify {
            file: root.join("missing.graphite"),
            verbose: false
        })
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
