//! `graph.completeness`: what the build did with the code it was given.
//!
//! A graph is a projection of bytecode, and an absent edge means two different things:
//! the body was read and has no such call, or the body was never read (outside the
//! scope, never loaded, failed to resolve, cut short). The JVM frontend records which
//! (see `docs/analysis-completeness.md`); this module reads the record so the query
//! path can say "not recorded" instead of answering an absence it cannot know.
//!
//! Layout, as `AnalysisCompletenessStore` writes it, big-endian: `int32 MAGIC|VERSION`,
//! the 32-byte node layout digest, a gzip payload, then the SHA-256 of every byte
//! before it. `forward.properties` binds both digests; a file another save wrote reads
//! as absent, a bound file that does not hash or parse is an error.

use crate::source::GraphSource;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Read;

pub const FILE_NAME: &str = "graph.completeness";
const MAGIC_VERSION: u32 = 0x4743_5000 | 1;
const DIGEST: usize = 32;
const HEADER: usize = 4 + DIGEST;
const LAYOUT_KEY: &str = "graphite.nodeLayout.sha256";
const BINDING_KEY: &str = "graphite.completeness.sha256";

/// The statuses the JVM frontend records. The file names them, so the codes it uses
/// are read by name and a status this reader does not know is an error.
pub const STATUSES: [&str; 10] = [
    "ANALYZED",
    "ANALYZED_WITHOUT_CONTROL_FLOW",
    "ABSTRACT",
    "NATIVE",
    "NO_BODY",
    "INCOMPLETE",
    "RESOLUTION_FAILED",
    "EXCLUDED_BY_SCOPE",
    "NOT_EXPANDED",
    "EXTERNAL",
];
/// The artifact roles and dispatch gaps the JVM frontend names, in its order.
pub const ROLES: [&str; 3] = ["APPLICATION", "LIBRARY", "PLATFORM"];
pub const GAPS: [&str; 3] = [
    "DISPATCH_UNRESOLVED",
    "DISPATCH_SATURATED",
    "DISPATCH_PARTIAL",
];
pub const UNKNOWN_MEMBER: &str = "UNKNOWN_MEMBER";
pub const ANALYZED: &str = "ANALYZED";
pub const EXTERNAL: &str = "EXTERNAL";

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Completeness {
    /// Class name → (origin artifact, status).
    pub classes: HashMap<String, (Option<String>, &'static str)>,
    /// Method identity (`class.name` + JVM descriptor) → (status, message), for every
    /// method whose status is not `ANALYZED`.
    pub methods: HashMap<String, (&'static str, Option<String>)>,
    /// Call-site node id → why its dispatch on a function value is incomplete.
    pub call_site_gaps: HashMap<u32, String>,
    /// Artifact name → role.
    pub artifacts: Vec<(String, String)>,
}

impl Completeness {
    /// The status of the class: `EXTERNAL` when the build never loaded it.
    pub fn class_status(&self, class: &str) -> &'static str {
        self.classes.get(class).map(|(_, s)| *s).unwrap_or(EXTERNAL)
    }

    /// The status of the method `identity` of `class`, given whether the graph holds the
    /// method: `Graph.analysisStatus` of the JVM, case for case.
    pub fn status_of(&self, class: &str, identity: &str, in_graph: bool) -> &'static str {
        if let Some((status, _)) = self.methods.get(identity) {
            return status;
        }
        if in_graph {
            return ANALYZED;
        }
        match self.class_status(class) {
            ANALYZED => UNKNOWN_MEMBER,
            other => other,
        }
    }
}

/// The evidence of one loaded graph, mapped at load and parsed on first use.
///
/// Mapping the file pins the bytes the load saw: a directory replaced afterwards (a new
/// save of the same path) keeps serving this graph its own evidence, never the new one's.
pub struct Bound {
    bytes: crate::container::Bytes,
    layout: String,
    binding: String,
}

impl Bound {
    pub fn parse(&self) -> Result<Option<Completeness>, String> {
        parse(&self.bytes, &self.layout, &self.binding)
    }
}

