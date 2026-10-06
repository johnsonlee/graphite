//! Language-independent schema records and binary interchange.
//!
//! Definitions travel with data. The codec does not dispatch on language names,
//! node kinds, operators, or predicates. This crate is independent of the legacy
//! v1–v3 storage reader and does not allocate a new persisted graph version.
//!
//! A previously unknown operator can be inspected without a language plugin:
//!
//! ```
//! use graphite_schema::*;
//! use std::collections::BTreeMap;
//!
//! let name = |local| QualifiedName::new("example.future", local);
//! let document = Document {
//!     strings: BTreeMap::from([(0, "region-local".into())]),
//!     definitions: BTreeMap::from([(7, Definition {
//!         name: name("region-type"), revision: "1".into(),
//!         category: QualifiedName::new("core", "expression"),
//!         fields: vec![Field {
//!             name: name("mode"), descriptor: Descriptor::String,
//!             required: true, nullable: false, role: None,
//!         }],
//!     })]),
//!     tables: BTreeMap::from([(3, Table {
//!         name: name("types"),
//!         rows: BTreeMap::from([(42, Record {
//!             layout: 7, fields: vec![Some(Value::String(0))],
//!         })]),
//!     })]),
//!     ..Document::default()
//! };
//! let limits = Limits::default();
//! let restored = decode(&encode(&document, &limits)?, &limits)?;
//! let record = restored.record(Reference { table: 3, row: 42 }).unwrap();
//! assert_eq!(restored.field(record, &name("mode")), Some(&Value::String(0)));
//! assert_eq!(restored, document);
//! # Ok::<(), Error>(())
//! ```

mod codec;
mod model;

pub use codec::{decode, encode, WIRE_VERSION};
pub use model::*;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("invalid schema document: {0}")]
    Invalid(String),
    #[error("schema resource limit exceeded: {0}")]
    Limit(&'static str),
    #[error("unsupported schema wire version {0}")]
    UnsupportedVersion(u32),
    #[error("truncated schema data")]
    Truncated,
    #[error("schema checksum mismatch")]
    Checksum,
}

pub type Result<T> = std::result::Result<T, Error>;
