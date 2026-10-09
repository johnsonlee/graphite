//! Conservative ASCII n-gram union for generic property text, never retained type strings.
const ALPHABET: usize = 63;
const PAIR_BASE: usize = 64;
const TRIPLE_BASE: usize = 4096;
const WORDS: usize = 3971;
// This optional accelerator must not add unbounded work to loading or planning.
const BUILD_BYTES: usize = 16 * 1024 * 1024;
const QUERY_BYTES: usize = 4096;

fn symbol(byte: u8) -> Option<usize> {
    match byte {
        b'A'..=b'Z' => Some(usize::from(byte - b'A')),
        b'a'..=b'z' => Some(26 + usize::from(byte - b'a')),
        b'_' => Some(52),
        b'0'..=b'9' => Some(53 + usize::from(byte - b'0')),
        _ => None,
    }
}

#[derive(Debug)]
pub(super) struct TextSummary {
    bits: Box<[u64]>,
}
impl TextSummary {
    /// Every ASCII run of an exact CONTAINS match must occur. Punctuation and
    /// Unicode never create a negative by themselves, or join unrelated runs.
    pub(super) fn may_contain(&self, literal: &str) -> bool {
        if literal.len() > QUERY_BYTES {
            return true;
        }
        let mut previous = None;
        let mut pair = None;
        for byte in literal.bytes() {
            let Some(current) = symbol(byte) else {
                previous = None;
                pair = None;
                continue;
            };
            let bit = match (previous, pair) {
                (_, Some(pair)) => TRIPLE_BASE + pair * ALPHABET + current,
                (Some(previous), None) => PAIR_BASE + previous * ALPHABET + current,
                _ => current,
            };
            if self.bits[bit / 64] & (1u64 << (bit % 64)) == 0 {
                return false;
            }
            pair = previous.map(|previous| previous * ALPHABET + current);
            previous = Some(current);
        }
        true
    }
}

pub(super) struct Builder {
    summary: TextSummary,
    previous: Option<usize>,
    pair: Option<usize>,
    inspected: usize,
}
impl Builder {
    pub(super) fn new() -> Self {
        let mut builder = Self {
            summary: TextSummary {
                bits: vec![0; WORDS].into_boxed_slice(),
            },
            previous: None,
            pair: None,
            inspected: 0,
        };
        for key in [
            "kind",
            "name",
            "scope",
            "owner",
            "component",
            "variance",
            "arguments",
        ] {
            builder.begin_text();
            let _ = builder.add_bytes(key.bytes());
        }
        builder
    }
    pub(super) fn begin_text(&mut self) {
        self.previous = None;
        self.pair = None;
    }
    pub(super) fn add_bytes(&mut self, bytes: impl IntoIterator<Item = u8>) -> bool {
        for byte in bytes {
            if self.inspected == BUILD_BYTES {
                return false;
            }
            self.inspected += 1;
            let Some(current) = symbol(byte) else {
                self.begin_text();
                continue;
            };
            self.set(current);
            if let Some(previous) = self.previous {
                self.set(PAIR_BASE + previous * ALPHABET + current);
            }
            if let Some(pair) = self.pair {
                self.set(TRIPLE_BASE + pair * ALPHABET + current);
            }
            self.pair = self.previous.map(|previous| previous * ALPHABET + current);
            self.previous = Some(current);
        }
        true
    }
    fn set(&mut self, bit: usize) {
        self.summary.bits[bit / 64] |= 1u64 << (bit % 64);
    }
    pub(super) fn finish(self) -> TextSummary {
        self.summary
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_substring_preserves_generated_punctuation_and_ascii_runs() {
        let atoms = [
            "java.util.Map",
            "Outer$Inner",
            "variable",
            "extends",
            "T",
            "method:Owner#echo([[Lfoo/Bar;II)I",
            "类型_Inner",
        ];
        let mut builder = Builder::new();
        for atom in atoms {
            builder.begin_text();
            assert!(builder.add_bytes(atom.bytes()));
        }
        let summary = builder.finish();
        let outputs = [
            "java.util.Map<T, ? extends Outer.Inner>[]",
            "{kind=variable, name=T, scope=method:Owner#echo([[Lfoo/Bar;II)I, arguments=[]}",
            "类型_Inner",
        ];
        for output in outputs {
            let boundaries: Vec<_> = output
                .char_indices()
                .map(|(i, _)| i)
                .chain([output.len()])
                .collect();
            for &a in &boundaries {
                for &b in &boundaries {
                    if a <= b {
                        assert!(summary.may_contain(&output[a..b]), "{:?}", &output[a..b]);
                    }
                }
            }
        }
        assert!(!summary.may_contain("android.permission.ZZZMissing"));
        assert!(summary.may_contain("<>, []={}类型"));
        assert!(summary.may_contain(""));
    }

    #[test]
    fn streaming_chunks_keep_descriptor_joins_but_separate_atoms_do_not_join() {
        let mut builder = Builder::new();
        builder.begin_text();
        for chunk in ["method:", "Owner", "#echo(", "I", "I", "L", "foobar", ";)V"] {
            assert!(builder.add_bytes(chunk.bytes()));
        }
        builder.begin_text();
        assert!(builder.add_bytes(b"QQ".iter().copied()));
        builder.begin_text();
        assert!(builder.add_bytes(b"ZZ".iter().copied()));
        let summary = builder.finish();
        assert!(summary.may_contain("IILfoo"));
        assert!(!summary.may_contain("QQZZ"));
    }

    #[test]
    fn optional_accelerator_work_is_bounded_and_large_queries_are_maybe() {
        let mut builder = Builder::new();
        builder.inspected = BUILD_BYTES - 1;
        assert!(!builder.add_bytes(b"AB".iter().copied()));
        assert!(Builder::new()
            .finish()
            .may_contain(&"Z".repeat(QUERY_BYTES + 1)));
    }
}
