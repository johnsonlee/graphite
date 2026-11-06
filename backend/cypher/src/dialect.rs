//! Public query compatibility modes. Both modes use the same parser and executor.
use std::{fmt, str::FromStr};

/// Graphite preserves its established query extensions; M23 selects standard contracts.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum CypherDialect {
    #[default]
    Graphite,
    OpenCypherM23,
}

impl fmt::Display for CypherDialect {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Graphite => "graphite",
            Self::OpenCypherM23 => "opencypher-m23",
        })
    }
}

impl FromStr for CypherDialect {
    type Err = String;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "graphite" => Ok(Self::Graphite),
            "opencypher-m23" => Ok(Self::OpenCypherM23),
            _ => Err("'dialect' must be 'graphite' or 'opencypher-m23'".into()),
        }
    }
}
