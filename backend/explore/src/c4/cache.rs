//! The server's C4 cache: one inference per loaded graph, built once and shared.
//!
//! Inference walks the whole graph and a loaded graph never changes, so the result is
//! kept for as long as the graph is served. Entries are keyed by the registry
//! generation, which a reload or replace renews, so a stale result can never be served
//! for a graph that was swapped out under the same id.
//!
//! The build is single-flight, and the builder is elected up front: `claim` hands
//! exactly one caller a `Ticket` to build with, and every other caller a `Waiter` on
//! that build. That lets the caller decide what only the builder must do (take a
//! build permit, say) before the expensive step starts, and keeps a client which timed
//! out and retried, or a fleet-wide request that fans out over every graph, from
//! multiplying it. Assembling a level's workspace from the inference is single-flight
//! the same way.
//!
//! A build that panics resets its slot: waiters learn that nothing was built and claim
//! again, so the retry goes through the same election, one builder at a time, and
//! nothing is ever evicted or duplicated on that path.

use parking_lot::{Condvar, Mutex};
use serde_json::Value as J;
use std::collections::HashMap;
use std::sync::{Arc, OnceLock};

enum State<T> {
    /// Nobody is building; the next claim builds.
    Empty,
    /// One claimant holds the ticket but has not started (it may be waiting for a
    /// build permit); the others wait.
    Claimed,
    /// The ticket holder is building; the others wait.
    Building,
    Ready(Arc<T>),
}

struct Entry<T> {
    state: Mutex<State<T>>,
    /// Wakes synchronous waiters (`Waiter::wait`) on every state change.
    changed: Condvar,
    /// Wakes asynchronous waiters (`Waiter::wait_async`) on every state change.
    notify: tokio::sync::Notify,
    /// Workspaces assembled from the inference, one single-flight slot per level.
    levels: Mutex<HashMap<String, Arc<OnceLock<Arc<J>>>>>,
}

impl<T> Entry<T> {
    fn set(&self, state: State<T>) {
        *self.state.lock() = state;
        self.changed.notify_all();
        self.notify.notify_waiters();
    }
}

/// What a claim on a generation's inference came back with.
pub enum Claim<T> {
    Ready(Arc<T>),
    /// This caller builds; nobody else will until the ticket is used or dropped.
    Build(Ticket<T>),
    /// Another caller is building; wait for it.
    Wait(Waiter<T>),
}

/// The right to build a generation's inference. Dropped unused (the build panicked,
/// or the holder went away), it hands the slot back so the next claim builds.
pub struct Ticket<T> {
    entry: Arc<Entry<T>>,
    built: bool,
}

impl<T> Ticket<T> {
    /// Run the build and publish its result to every waiter.
    pub fn build(mut self, build: impl FnOnce() -> T) -> Arc<T> {
        self.entry.set(State::Building);
        let built = Arc::new(build());
        self.built = true;
        self.entry.set(State::Ready(built.clone()));
        built
    }
}

impl<T> Drop for Ticket<T> {
    fn drop(&mut self) {
        if !self.built {
            self.entry.set(State::Empty);
        }
    }
}

/// A wait on the build another claimant is running.
pub struct Waiter<T> {
    entry: Arc<Entry<T>>,
}

impl<T> Waiter<T> {
    /// Block until the build lands (`Some`) or fails (`None`, in which case the slot
    /// is free again and the caller should claim it anew).
    pub fn wait(self) -> Option<Arc<T>> {
        let mut state = self.entry.state.lock();
        loop {
            match &*state {
                State::Ready(v) => return Some(v.clone()),
                State::Empty => return None,
                State::Claimed | State::Building => self.entry.changed.wait(&mut state),
            }
        }
    }

