#![doc = include_str!("../README.md")]

mod codec;
mod mapped;
mod model;

pub use codec::{decode, encode, WIRE_VERSION};
pub use mapped::{encode_mapped, MappedDocument, MappedLimits, MAPPED_VERSION};
pub use model::*;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("schema I/O error: {0}")]
    Io(#[from] std::io::Error),
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