/// Maps the evidence `forward.properties` binds, without parsing it; `Ok(None)` when the
/// graph has none (written before the evidence existed, or by a frontend that records none).
pub fn bind(src: &GraphSource) -> Result<Option<Bound>, String> {
    let properties = match src.bytes("forward.properties") {
        Ok(Some(bytes)) => String::from_utf8_lossy(&bytes).into_owned(),
        Ok(None) => return Ok(None),
        Err((path, e)) => return Err(format!("{path}: {e}")),
    };
    let property = |key: &str| {
        properties
            .lines()
            .find_map(|line| line.strip_prefix(key)?.strip_prefix('='))
            .map(|v| v.trim().to_ascii_lowercase())
    };
    let (Some(layout), Some(binding)) = (property(LAYOUT_KEY), property(BINDING_KEY)) else {
        return Ok(None);
    };
    match src.bytes(FILE_NAME) {
        Ok(Some(bytes)) => Ok(Some(Bound {
            bytes,
            layout,
            binding,
        })),
        Ok(None) => Ok(None),
        Err((path, e)) => Err(format!("{path}: {e}")),
    }
}

/// Reads the evidence `forward.properties` binds; `Ok(None)` when the graph has none
/// (written before the evidence existed, by a frontend that records none, or the file
/// is another save's).
pub fn load(src: &GraphSource) -> Result<Option<Completeness>, String> {
    match bind(src)? {
        Some(bound) => bound.parse(),
        None => Ok(None),
    }
}

/// What a frontend other than the JVM's recorded, for [`encode`]: the importer of the JSON
/// interchange writes the JVM's own sidecar, so one reader answers for every frontend.
#[derive(Debug, Default)]
pub struct Evidence {
    /// (class name, origin, status), a status of [`STATUSES`].
    pub classes: Vec<(String, Option<String>, String)>,
    /// (method identity, status, message), for methods whose status is not `ANALYZED`.
    pub methods: Vec<(String, String, Option<String>)>,
    /// (call-site node id, gap), a gap of [`GAPS`].
    pub call_site_gaps: Vec<(u32, String)>,
}

