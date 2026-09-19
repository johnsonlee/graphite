//! `serve --watch`: follow the `--data` directory and swap graphs as their files change.
//!
//! The server does not know where graphs come from. Something else (`graphite sync`, a
//! CI job, a person with `scp`) puts `<id>.graphite` files into the data directory, and
//! the watcher makes the running server match the directory: a new file is loaded, a
//! changed file is reloaded, a removed file is unloaded. Every change goes through the
//! same registry and topology rebuild the HTTP load and unload routes use, so a query in
//! flight keeps its lease on the old graph and the swap is invisible to it.
//!
//! Two rules keep a half-written file out of the server:
//!
//! - A file is loaded only once its signature (size, modification time, and the text of
//!   its `.sha256` sidecar) has been the same on two consecutive scans, so a copy in
//!   progress settles before it is touched. An atomic rename lands in one scan and
//!   waits one more.
//! - A container is verified before it is loaded: every entry against the manifest and,
//!   when a sidecar exists, the whole file against it. A file that fails is skipped and
//!   not retried until it changes again.
//!
//! Ids given explicitly (`--graph`, the positional graph) are never touched: a file that
//! appears under such an id is reported once and ignored, as startup rejects the same
//! collision.

use crate::registry::GraphRegistry;
use crate::routes::AppState;
use crate::serve::discover_graphs;
use graphite_storage::container::{digest_path, Container};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

/// What a scan records about one `.graphite` file. Two files with equal signatures are
/// taken to be the same bytes; the digest sidecar is included so that a publisher
/// writing the sidecar last (as `graphite sync` does) settles the pair together.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Signature {
    pub len: u64,
    pub modified: Option<SystemTime>,
    pub digest: Option<String>,
}

impl Signature {
    pub fn of(path: &Path) -> Result<Signature, String> {
        let meta =
            std::fs::metadata(path).map_err(|e| format!("cannot stat {}: {e}", path.display()))?;
        let digest = match std::fs::read_to_string(digest_path(path)) {
            Ok(text) => Some(text),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
            Err(e) => return Err(format!("cannot read {}: {e}", digest_path(path).display())),
        };
        Ok(Signature {
            len: meta.len(),
            modified: meta.modified().ok(),
            digest,
        })
    }
}

/// Every `*.graphite` file directly under `data`, by id, with its signature.
pub fn scan(data: &Path) -> Result<BTreeMap<String, (PathBuf, Signature)>, String> {
    let mut out = BTreeMap::new();
    for (id, path) in discover_graphs(data)? {
        // A file removed between the listing and the stat is simply absent this scan.
        match Signature::of(&path) {
            Ok(sig) => {
                out.insert(id, (path, sig));
            }
            Err(e) if !path.exists() => {
                let _ = e;
            }
            Err(e) => return Err(e),
        }
    }
    Ok(out)
}

/// Where the watcher applies changes. The server implements it over the registry; tests
/// implement it with a recorder.
pub trait Catalog {
    /// Load `path` under `id`, replacing any graph already served under that id.
    fn load(&self, id: &str, path: &Path) -> Result<(), String>;
    fn unload(&self, id: &str) -> Result<(), String>;
}

/// One thing the watcher did, or decided not to do, on a tick.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    Loaded(String),
    Reloaded(String),
    Unloaded(String),
    /// A file that failed to load or unload; reported once per signature.
    Skipped(String, String),
    /// A file under an id given with `--graph`; reported once.
    Conflict(String),
    /// The directory could not be scanned; nothing was changed.
    ScanFailed(String),
}

impl Event {
    pub fn message(&self, data: &Path) -> String {
        match self {
            Event::Loaded(id) => format!("Loaded graph '{id}' from {}", data.display()),
            Event::Reloaded(id) => format!("Reloaded graph '{id}' from {}", data.display()),
            Event::Unloaded(id) => format!("Unloaded graph '{id}': its file was removed"),
            Event::Skipped(id, why) => format!("Skipped graph '{id}': {why}"),
            Event::Conflict(id) => format!(
                "Ignoring {id}.graphite: the id '{id}' was given with --graph and is not watched"
            ),
            Event::ScanFailed(why) => format!("Cannot scan {}: {why}", data.display()),
        }
    }
}

