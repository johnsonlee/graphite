//! Where the first declared-type access of a graph spends its time, per graph and in total.
//!
//! Usage: declared_cost <graphs.tsv> [threads]
//! Reads the second column of the manifest as graph directories, loads each graph, then times
//! the pieces of the first declared access: opening the source, hashing `graph.strings`
//! (the shared-dictionary binding), hashing `graph.metadata` (the table's binding), parsing
//! `graph.types`, and the whole `declared_types()` call on a fresh graph (whose remainder over
//! the pieces is the key partition summary). With `threads` > 1 the fresh-graph calls run
//! concurrently, as the server's first requests do.
use graphite_storage::graph::Graph;
use graphite_storage::source::GraphSource;
use graphite_storage::types::DeclaredTypes;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::time::Instant;

fn ms(start: Instant) -> f64 {
    start.elapsed().as_secs_f64() * 1e3
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let manifest = std::fs::read_to_string(&args[1]).expect("manifest");
    let threads: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(1);
    let dirs: Vec<PathBuf> = manifest
        .lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .filter_map(|l| l.split('\t').nth(1).map(PathBuf::from))
        .filter(|p| p.is_dir())
        .collect();
    let mut totals = [0f64; 6];
    println!("graph\tload\topen\thash_strings\thash_metadata\tparse\tdeclared_total");
    for dir in &dirs {
        let t = Instant::now();
        let graph = Graph::load(dir).expect("load");
        let load = ms(t);
        let t = Instant::now();
        let src = GraphSource::open(dir).expect("open");
        let open = ms(t);
        let strings = src.require("graph.strings").expect("strings");
        let t = Instant::now();
        let _ = Sha256::digest(&strings[..]);
        let hash_strings = ms(t);
        let metadata = src.require("graph.metadata").expect("metadata");
        let t = Instant::now();
        let _ = Sha256::digest(&metadata[..]);
        let hash_metadata = ms(t);
        graph.strings().bind_serialized(&strings[..]);
        let t = Instant::now();
        let table = DeclaredTypes::load_with_strings(&src, graph.strings()).expect("table");
        let parse = ms(t);
        drop(table);
        let fresh = Graph::load(dir).expect("load");
        let t = Instant::now();
        let _ = fresh.declared_types();
        let declared_total = ms(t);
        if fresh.has_persisted_declared_keys() {
            let summary: Vec<_> = [9u8, 10, 11]
                .iter()
                .map(|&tag| {
                    Graph::load(dir)
                        .expect("load")
                        .declared_key_partitions(tag)
                        .copied()
                })
                .collect();
            let computed = fresh.recomputed_declared_key_partitions();
            let agree = summary.iter().zip(computed.iter()).all(|(a, b)| a == b);
            println!(
                "  persisted summary {}: {:?}",
                if agree { "AGREES" } else { "DIFFERS" },
                computed
            );
            assert!(
                agree,
                "persisted key summary differs from the reader's computation"
            );
        } else {
            println!("  no persisted summary");
        }
        let row = [
            load,
            open,
            hash_strings,
            hash_metadata,
            parse,
            declared_total,
        ];
        for (sum, v) in totals.iter_mut().zip(row) {
            *sum += v;
        }
        println!(
            "{}\t{:.1}\t{:.1}\t{:.1}\t{:.1}\t{:.1}\t{:.1}",
            dir.file_name().unwrap().to_string_lossy(),
            load,
            open,
            hash_strings,
            hash_metadata,
            parse,
            declared_total
        );
    }
    println!(
        "TOTAL({} graphs)\t{:.0}\t{:.0}\t{:.0}\t{:.0}\t{:.0}\t{:.0}",
        dirs.len(),
        totals[0],
        totals[1],
        totals[2],
        totals[3],
        totals[4],
        totals[5]
    );
    if threads > 1 {
        let graphs: Vec<Graph> = dirs.iter().map(|d| Graph::load(d).expect("load")).collect();
        let t = Instant::now();
        std::thread::scope(|scope| {
            for chunk in graphs.chunks(graphs.len().div_ceil(threads)) {
                scope.spawn(move || {
                    for g in chunk {
                        let _ = g.declared_types();
                    }
                });
            }
        });
        println!(
            "CONCURRENT first access, {} threads: {:.0} ms wall",
            threads,
            ms(t)
        );
    }
    let _ = Path::new("");
}
