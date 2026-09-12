//! C4 architecture inference and rendering.
//!
//! The pipeline reads the code graph and infers, in order: the system boundary (the
//! dominant package prefix), the subject (application vs library), external
//! dependencies (grouped by artifact, runtime or namespace), internal containers
//! (clusters of tightly-coupled package units) and components. The result is mapped
//! to a Structurizr workspace, which the renderers turn into Mermaid, PlantUML or DSL.

pub mod constants;
pub mod util;

mod boundary;
mod components;
mod containers;
mod external;
mod model;
mod render;
mod subject;

use graphite_storage::Graph;
use serde_json::Value as J;

pub use model::build_model;
pub use render::{render_dsl, render_mermaid, render_plantuml};

pub const LEVELS: [&str; 4] = ["context", "container", "component", "all"];
pub const FORMATS: [&str; 4] = ["json", "mermaid", "plantuml", "dsl"];

/// Convert a persisted annotation value to JSON, resolving string-table ids.
pub fn any_value_json(g: &Graph, v: &graphite_storage::AnyValue) -> J {
    use graphite_storage::AnyValue as A;
    use serde_json::Number;
    match v {
        A::Int(i) => J::Number(Number::from(*i as i64)),
        A::Long(l) => J::Number(Number::from(*l)),
        A::Str(s) => J::String(g.str(*s).to_string()),
        A::Float(f) => Number::from_f64(*f as f64)
            .map(J::Number)
            .unwrap_or(J::Null),
        A::Double(d) => Number::from_f64(*d).map(J::Number).unwrap_or(J::Null),
        A::Bool(b) => J::Bool(*b),
        A::Null => J::Null,
        A::EnumRef {
            enum_class,
            enum_name,
        } => J::String(format!("{}.{}", g.str(*enum_class), g.str(*enum_name))),
        A::List(items) => J::Array(items.iter().map(|i| any_value_json(g, i)).collect()),
    }
}