pub struct Watcher<C: Catalog> {
    data: PathBuf,
    catalog: C,
    /// What is served from the directory, with the signature it was loaded from.
    managed: BTreeMap<String, Signature>,
    /// Ids the watcher must never touch.
    reserved: BTreeSet<String>,
    /// Files seen once whose signature must hold for one more scan.
    pending: BTreeMap<String, Signature>,
    /// Signatures that failed to load, so the same bytes are not retried every tick.
    failed: BTreeMap<String, Signature>,
    warned: BTreeSet<String>,
}

impl<C: Catalog> Watcher<C> {
    /// `managed` are the ids already served from the directory (the graphs discovered at
    /// startup) with their signatures; `reserved` are the ids given explicitly.
    pub fn new(
        data: PathBuf,
        catalog: C,
        managed: BTreeMap<String, Signature>,
        reserved: BTreeSet<String>,
    ) -> Watcher<C> {
        Watcher {
            data,
            catalog,
            managed,
            reserved,
            pending: BTreeMap::new(),
            failed: BTreeMap::new(),
            warned: BTreeSet::new(),
        }
    }

    pub fn managed_ids(&self) -> Vec<String> {
        self.managed.keys().cloned().collect()
    }

    /// One scan of the directory, applying every settled change.
    pub fn tick(&mut self) -> Vec<Event> {
        let current = match scan(&self.data) {
            Ok(c) => c,
            Err(e) => return vec![Event::ScanFailed(e)],
        };
        let mut events = Vec::new();
        for (id, (path, sig)) in &current {
            if self.reserved.contains(id) {
                if self.warned.insert(id.clone()) {
                    events.push(Event::Conflict(id.clone()));
                }
                continue;
            }
            if self.managed.get(id) == Some(sig) || self.failed.get(id) == Some(sig) {
                self.pending.remove(id);
                continue;
            }
            if self.pending.get(id) != Some(sig) {
                // First sighting of these bytes: wait for them to settle.
                self.pending.insert(id.clone(), sig.clone());
                continue;
            }
            self.pending.remove(id);
            let replacing = self.managed.contains_key(id);
            match self.catalog.load(id, path) {
                Ok(()) => {
                    self.failed.remove(id);
                    self.managed.insert(id.clone(), sig.clone());
                    events.push(if replacing {
                        Event::Reloaded(id.clone())
                    } else {
                        Event::Loaded(id.clone())
                    });
                }
                Err(why) => {
                    self.failed.insert(id.clone(), sig.clone());
                    events.push(Event::Skipped(id.clone(), why));
                }
            }
        }
        let gone: Vec<String> = self
            .managed
            .keys()
            .filter(|id| !current.contains_key(*id))
            .cloned()
            .collect();
        for id in gone {
            match self.catalog.unload(&id) {
                Ok(()) => {
                    self.managed.remove(&id);
                    events.push(Event::Unloaded(id));
                }
                Err(why) => events.push(Event::Skipped(id, why)),
            }
        }
        self.pending.retain(|id, _| current.contains_key(id));
        self.failed.retain(|id, _| current.contains_key(id));
        events
    }
}

/// The server's catalog: the registry plus the topology rebuild that follows every
/// change, with the same rollback the HTTP routes perform.
pub struct RegistryCatalog {
    pub state: Arc<AppState>,
}

impl RegistryCatalog {
    fn registry(&self) -> &GraphRegistry {
        &self.state.registry
    }
}

/// Verify a container before serving it. A directory is served as is.
pub fn verify_container(path: &Path) -> Result<(), String> {
    if !path.is_file() {
        return Ok(());
    }
    let container = Container::open(path).map_err(|e| e.to_string())?;
    let report = container.verify().map_err(|e| e.to_string())?;
    // A sidecar that disagrees with the file is one of the failures.
    if let Some(first) = report.failures.first() {
        return Err(format!("verification failed: {first}"));
    }
    Ok(())
}

