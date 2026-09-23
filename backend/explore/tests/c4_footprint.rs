//! The per-graph footprint of a retained C4 inference, weighed with a counting
//! allocator. Its own binary, so no other test's allocations land in the count.

use graphite_explore::c4::{Inference, LEVELS};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};

/// The system allocator with a running count of live bytes and a high-water mark, so
/// a test can weigh what an inference keeps against what building it took.
struct Counting;
static LIVE: AtomicUsize = AtomicUsize::new(0);
static PEAK: AtomicUsize = AtomicUsize::new(0);
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let live = LIVE.fetch_add(l.size(), Relaxed) + l.size();
        PEAK.fetch_max(live, Relaxed);
        System.alloc(l)
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        LIVE.fetch_sub(l.size(), Relaxed);
        System.dealloc(p, l)
    }
}
#[global_allocator]
static ALLOCATOR: Counting = Counting;

/// Against a real graph (`GRAPHITE_INDEX_FIXTURE`): what a server retains per graph
/// is the inferred layout, one entry per container, component and dependency with each
/// entry's lists capped by the C4 constants, so it is of the order of the workspace it
/// assembles (which the server caches anyway) and never one entry per class or method.
/// Building it walks the whole graph and peaks far higher (28 MiB against 42 KiB
/// retained for a 1.55M-node graph); that working set goes back to the allocator when
/// the build ends.
///
/// An inference is weighed by what dropping it frees, so allocations made alongside it
/// (the graph's lazy columns, an assembly's statics, a thread's buffers) never land in
/// the count, on any platform.
#[test]
fn a_retained_inference_is_a_sliver_of_its_build() {
    let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
        eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
        return;
    };
    let g = graphite_storage::Graph::load(std::path::Path::new(&dir)).unwrap();

    let before = LIVE.load(Relaxed);
    PEAK.store(before, Relaxed);
    let fresh = Inference::of(&g);
    let peak = PEAK.load(Relaxed) - before;
    let owned_fresh = owned_by(fresh);

    let assembled = Inference::of(&g);
    let mut workspace_bytes = 0;
    for level in LEVELS {
        let workspace = assembled.assemble(level);
        if level == "all" {
            workspace_bytes = serde_json::to_vec(&workspace).unwrap().len();
        }
    }
    let owned_assembled = owned_by(assembled);

    eprintln!(
        "inference owns {owned_fresh} bytes against a {workspace_bytes} byte `all` workspace; \
         build peaked at {peak} bytes"
    );
    assert!(
        owned_fresh <= BASE_BYTES + WORKSPACE_MULTIPLE * workspace_bytes,
        "retained {owned_fresh} bytes for a {workspace_bytes} byte workspace"
    );
    assert_eq!(owned_assembled, owned_fresh, "assembly grew the inference");
}

/// What an inference may own beyond what the `all` workspace shows: the subject, the
/// boundary, and the collections' own headers.
const BASE_BYTES: usize = 16 * 1024;
/// How many times the serialized `all` workspace an inference may own. Its entries are
/// the workspace's elements with their lists (package units, primary classes,
/// entrypoints) held as separate strings rather than one rendered property.
const WORKSPACE_MULTIPLE: usize = 4;

/// The bytes freed by dropping `inference`: what it owned, and nothing else.
fn owned_by(inference: Inference) -> usize {
    let held = LIVE.load(Relaxed);
    drop(inference);
    held - LIVE.load(Relaxed)
}