    /// `wait`, without a thread: the task parks on the entry's notifier, so a
    /// request waiting for another's build holds no blocking-pool worker (which the
    /// builder itself needs to finish).
    pub async fn wait_async(self) -> Option<Arc<T>> {
        loop {
            let notified = self.entry.notify.notified();
            tokio::pin!(notified);
            {
                let state = self.entry.state.lock();
                match &*state {
                    State::Ready(v) => return Some(v.clone()),
                    State::Empty => return None,
                    State::Claimed | State::Building => {
                        // Registered before the lock is released, so a change that
                        // lands in between is not missed.
                        notified.as_mut().enable();
                    }
                }
            }
            notified.await;
        }
    }
}

/// Cached C4 state per graph generation. `T` is the inference type
/// (`super::Inference` in the server; tests use a stand-in).
pub struct C4Cache<T = super::Inference> {
    entries: Mutex<HashMap<u64, Arc<Entry<T>>>>,
}

impl<T> Default for C4Cache<T> {
    fn default() -> Self {
        C4Cache {
            entries: Mutex::new(HashMap::new()),
        }
    }
}

impl<T> C4Cache<T> {
    fn entry(&self, generation: u64) -> Arc<Entry<T>> {
        self.entries
            .lock()
            .entry(generation)
            .or_insert_with(|| {
                Arc::new(Entry {
                    state: Mutex::new(State::Empty),
                    changed: Condvar::new(),
                    notify: tokio::sync::Notify::new(),
                    levels: Mutex::new(HashMap::new()),
                })
            })
            .clone()
    }

    /// Claim `generation`'s inference: the result if built, the ticket if this caller
    /// is the one to build it, or a waiter on the build in flight.
    pub fn claim(&self, generation: u64) -> Claim<T> {
        let entry = self.entry(generation);
        let mut state = entry.state.lock();
        match &*state {
            State::Ready(v) => Claim::Ready(v.clone()),
            State::Claimed | State::Building => {
                drop(state);
                Claim::Wait(Waiter { entry })
            }
            State::Empty => {
                *state = State::Claimed;
                drop(state);
                Claim::Build(Ticket {
                    entry,
                    built: false,
                })
            }
        }
    }

    /// The inference for `generation`, built by `build` on first use. Concurrent
    /// callers for the same generation wait for the one build in flight and share its
    /// result; if that build fails, the next of them builds.
    pub fn inference(&self, generation: u64, mut build: impl FnMut() -> T) -> Arc<T> {
        loop {
            match self.claim(generation) {
                Claim::Ready(v) => return v,
                Claim::Build(ticket) => return ticket.build(&mut build),
                Claim::Wait(waiter) => {
                    if let Some(v) = waiter.wait() {
                        return v;
                    }
                }
            }
        }
    }

    /// The workspace at `level`, assembled from `inference` (the caller's claimed
    /// result for `generation`) once per generation and level. Concurrent callers for
    /// the same level wait for the one assembly in flight, as they do for the
    /// inference. A generation the cache no longer holds (pruned while the caller
    /// waited) is assembled but not cached: the caller keeps its snapshot, and
    /// nothing is ever built or recreated here.
    pub fn model(
        &self,
        generation: u64,
        level: &str,
        inference: &Arc<T>,
        assemble: impl FnOnce(&T) -> J,
    ) -> Arc<J> {
        let Some(entry) = self.entries.lock().get(&generation).cloned() else {
            return Arc::new(assemble(inference));
        };
        let slot = entry
            .levels
            .lock()
            .entry(level.to_string())
            .or_default()
            .clone();
        slot.get_or_init(|| Arc::new(assemble(inference))).clone()
    }

    /// Whether `generation` has a built inference.
    pub fn is_warm(&self, generation: u64) -> bool {
        self.entries
            .lock()
            .get(&generation)
            .is_some_and(|e| matches!(&*e.state.lock(), State::Ready(_)))
    }

    /// Whether a build for `generation` is running: started, not yet finished. A
    /// ticket claimed but not yet building (its holder waits for a permit) does not
    /// count.
    pub fn is_building(&self, generation: u64) -> bool {
        self.entries
            .lock()
            .get(&generation)
            .is_some_and(|e| matches!(&*e.state.lock(), State::Building))
    }