/// The bytes of a `graph.completeness` holding `evidence`, bound to the node layout digest
/// `layout`, as `AnalysisCompletenessStore.save` writes them. The last 32 bytes are the binding
/// `forward.properties` records. Entries are written in name and id order, so the same evidence
/// is the same file. An unknown status or gap, or a name `writeUTF` cannot hold, is an error.
pub fn encode(evidence: &Evidence, layout: [u8; DIGEST]) -> Result<Vec<u8>, String> {
    fn utf(out: &mut Vec<u8>, s: &str) -> Result<(), String> {
        let bytes = crate::javaser::encode_java_utf(s).ok_or_else(|| {
            let prefix: String = s.chars().take(60).collect();
            format!("{FILE_NAME} cannot hold a name longer than 65535 bytes: {prefix}...")
        })?;
        out.extend(bytes);
        Ok(())
    }
    fn optional(out: &mut Vec<u8>, s: Option<&str>) -> Result<(), String> {
        out.push(u8::from(s.is_some()));
        s.map_or(Ok(()), |s| utf(out, s))
    }
    fn strings(out: &mut Vec<u8>, values: &[&str]) -> Result<(), String> {
        count(out, values.len())?;
        values.iter().try_for_each(|v| utf(out, v))
    }
    fn count(out: &mut Vec<u8>, n: usize) -> Result<(), String> {
        let n = i32::try_from(n).map_err(|_| format!("{FILE_NAME} holds too many entries"))?;
        out.extend(n.to_be_bytes());
        Ok(())
    }
    fn code(names: &[&str], name: &str, what: &str) -> Result<u8, String> {
        names
            .iter()
            .position(|known| *known == name)
            .map(|i| i as u8)
            .ok_or_else(|| format!("{FILE_NAME} does not know the {what} '{name}'"))
    }

    let mut classes: Vec<_> = evidence.classes.iter().collect();
    classes.sort();
    let mut methods: Vec<_> = evidence.methods.iter().collect();
    methods.sort();
    let mut gaps: Vec<_> = evidence.call_site_gaps.iter().collect();
    gaps.sort();

    let mut p = Vec::new();
    // The build's scope, which only the JVM frontend has: no packages, no libraries, no budget.
    strings(&mut p, &[])?;
    strings(&mut p, &[])?;
    p.push(0);
    strings(&mut p, &[])?;
    optional(&mut p, None)?;
    strings(&mut p, &[])?;
    strings(&mut p, &[])?;
    p.extend((-1i32).to_be_bytes());
    strings(&mut p, &[])?;
    let mut statuses = STATUSES.to_vec();
    statuses.push(UNKNOWN_MEMBER);
    strings(&mut p, &statuses)?;
    strings(&mut p, &ROLES)?;
    strings(&mut p, &GAPS)?;
    count(&mut p, 0)?; // artifacts
    count(&mut p, classes.len())?;
    for (name, origin, status) in classes {
        utf(&mut p, name)?;
        optional(&mut p, origin.as_deref())?;
        p.push(code(&STATUSES, status, "status")?);
    }
    count(&mut p, methods.len())?;
    for (identity, status, message) in methods {
        utf(&mut p, identity)?;
        p.push(code(&STATUSES, status, "status")?);
        optional(&mut p, message.as_deref())?;
    }
    count(&mut p, 0)?; // duplicate classes
    count(&mut p, gaps.len())?;
    for (id, gap) in gaps {
        let id = i32::try_from(*id).map_err(|_| format!("{FILE_NAME} cannot name node {id}"))?;
        p.extend(id.to_be_bytes());
        p.push(code(&GAPS, gap, "dispatch gap")?);
    }

    let mut out = MAGIC_VERSION.to_be_bytes().to_vec();
    out.extend_from_slice(&layout);
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    std::io::Write::write_all(&mut gz, &p).map_err(|e| e.to_string())?;
    out.extend(gz.finish().map_err(|e| e.to_string())?);
    let digest = Sha256::digest(&out);
    out.extend_from_slice(&digest);
    Ok(out)
}

/// The two `forward.properties` lines that bind `sidecar` (the bytes [`encode`] returned).
pub fn binding_properties(sidecar: &[u8]) -> String {
    format!(
        "{LAYOUT_KEY}={}\n{BINDING_KEY}={}\n",
        hex(&sidecar[4..HEADER]),
        hex(&sidecar[sidecar.len() - DIGEST..])
    )
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `layout` and `binding` are the lower-case hex digests `forward.properties` records.
pub fn parse(bytes: &[u8], layout: &str, binding: &str) -> Result<Option<Completeness>, String> {
    if bytes.len() < HEADER + DIGEST
        || u32::from_be_bytes(bytes[..4].try_into().unwrap()) != MAGIC_VERSION
    {
        return Err(format!("{FILE_NAME} is not a completeness sidecar"));
    }
    let payload_end = bytes.len() - DIGEST;
    if hex(&bytes[payload_end..]) != binding || hex(&bytes[4..HEADER]) != layout {
        return Ok(None);
    }
    if Sha256::digest(&bytes[..payload_end])[..] != bytes[payload_end..] {
        return Err(format!("{FILE_NAME} does not match its digest"));
    }
    let mut payload = Vec::new();
    flate2::read::GzDecoder::new(&bytes[HEADER..payload_end])
        .read_to_end(&mut payload)
        .map_err(|e| format!("{FILE_NAME} is not a gzip payload: {e}"))?;
    Payload {
        data: &payload,
        pos: 0,
    }
    .read()
    .map(Some)
}

struct Payload<'a> {
    data: &'a [u8],
    pos: usize,
}