impl Catalog for RegistryCatalog {
    fn load(&self, id: &str, path: &Path) -> Result<(), String> {
        verify_container(path)?;
        let previous = self.registry().describe(id)?;
        self.registry().load(id, path, None)?;
        if let Err(e) = self.state.rebuild_topology() {
            match previous {
                Some(p) => self.registry().restore(p),
                None => {
                    let _ = self.registry().unload(id);
                }
            }
            return Err(format!("topology rebuild failed: {e}"));
        }
        Ok(())
    }

    fn unload(&self, id: &str) -> Result<(), String> {
        let Some(taken) = self.registry().take(id)? else {
            return Ok(());
        };
        if let Err(e) = self.state.rebuild_topology() {
            self.registry().restore(taken);
            return Err(format!("topology rebuild failed: {e}"));
        }
        Ok(())
    }
}

/// Build the watcher for a server that has just opened `data`: every served id whose
/// file is in the directory is managed, every other served id is reserved.
pub fn for_server(state: Arc<AppState>, data: &Path) -> Result<Watcher<RegistryCatalog>, String> {
    let current = scan(data)?;
    let mut managed = BTreeMap::new();
    let mut reserved = BTreeSet::new();
    for id in state.registry.ids() {
        match current.get(&id) {
            Some((_, sig)) => {
                managed.insert(id, sig.clone());
            }
            None => {
                reserved.insert(id);
            }
        }
    }
    Ok(Watcher::new(
        data.to_path_buf(),
        RegistryCatalog { state },
        managed,
        reserved,
    ))
}