    /// Keep only the generations in `live`, dropping the rest.
    pub fn retain(&self, live: &[u64]) {
        self.entries.lock().retain(|g, _| live.contains(g));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Barrier;
    use std::time::Duration;

    /// One claim builds, the concurrent ones wait, and all of them get the result.
    #[test]
    fn claims_elect_one_builder_and_the_rest_wait() {
        let cache: C4Cache<u8> = C4Cache::default();
        let Claim::Build(ticket) = cache.claim(1) else {
            panic!("the first claim builds");
        };
        // Claimed, not yet building: the holder may still be waiting for a permit.
        assert!(!cache.is_building(1));
        assert!(!cache.is_warm(1));
        assert!(matches!(cache.claim(1), Claim::Wait(_)));
        assert!(matches!(cache.claim(1), Claim::Wait(_)));
        let waiter = match cache.claim(1) {
            Claim::Wait(w) => w,
            _ => panic!("still building"),
        };
        assert_eq!(*ticket.build(|| 5), 5);
        assert_eq!(waiter.wait().as_deref(), Some(&5));
        assert!(matches!(cache.claim(1), Claim::Ready(v) if *v == 5));
        assert!(cache.is_warm(1));
        assert!(!cache.is_building(1));
    }

    /// An asynchronous waiter parks without a thread and wakes on the build's landing,
    /// or on its failure.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn an_async_waiter_wakes_when_the_build_lands_or_fails() {
        let cache: Arc<C4Cache<u8>> = Arc::new(C4Cache::default());
        let ticket = match cache.claim(6) {
            Claim::Build(t) => t,
            _ => panic!("first claim builds"),
        };
        let waiting = tokio::spawn({
            let cache = cache.clone();
            async move {
                let Claim::Wait(w) = cache.claim(6) else {
                    panic!("claimed");
                };
                w.wait_async().await
            }
        });
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(!waiting.is_finished());
        std::thread::spawn(move || ticket.build(|| 8))
            .join()
            .unwrap();
        assert_eq!(waiting.await.unwrap().as_deref(), Some(&8));
        // And a failed build wakes it with `None`.
        let ticket = match cache.claim(7) {
            Claim::Build(t) => t,
            _ => panic!("first claim builds"),
        };
        let waiting = tokio::spawn({
            let cache = cache.clone();
            async move {
                let Claim::Wait(w) = cache.claim(7) else {
                    panic!("claimed");
                };
                w.wait_async().await
            }
        });
        tokio::time::sleep(Duration::from_millis(20)).await;
        drop(ticket);
        assert!(waiting.await.unwrap().is_none());
    }

    /// A ticket dropped unused frees the slot, and a waiter on it learns that nothing
    /// was built rather than blocking forever.
    #[test]
    fn a_dropped_ticket_frees_the_slot_for_the_next_claim() {
        let cache: Arc<C4Cache<u8>> = Arc::new(C4Cache::default());
        let ticket = match cache.claim(4) {
            Claim::Build(t) => t,
            _ => panic!("first claim builds"),
        };
        let waiter = match cache.claim(4) {
            Claim::Wait(w) => w,
            _ => panic!("second claim waits"),
        };
        let waiting = std::thread::spawn(move || waiter.wait());
        std::thread::sleep(Duration::from_millis(20));
        drop(ticket);
        assert!(waiting.join().unwrap().is_none());
        assert!(!cache.is_building(4));
        assert!(matches!(cache.claim(4), Claim::Build(_)));
    }