impl Payload<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], String> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|end| *end <= self.data.len())
            .ok_or_else(|| format!("{FILE_NAME} is truncated"))?;
        let slice = &self.data[self.pos..end];
        self.pos = end;
        Ok(slice)
    }
    fn byte(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }
    fn bool(&mut self) -> Result<bool, String> {
        Ok(self.byte()? != 0)
    }
    fn int(&mut self) -> Result<i32, String> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn count(&mut self) -> Result<usize, String> {
        usize::try_from(self.int()?).map_err(|_| format!("{FILE_NAME} holds a negative count"))
    }
    fn utf(&mut self) -> Result<String, String> {
        let len = u16::from_be_bytes(self.take(2)?.try_into().unwrap()) as usize;
        Ok(crate::javaser::decode_java_utf(self.take(len)?))
    }
    fn optional(&mut self) -> Result<Option<String>, String> {
        if self.bool()? {
            self.utf().map(Some)
        } else {
            Ok(None)
        }
    }
    fn strings(&mut self) -> Result<Vec<String>, String> {
        (0..self.count()?).map(|_| self.utf()).collect()
    }
    fn code<'n, T>(&mut self, names: &'n [T]) -> Result<&'n T, String> {
        let code = self.byte()? as usize;
        names
            .get(code)
            .ok_or_else(|| format!("{FILE_NAME} names code {code} among {}", names.len()))
    }

    fn read(mut self) -> Result<Completeness, String> {
        // The build's scope: options a consumer compares, not needed to answer a status.
        self.strings()?;
        self.strings()?;
        self.bool()?;
        self.strings()?;
        self.optional()?;
        self.strings()?;
        self.strings()?;
        self.int()?;
        self.strings()?;
        let statuses = self
            .strings()?
            .into_iter()
            .map(|name| {
                STATUSES
                    .iter()
                    .chain(std::iter::once(&UNKNOWN_MEMBER))
                    .find(|known| **known == name)
                    .copied()
                    .ok_or_else(|| {
                        format!("{FILE_NAME} names a status '{name}' this reader does not know")
                    })
            })
            .collect::<Result<Vec<&'static str>, String>>()?;
        let roles = self.strings()?;
        let gaps = self.strings()?;
        let mut completeness = Completeness::default();
        for _ in 0..self.count()? {
            let name = self.utf()?;
            let role = self.code(&roles)?.clone();
            self.bool()?;
            completeness.artifacts.push((name, role));
        }
        for _ in 0..self.count()? {
            let name = self.utf()?;
            let origin = self.optional()?;
            let status = *self.code(&statuses)?;
            completeness.classes.insert(name, (origin, status));
        }
        for _ in 0..self.count()? {
            let identity = self.utf()?;
            let status = *self.code(&statuses)?;
            let message = self.optional()?;
            completeness.methods.insert(identity, (status, message));
        }
        for _ in 0..self.count()? {
            self.utf()?;
            self.strings()?;
        }
        for _ in 0..self.count()? {
            let id = self.int()?;
            let gap = self.code(&gaps)?.clone();
            completeness.call_site_gaps.insert(id as u32, gap);
        }
        if self.pos != self.data.len() {
            return Err(format!("{FILE_NAME} has bytes after its last entry"));
        }
        Ok(completeness)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn utf(out: &mut Vec<u8>, s: &str) {
        out.extend_from_slice(&(s.len() as u16).to_be_bytes());
        out.extend_from_slice(s.as_bytes());
    }
    fn strings(out: &mut Vec<u8>, values: &[&str]) {
        out.extend_from_slice(&(values.len() as i32).to_be_bytes());
        values.iter().for_each(|v| utf(out, v));
    }

    /// A payload as `AnalysisCompletenessStore.write` lays it out.
    fn payload() -> Vec<u8> {
        let mut p = Vec::new();
        strings(&mut p, &["com.acme."]);
        strings(&mut p, &[]);
        p.push(1);
        strings(&mut p, &[]);
        p.push(0);
        strings(&mut p, &[]);
        strings(&mut p, &["kotlin.text."]);
        p.extend_from_slice(&(-1i32).to_be_bytes());
        strings(&mut p, &[]);
        let mut statuses: Vec<&str> = STATUSES.to_vec();
        statuses.push(UNKNOWN_MEMBER);
        strings(&mut p, &statuses);
        strings(&mut p, &["APPLICATION", "LIBRARY", "PLATFORM"]);
        strings(
            &mut p,
            &[
                "DISPATCH_UNRESOLVED",
                "DISPATCH_SATURATED",
                "DISPATCH_PARTIAL",
            ],
        );
        p.extend_from_slice(&1i32.to_be_bytes());
        utf(&mut p, "app.jar");
        p.push(0);
        p.push(0);
        p.extend_from_slice(&2i32.to_be_bytes());
        utf(&mut p, "com.acme.App");
        p.push(1);
        utf(&mut p, "app.jar");
        p.push(0); // ANALYZED
        utf(&mut p, "lib.Helper");
        p.push(0);
        p.push(7); // EXCLUDED_BY_SCOPE
        p.extend_from_slice(&1i32.to_be_bytes());
        utf(&mut p, "com.acme.App.big()V");
        p.push(1); // ANALYZED_WITHOUT_CONTROL_FLOW
        p.push(0);
        p.extend_from_slice(&0i32.to_be_bytes());
        p.extend_from_slice(&1i32.to_be_bytes());
        p.extend_from_slice(&42i32.to_be_bytes());
        p.push(2);
        p
    }

    fn sidecar(payload: &[u8], layout: [u8; 32]) -> Vec<u8> {
        let mut out = MAGIC_VERSION.to_be_bytes().to_vec();
        out.extend_from_slice(&layout);
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        gz.write_all(payload).unwrap();
        out.extend(gz.finish().unwrap());
        let digest = Sha256::digest(&out);
        out.extend_from_slice(&digest);
        out
    }

    #[test]
    fn a_loaded_graph_keeps_its_own_evidence_after_its_directory_is_replaced() {
        let dir = std::env::temp_dir().join(format!(
            "graphite-completeness-generation-{}",
            std::process::id()
        ));
        let write = |with_evidence: bool| {
            let _ = std::fs::remove_dir_all(&dir);
            let graph = serde_json::json!({
                "version": 1, "methods": [], "edges": [],
                "nodes": [{"id": 0, "kind": "StringConstant", "value": "a"}]
            });
            crate::interchange::import_json(graph.to_string().as_bytes(), &dir).unwrap();
            if with_evidence {
                let bytes = sidecar(&payload(), [7; 32]);
                let binding = hex(&bytes[bytes.len() - 32..]);
                std::fs::write(dir.join(FILE_NAME), &bytes).unwrap();
                let properties = dir.join("forward.properties");
                let mut text = std::fs::read_to_string(&properties).unwrap();
                text.push_str(&format!(
                    "{LAYOUT_KEY}={}\n{BINDING_KEY}={binding}\n",
                    hex(&[7; 32])
                ));
                std::fs::write(&properties, text).unwrap();
                crate::manifest::GraphManifest::write_dir(&dir).unwrap();
            }
        };
        write(true);
        let graph = crate::Graph::load(&dir).unwrap();
        // A new save of the same path before the first status question.
        write(false);
        assert_eq!(
            crate::Graph::load(&dir).unwrap().dispatch_gap(42).unwrap(),
            None,
            "the new graph has no evidence"
        );
        assert_eq!(graph.dispatch_gap(42).unwrap(), Some("DISPATCH_PARTIAL"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// The file the importer wrote for a JavaScript build, byte for byte; the JVM reads the same
    /// bytes in `AnalysisCompletenessPersistenceTest` ("the sidecar the Rust importer writes").
    const IMPORTER_WRITTEN: &str = "4743500188a96348ffcd89a085b33ef1583d3e96dc9c1f7dd79bb072cbf8af5b9934b3781f8b08000000000000ff6d90d14a\
             c3401045470b6d488b587df617fc87c96642173733cb66d224be2c167ca8da0afaffe8a6d5e08317160e87e1ceb000ffe52b\
             e507979021a31b1ea984bb5f8a9dd58db41a8db00671b172d2a5c1a2d1804661cea8764bb06089859403e4968dd4de9112ac\
             0335e25ab5c2b142eb52f19a7ae3da321517436c8c7882158b46ea3d72d29051af14d276b86af981a5e358535d504817ce60\
             89de3b6b70ac8485b345c03040e61d6a25a13ecddc96b6f1a866135b3e1db04db537936c50db809adcf5e43c06b569e53933\
             c8771fefafcfc7fbc3cb27cc61f1b6df8d783111407678da1fcf72c2bfff7a995e3ec20abe0172dfa85579010000c683ddae\
             21844f8c50b254e63284b045ccac93d014742e09fefb48ebd04ce5ff";
    const IMPORTER_LAYOUT: &str =
        "88a96348ffcd89a085b33ef1583d3e96dc9c1f7dd79bb072cbf8af5b9934b378";
    const IMPORTER_BINDING: &str =
        "c683ddae21844f8c50b254e63284b045ccac93d014742e09fefb48ebd04ce5ff";

    #[test]
    fn the_jvm_reads_the_sidecar_the_importer_writes() {
        let bytes: Vec<u8> = (0..IMPORTER_WRITTEN.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&IMPORTER_WRITTEN[i..i + 2], 16).unwrap())
            .collect();
        assert_eq!(
            binding_properties(&bytes),
            format!("{LAYOUT_KEY}={IMPORTER_LAYOUT}\n{BINDING_KEY}={IMPORTER_BINDING}\n")
        );
        let read = parse(&bytes, IMPORTER_LAYOUT, IMPORTER_BINDING)
            .unwrap()
            .unwrap();
        let evidence = Evidence {
            classes: vec![
                ("main.mjs".into(), Some("main.mjs".into()), ANALYZED.into()),
                ("broken.mjs".into(), None, "RESOLUTION_FAILED".into()),
                ("lib.mjs".into(), Some("lib.mjs".into()), ANALYZED.into()),
            ],
            methods: vec![],
            call_site_gaps: vec![
                (12, "DISPATCH_UNRESOLVED".into()),
                (10, "DISPATCH_UNRESOLVED".into()),
            ],
        };
        // What this encoder writes for that evidence reads as the fixture reads.
        let layout: [u8; DIGEST] = bytes[4..HEADER].try_into().unwrap();
        let written = encode(&evidence, layout).unwrap();
        let binding = hex(&written[written.len() - DIGEST..]);
        assert_eq!(
            parse(&written, IMPORTER_LAYOUT, &binding).unwrap().unwrap(),
            read
        );
        assert_eq!(read.classes.len(), 3);
        assert_eq!(read.class_status("broken.mjs"), "RESOLUTION_FAILED");
        assert_eq!(read.classes["lib.mjs"], (Some("lib.mjs".into()), ANALYZED));
        assert_eq!(read.call_site_gaps[&10], "DISPATCH_UNRESOLVED");
        assert_eq!(read.call_site_gaps.len(), 2);
        assert!(read.methods.is_empty() && read.artifacts.is_empty());
        // A status or a gap the format does not name, and a node id it cannot hold, are refused.
        for (bad, message) in [
            (
                Evidence {
                    classes: vec![("a".into(), None, "UNKNOWN_MEMBER".into())],
                    ..Evidence::default()
                },
                "does not know the status 'UNKNOWN_MEMBER'",
            ),
            (
                Evidence {
                    methods: vec![("a.f()V".into(), "DONE".into(), None)],
                    ..Evidence::default()
                },
                "does not know the status 'DONE'",
            ),
            (
                Evidence {
                    call_site_gaps: vec![(1, "LOST".into())],
                    ..Evidence::default()
                },
                "does not know the dispatch gap 'LOST'",
            ),
            (
                Evidence {
                    call_site_gaps: vec![(u32::MAX, "DISPATCH_PARTIAL".into())],
                    ..Evidence::default()
                },
                "cannot name node 4294967295",
            ),
        ] {
            let error = encode(&bad, layout).unwrap_err();
            assert!(error.contains(message), "{error}");
        }
    }

    #[test]
    fn statuses_follow_the_jvm_rules() {
        let bytes = sidecar(&payload(), [7; 32]);
        let binding = hex(&bytes[bytes.len() - 32..]);
        let c = parse(&bytes, &hex(&[7; 32]), &binding).unwrap().unwrap();
        assert_eq!(c.class_status("com.acme.App"), "ANALYZED");
        assert_eq!(c.class_status("lib.Helper"), "EXCLUDED_BY_SCOPE");
        assert_eq!(c.class_status("java.lang.String"), "EXTERNAL");
        assert_eq!(
            c.status_of("com.acme.App", "com.acme.App.big()V", true),
            "ANALYZED_WITHOUT_CONTROL_FLOW"
        );
        assert_eq!(
            c.status_of("com.acme.App", "com.acme.App.run()V", true),
            "ANALYZED"
        );
        assert_eq!(
            c.status_of("com.acme.App", "com.acme.App.gone()V", false),
            "UNKNOWN_MEMBER"
        );
        assert_eq!(
            c.status_of("lib.Helper", "lib.Helper.trim()V", false),
            "EXCLUDED_BY_SCOPE"
        );
        assert_eq!(
            c.status_of(
                "java.lang.String",
                "java.lang.String.trim()Ljava/lang/String;",
                false
            ),
            "EXTERNAL"
        );
        assert_eq!(c.call_site_gaps[&42], "DISPATCH_PARTIAL");
        assert_eq!(
            c.artifacts,
            vec![("app.jar".to_string(), "APPLICATION".to_string())]
        );
        assert_eq!(c.classes["com.acme.App"].0.as_deref(), Some("app.jar"));
    }

    #[test]
    fn another_saves_file_is_absent_and_damage_is_an_error() {
        let bytes = sidecar(&payload(), [7; 32]);
        let binding = hex(&bytes[bytes.len() - 32..]);
        assert_eq!(
            parse(&bytes, &hex(&[8; 32]), &binding).unwrap(),
            None,
            "another node layout"
        );
        assert_eq!(
            parse(&bytes, &hex(&[7; 32]), &"0".repeat(64)).unwrap(),
            None,
            "another save"
        );
        let mut flipped = bytes.clone();
        // Inside the deflate stream: the gzip header's timestamp is covered by no checksum.
        let at = HEADER + (bytes.len() - HEADER - DIGEST) / 2;
        flipped[at] ^= 1;
        let digest = Sha256::digest(&flipped[..flipped.len() - 32]);
        assert_eq!(
            parse(&flipped, &hex(&[7; 32]), &binding).unwrap_err(),
            "graph.completeness does not match its digest"
        );
        // Re-signed damage still fails to parse.
        let len = flipped.len();
        flipped[len - 32..].copy_from_slice(&digest);
        let resigned = hex(&digest);
        assert!(parse(&flipped, &hex(&[7; 32]), &resigned).is_err());
        assert!(parse(b"short", "", "")
            .unwrap_err()
            .contains("not a completeness sidecar"));
        let mut extra = payload();
        extra.push(0);
        let bytes = sidecar(&extra, [7; 32]);
        let binding = hex(&bytes[bytes.len() - 32..]);
        assert!(parse(&bytes, &hex(&[7; 32]), &binding)
            .unwrap_err()
            .contains("bytes after"));
        let truncated = payload()[..40].to_vec();
        let bytes = sidecar(&truncated, [7; 32]);
        let binding = hex(&bytes[bytes.len() - 32..]);
        assert!(parse(&bytes, &hex(&[7; 32]), &binding)
            .unwrap_err()
            .contains("truncated"));
    }
}