/// Run the watcher on its own thread until the process exits, logging every event.
pub fn spawn(mut watcher: Watcher<RegistryCatalog>, interval: Duration) {
    let data = watcher.data.clone();
    eprintln!(
        "Watching {} every {}s for *.graphite changes",
        data.display(),
        interval.as_secs()
    );
    std::thread::Builder::new()
        .name("graphite-watch".into())
        .spawn(move || loop {
            std::thread::sleep(interval);
            for event in watcher.tick() {
                eprintln!("{}", event.message(&data));
            }
        })
        .expect("spawn watch thread");
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    fn tempdir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("graphite-watch-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Records every call; `fail` makes loads of that id fail.
    #[derive(Default)]
    struct Recorder {
        calls: RefCell<Vec<String>>,
        fail: RefCell<BTreeSet<String>>,
    }

    impl Catalog for Recorder {
        fn load(&self, id: &str, path: &Path) -> Result<(), String> {
            self.calls.borrow_mut().push(format!(
                "load {id} {}",
                path.file_name().unwrap().to_str().unwrap()
            ));
            if self.fail.borrow().contains(id) {
                Err("bad bytes".into())
            } else {
                Ok(())
            }
        }
        fn unload(&self, id: &str) -> Result<(), String> {
            self.calls.borrow_mut().push(format!("unload {id}"));
            Ok(())
        }
    }

    fn write(dir: &Path, name: &str, bytes: &[u8]) {
        std::fs::write(dir.join(name), bytes).unwrap();
    }

    fn watcher(dir: &Path) -> Watcher<Recorder> {
        Watcher::new(
            dir.to_path_buf(),
            Recorder::default(),
            BTreeMap::new(),
            BTreeSet::new(),
        )
    }

    #[test]
    fn a_new_file_is_loaded_once_its_signature_holds_for_two_scans() {
        let dir = tempdir("new");
        let mut w = watcher(&dir);
        assert!(w.tick().is_empty());
        write(&dir, "orders.graphite", b"one");
        assert!(
            w.tick().is_empty(),
            "first sighting only records the signature"
        );
        assert_eq!(w.tick(), [Event::Loaded("orders".into())]);
        assert!(w.tick().is_empty(), "an unchanged file is not loaded again");
        assert_eq!(
            w.catalog.calls.borrow().as_slice(),
            ["load orders orders.graphite"]
        );
        assert_eq!(w.managed_ids(), ["orders"]);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_file_that_keeps_changing_is_not_loaded_until_it_settles() {
        let dir = tempdir("settle");
        let mut w = watcher(&dir);
        write(&dir, "orders.graphite", b"1");
        assert!(w.tick().is_empty());
        write(&dir, "orders.graphite", b"12");
        assert!(
            w.tick().is_empty(),
            "the size changed, so the file is still being written"
        );
        write(&dir, "orders.graphite", b"123");
        assert!(w.tick().is_empty());
        assert_eq!(w.tick(), [Event::Loaded("orders".into())]);
        assert_eq!(w.catalog.calls.borrow().len(), 1);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_changed_file_is_reloaded_and_a_removed_file_unloaded() {
        let dir = tempdir("change");
        write(&dir, "orders.graphite", b"v1");
        let mut managed = BTreeMap::new();
        managed.insert(
            "orders".to_string(),
            Signature::of(&dir.join("orders.graphite")).unwrap(),
        );
        let mut w = Watcher::new(dir.clone(), Recorder::default(), managed, BTreeSet::new());
        assert!(w.tick().is_empty(), "the startup graph matches its file");
        write(&dir, "orders.graphite", b"v2 longer");
        assert!(w.tick().is_empty());
        assert_eq!(w.tick(), [Event::Reloaded("orders".into())]);
        std::fs::remove_file(dir.join("orders.graphite")).unwrap();
        assert_eq!(w.tick(), [Event::Unloaded("orders".into())]);
        assert!(w.managed_ids().is_empty());
        assert_eq!(
            w.catalog.calls.borrow().as_slice(),
            ["load orders orders.graphite", "unload orders"]
        );
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_sidecar_is_part_of_the_signature() {
        let dir = tempdir("sidecar");
        write(&dir, "orders.graphite", b"same bytes");
        let mut w = watcher(&dir);
        w.tick();
        assert_eq!(w.tick(), [Event::Loaded("orders".into())]);
        // Same file, a sidecar written later: a publisher writes the sidecar last, and
        // the pair must settle again before the server trusts it.
        write(&dir, "orders.graphite.sha256", b"abc  orders.graphite\n");
        assert!(w.tick().is_empty());
        assert_eq!(w.tick(), [Event::Reloaded("orders".into())]);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_file_that_fails_to_load_is_reported_once_and_retried_when_it_changes() {
        let dir = tempdir("fail");
        let mut w = watcher(&dir);
        w.catalog.fail.borrow_mut().insert("orders".into());
        write(&dir, "orders.graphite", b"broken");
        w.tick();
        assert_eq!(
            w.tick(),
            [Event::Skipped("orders".into(), "bad bytes".into())]
        );
        assert!(w.tick().is_empty(), "the same bytes are not retried");
        assert!(w.tick().is_empty());
        assert!(w.managed_ids().is_empty());
        w.catalog.fail.borrow_mut().clear();
        write(&dir, "orders.graphite", b"repaired");
        w.tick();
        assert_eq!(w.tick(), [Event::Loaded("orders".into())]);
        assert_eq!(w.catalog.calls.borrow().len(), 2);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_reserved_id_is_reported_once_and_never_loaded() {
        let dir = tempdir("reserved");
        let mut reserved = BTreeSet::new();
        reserved.insert("orders".to_string());
        let mut w = Watcher::new(dir.clone(), Recorder::default(), BTreeMap::new(), reserved);
        write(&dir, "orders.graphite", b"x");
        assert_eq!(w.tick(), [Event::Conflict("orders".into())]);
        assert!(w.tick().is_empty());
        assert!(w.tick().is_empty());
        assert!(w.catalog.calls.borrow().is_empty());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_scan_failure_changes_nothing() {
        let dir = tempdir("scanfail");
        write(&dir, "orders.graphite", b"x");
        let mut w = watcher(&dir);
        w.tick();
        w.tick();
        write(&dir, "bad name.graphite", b"x");
        let events = w.tick();
        assert_eq!(events.len(), 1);
        assert!(matches!(&events[0], Event::ScanFailed(why) if why.contains("bad name")));
        assert_eq!(w.managed_ids(), ["orders"]);
        assert!(w.catalog.calls.borrow().len() == 1);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn events_have_operator_messages() {
        let data = Path::new("/srv/graphs");
        assert_eq!(
            Event::Loaded("a".into()).message(data),
            "Loaded graph 'a' from /srv/graphs"
        );
        assert_eq!(
            Event::Reloaded("a".into()).message(data),
            "Reloaded graph 'a' from /srv/graphs"
        );
        assert_eq!(
            Event::Unloaded("a".into()).message(data),
            "Unloaded graph 'a': its file was removed"
        );
        assert_eq!(
            Event::Skipped("a".into(), "why".into()).message(data),
            "Skipped graph 'a': why"
        );
        assert!(Event::Conflict("a".into())
            .message(data)
            .contains("--graph"));
        assert!(Event::ScanFailed("why".into())
            .message(data)
            .contains("/srv/graphs"));
    }

    #[test]
    fn verification_rejects_a_truncated_container_and_accepts_a_directory() {
        let dir = tempdir("verify");
        assert!(verify_container(&dir).is_ok());
        write(&dir, "orders.graphite", b"not a zip");
        assert!(verify_container(&dir.join("orders.graphite")).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    /// With a real graph (`GRAPHITE_INDEX_FIXTURE`, a persisted graph directory), the
    /// registry catalog loads, reloads and unloads through the server state, and a lease
    /// taken before a swap keeps the old graph.
    #[test]
    fn registry_catalog_swaps_a_real_graph() {
        let Some(fixture) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return;
        };
        let dir = tempdir("real");
        let file = dir.join("acme.graphite");
        graphite_storage::container::pack(Path::new(&fixture), &file).unwrap();
        let registry = Arc::new(GraphRegistry::new(
            dir.clone(),
            crate::registry::LoadMode::Mapped,
        ));
        let guard = Arc::new(crate::guard::CypherGuard::new(1, 1_000));
        let state = Arc::new(AppState::new(registry.clone(), guard, "test".into(), false));
        let mut w = for_server(state.clone(), &dir).unwrap();
        assert!(w.tick().is_empty());
        assert_eq!(w.tick(), [Event::Loaded("acme".into())]);
        let lease = registry.acquire("acme").unwrap().unwrap();
        let first_generation = registry.describe("acme").unwrap().unwrap().generation;

        // Replace the file the way a publisher does: a new name, then a rename over it.
        let staged = dir.join("acme.graphite.new");
        std::fs::copy(&file, &staged).unwrap();
        std::fs::rename(&staged, &file).unwrap();
        // A rename keeps the size and may keep the second; force a visible change.
        std::fs::write(
            digest_path(&file),
            format!("{}  acme.graphite\n", "0".repeat(64)),
        )
        .unwrap();
        let mut events = w.tick();
        if events.is_empty() {
            events = w.tick();
        }
        assert_eq!(
            events,
            [Event::Skipped(
                "acme".into(),
                format!(
                    "verification failed: {}: digest file does not match the file",
                    digest_path(&file).display()
                )
            )]
        );
        let c = Container::open(&file).unwrap();
        std::fs::write(
            digest_path(&file),
            format!("{}  acme.graphite\n", c.file_sha256()),
        )
        .unwrap();
        w.tick();
        assert_eq!(w.tick(), [Event::Reloaded("acme".into())]);
        let second_generation = registry.describe("acme").unwrap().unwrap().generation;
        assert!(second_generation > first_generation);
        let fresh = registry.acquire("acme").unwrap().unwrap();
        assert!(
            !Arc::ptr_eq(&lease.graph, &fresh.graph),
            "the lease taken before the swap keeps the old graph"
        );
        assert_eq!(lease.stats, fresh.stats);

        std::fs::remove_file(&file).unwrap();
        assert_eq!(w.tick(), [Event::Unloaded("acme".into())]);
        assert!(registry.is_empty());
        drop(lease);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