    #[test]
    fn concurrent_requests_for_one_generation_build_once() {
        let cache: Arc<C4Cache<usize>> = Arc::new(C4Cache::default());
        let builds = Arc::new(AtomicUsize::new(0));
        let handles: Vec<_> = (0..8)
            .map(|_| {
                let (cache, builds) = (cache.clone(), builds.clone());
                std::thread::spawn(move || {
                    let inference = cache.inference(7, || {
                        builds.fetch_add(1, Ordering::SeqCst);
                        std::thread::sleep(Duration::from_millis(50));
                        42
                    });
                    cache.model(7, "all", &inference, |v| json!({ "value": v }))
                })
            })
            .collect();
        for h in handles {
            assert_eq!(*h.join().unwrap(), json!({ "value": 42 }));
        }
        assert_eq!(builds.load(Ordering::SeqCst), 1);
        assert!(cache.is_warm(7));
        assert!(!cache.is_warm(8));
        assert!(!cache.is_building(7));
    }

    /// Eight callers missing the same level of a warm inference at once: one
    /// assembles, the others wait for its result.
    #[test]
    fn concurrent_misses_for_one_level_assemble_once() {
        let cache: Arc<C4Cache<&'static str>> = Arc::new(C4Cache::default());
        let inference = cache.inference(3, || "warm");
        let assemblies = Arc::new(AtomicUsize::new(0));
        // Every caller is inside `model` before any assembly can finish.
        let gate = Arc::new(Barrier::new(8));
        let handles: Vec<_> = (0..8)
            .map(|_| {
                let (cache, assemblies, gate, inference) = (
                    cache.clone(),
                    assemblies.clone(),
                    gate.clone(),
                    inference.clone(),
                );
                std::thread::spawn(move || {
                    gate.wait();
                    cache.model(3, "container", &inference, |v| {
                        assemblies.fetch_add(1, Ordering::SeqCst);
                        std::thread::sleep(Duration::from_millis(50));
                        json!({ "from": *v })
                    })
                })
            })
            .collect();
        let results: Vec<Arc<J>> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        assert_eq!(assemblies.load(Ordering::SeqCst), 1);
        assert!(results.iter().all(|r| Arc::ptr_eq(r, &results[0])));
        assert_eq!(*results[0], json!({ "from": "warm" }));
    }

    #[test]
    fn levels_share_the_inference_and_assemble_once_each() {
        let cache: C4Cache<&'static str> = C4Cache::default();
        let assemblies = AtomicUsize::new(0);
        let assemble = |level: &'static str| {
            let assemblies = &assemblies;
            move |v: &&'static str| {
                assemblies.fetch_add(1, Ordering::SeqCst);
                json!({ "level": level, "from": *v })
            }
        };
        let inference = cache.inference(1, || "inferred");
        let first = cache.model(1, "context", &inference, assemble("context"));
        let again = cache.model(1, "context", &inference, assemble("context"));
        let other = cache.model(1, "container", &inference, assemble("container"));
        assert!(Arc::ptr_eq(&first, &again));
        assert_eq!(*other, json!({ "level": "container", "from": "inferred" }));
        assert_eq!(assemblies.load(Ordering::SeqCst), 2);
    }

    /// A generation pruned while its caller still holds the inference is assembled
    /// for that caller but neither cached nor recreated.
    #[test]
    fn a_pruned_generation_is_assembled_for_its_holder_but_not_kept() {
        let cache: C4Cache<&'static str> = C4Cache::default();
        let assemblies = AtomicUsize::new(0);
        let inference = cache.inference(5, || "retired");
        cache.retain(&[]);
        for _ in 0..2 {
            let model = cache.model(5, "all", &inference, |v| {
                assemblies.fetch_add(1, Ordering::SeqCst);
                json!(*v)
            });
            assert_eq!(*model, json!("retired"));
        }
        // Assembled each time: nothing was cached for the retired generation.
        assert_eq!(assemblies.load(Ordering::SeqCst), 2);
        assert!(!cache.is_warm(5));
        assert!(!cache.is_building(5));
    }

    #[test]
    fn a_build_in_flight_is_visible_until_it_lands() {
        let cache: Arc<C4Cache<u8>> = Arc::new(C4Cache::default());
        let (tx, rx) = std::sync::mpsc::channel::<()>();
        let builder = {
            let cache = cache.clone();
            std::thread::spawn(move || {
                cache.inference(3, || {
                    rx.recv().unwrap();
                    9
                })
            })
        };
        while !cache.is_building(3) {
            std::thread::yield_now();
        }
        assert!(!cache.is_warm(3));
        tx.send(()).unwrap();
        assert_eq!(*builder.join().unwrap(), 9);
        assert!(!cache.is_building(3));
        assert!(cache.is_warm(3));
    }

    /// A build that panics with a waiter blocked on it: the waiter claims again and
    /// builds, nothing runs alongside it, and a request arriving meanwhile joins that
    /// build rather than starting one of its own.
    #[test]
    fn a_panicking_build_hands_its_slot_to_the_next_caller() {
        let cache: Arc<C4Cache<u8>> = Arc::new(C4Cache::default());
        let starts = Arc::new(AtomicUsize::new(0));
        let active = Arc::new(AtomicUsize::new(0));
        let max_active = Arc::new(AtomicUsize::new(0));
        let (release_first, first_may_panic) = std::sync::mpsc::channel::<()>();
        let build = |cache: &Arc<C4Cache<u8>>,
                     starts: &Arc<AtomicUsize>,
                     active: &Arc<AtomicUsize>,
                     max_active: &Arc<AtomicUsize>,
                     panics: Option<std::sync::mpsc::Receiver<()>>| {
            let (cache, starts, active, max_active) = (
                cache.clone(),
                starts.clone(),
                active.clone(),
                max_active.clone(),
            );
            std::thread::spawn(move || {
                cache.inference(9, || {
                    let n = starts.fetch_add(1, Ordering::SeqCst) + 1;
                    let now = active.fetch_add(1, Ordering::SeqCst) + 1;
                    max_active.fetch_max(now, Ordering::SeqCst);
                    // The first build waits to be told to fail.
                    if let Some(rx) = panics.as_ref() {
                        rx.recv().unwrap();
                        active.fetch_sub(1, Ordering::SeqCst);
                        panic!("build {n} failed");
                    }
                    std::thread::sleep(Duration::from_millis(30));
                    active.fetch_sub(1, Ordering::SeqCst);
                    n as u8
                })
            })
        };
        let first = build(&cache, &starts, &active, &max_active, Some(first_may_panic));
        while !cache.is_building(9) {
            std::thread::yield_now();
        }
        // A waiter blocks on the build in flight.
        let waiter = build(&cache, &starts, &active, &max_active, None);
        std::thread::sleep(Duration::from_millis(30));
        assert_eq!(starts.load(Ordering::SeqCst), 1);
        release_first.send(()).unwrap();
        assert!(first.join().is_err(), "the first build panics");
        // A request arriving now joins whichever build the waiter started.
        let late = build(&cache, &starts, &active, &max_active, None);
        let results = [waiter.join().unwrap(), late.join().unwrap()];
        assert!(Arc::ptr_eq(&results[0], &results[1]));
        assert_eq!(
            starts.load(Ordering::SeqCst),
            2,
            "one retry, no third build"
        );
        assert_eq!(
            max_active.load(Ordering::SeqCst),
            1,
            "never two builds at once"
        );
        assert!(cache.is_warm(9));
        assert!(!cache.is_building(9));
        assert_eq!(*results[0], 2);
    }

    #[test]
    fn generations_are_retained_by_the_catalog() {
        let cache: C4Cache<u8> = C4Cache::default();
        for g in [1, 2, 3] {
            cache.inference(g, || g as u8);
        }
        cache.retain(&[2, 3]);
        assert!(!cache.is_warm(1));
        assert!(cache.is_warm(2));
        assert!(cache.is_warm(3));
        // A dropped generation is rebuilt on its next request.
        assert_eq!(*cache.inference(1, || 10), 10);
    }
}
