//! Partitioned evaluation: run an aggregating query once per class of nodes that its
//! expressions cannot tell apart, weighted by the size of the class.
//!
//! The general pipeline costs a row per matched node: the node bound into an
//! `IndexMap`, every projected expression interpreted on it, a group key encoded and
//! looked up. Most of what an agent asks a graph it has just met -- which labels
//! exist and how many nodes carry each, which keys a type exposes, how call sites
//! split by callee class, how the nodes split across graphs -- reads nothing of a
//! node that a million other nodes do not share. Paying a microsecond per node for
//! such a query is a timeout over a fleet, and recognising each phrasing of it by
//! shape is a race that cannot be won: every alias, function or `WITH` an agent
//! adds is a new shape.
//!
//! Instead the executor classifies each expression by what it reads:
//!
//! | level     | determined by                          | examples                          |
//! |-----------|----------------------------------------|-----------------------------------|
//! | `Const`   | nothing                                | `1`, `'x'`, `$p`, `count(*)`      |
//! | `Source`  | the graph                              | `n.graphId`                       |
//! | `Tag`     | the node's type                        | `labels(n)`, `keys(n)`, `type(r)` |
//! | `Column`  | a few string ids read raw off the record | `n.callee_class`, `n.name`      |
//! | `Keys`    | the node's set of property keys        | `keys(n)` of an annotation        |
//! | `Content` | the decoded record                     | `n.line`, `id(n)`, `n`            |
//!
//! and a pure function or operator is at the level of its arguments. For each type
//! of node in each graph, the coarsest partition that determines every expression
//! of the segment -- the WHERE, the unwound lists, the intermediate projections and
//! the aggregation's own items -- is enumerated: one weighted row per type, one per
//! distinct tuple of string ids, one per distinct key set, or, when the record
//! itself is read, one per node.
//! Each row binds a *representative* node of its partition, so the existing
//! evaluator computes every expression exactly as it would on any member, and
//! carries a hidden weight that the aggregation applies: `count` sums weights,
//! `sum` and `avg` weigh their inputs, `min`, `max` and the `DISTINCT` aggregates
//! ignore them. Rows come out in the order the pipeline would first meet each
//! partition, so first-seen group order, provenance, `ORDER BY` and `LIMIT` are
//! untouched, and the clauses after the match run unchanged over a few rows
//! instead of a few million.
//!
//! Nothing here interprets Cypher: a query is partitioned only when every
//! expression is classified and every aggregate can be weighted; anything else
//! takes the general pipeline, which remains the single source of truth.

use super::fastpath::{raw_string_field, read_string_field, StringField};
use super::matching::{rel_type_matches, resolve_node_class, MergedWalk, NodeClass};
use super::pipeline::{add_provenance, add_provenance_id, Row};
use super::scan::ScanPlan;
use super::Executor;
use crate::ast::{BinOp, Clause, Direction, Expr, Literal, Pattern, ReturnItem};
use crate::context::GraphContext;
use crate::eval::{is_aggregation_name, Evaluator};
use crate::value::{EdgeRef, NodeRef, SourceIdx, Value};
use crate::CypherResult;
use graphite_storage::node::{StrId, TAG_ANNOTATION_NODE, TAG_COUNT};
use graphite_storage::{Edge, Graph};
use indexmap::IndexMap;
use rayon::prelude::*;
use std::collections::HashMap;

/// Hidden row key carrying how many matches a row stands for. Absent means one.
pub const INTERNAL_WEIGHT_KEY: &str = "\u{0}graphite.weight";

/// How many matches a row stands for.
#[inline]
pub fn row_weight(row: &Row) -> i64 {
    match row.get(INTERNAL_WEIGHT_KEY) {
        Some(Value::Int(w)) => *w,
        _ => 1,
    }
}

// ---------------------------------------------------------------------------
// Dependence analysis
// ---------------------------------------------------------------------------

/// What an expression reads of a matched node, coarsest first.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
enum Level {
    Const,
    Source,
    Tag,
    Column,
    /// The node's set of property keys: fixed per type for every type but the
    /// annotations, whose values add keys of their own.
    Keys,
    Content,
}

/// An expression's dependence: its level, at `Column` which of the type's raw
/// string columns it reads (a bit per column), and whether it reads the graph
/// itself (`graphId`), which a finer level does not imply.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct Dep {
    level: Level,
    columns: u8,
    graph: bool,
}

impl Dep {
    const CONST: Dep = Dep {
        level: Level::Const,
        columns: 0,
        graph: false,
    };
    const SOURCE: Dep = Dep {
        level: Level::Source,
        columns: 0,
        graph: true,
    };
    const TAG: Dep = Dep {
        level: Level::Tag,
        columns: 0,
        graph: false,
    };
    const KEYS: Dep = Dep {
        level: Level::Keys,
        columns: 0,
        graph: false,
    };
    const CONTENT: Dep = Dep {
        level: Level::Content,
        columns: 0,
        graph: false,
    };

    fn column(i: usize) -> Dep {
        Dep {
            level: Level::Column,
            columns: 1 << i,
            graph: false,
        }
    }

    fn join(self, o: Dep) -> Dep {
        Dep {
            level: self.level.max(o.level),
            columns: self.columns | o.columns,
            graph: self.graph || o.graph,
        }
    }
}

/// The raw string columns a type exposes, by property name, in a fixed order so a
/// column bit means the same thing everywhere.
const COLUMN_PROPS: [&str; 12] = [
    "caller_class",
    "caller_name",
    "callee_class",
    "callee_name",
    "value",
    "name",
    "type",
    "class",
    "member",
    "path",
    "source",
    "format",
];

fn tag_columns(tag: u8) -> Vec<(&'static str, StringField)> {
    COLUMN_PROPS
        .iter()
        .filter_map(|p| raw_string_field(tag, p).map(|f| (*p, f)))
        .collect()
}

/// The type a node variable is analysed against.
struct TagCtx<'a> {
    /// `None` for the end of a hop, where the type is fixed per partition but the
    /// record is never read.
    tag: Option<u8>,
    /// The keys a node of the type exposes (`keys(n)`), read off one node.
    keys: &'a [String],
    columns: &'a [(&'static str, StringField)],
}

struct Scope<'a> {
    cross: bool,
    nodes: Vec<(&'a str, &'a TagCtx<'a>)>,
    rel: Option<&'a str>,
    /// Variables whose dependence is already known: unwound lists, `WITH` aliases,
    /// comprehension variables.
    bound: Vec<(String, Dep)>,
}

impl<'a> Scope<'a> {
    fn node(&self, v: &str) -> Option<&'a TagCtx<'a>> {
        self.nodes.iter().find(|(n, _)| *n == v).map(|(_, c)| *c)
    }
    fn bound(&self, v: &str) -> Option<Dep> {
        self.bound
            .iter()
            .rev()
            .find(|(n, _)| n == v)
            .map(|(_, d)| *d)
    }
}

/// Functions whose result depends on nothing but their arguments.
const PURE: [&str; 52] = [
    "coalesce",
    "exists",
    "tofloat",
    "toboolean",
    "tostring",
    "tointeger",
    "toint",
    "size",
    "length",
    "tolower",
    "tolowercase",
    "toupper",
    "touppercase",
    "trim",
    "ltrim",
    "rtrim",
    "replace",
    "substring",
    "split",
    "left",
    "right",
    "reverse",
    "head",
    "tail",
    "last",
    "range",
    "abs",
    "ceil",
    "floor",
    "round",
    "sqrt",
    "exp",
    "log",
    "log10",
    "sin",
    "cos",
    "tan",
    "cot",
    "asin",
    "acos",
    "atan",
    "degrees",
    "radians",
    "sign",
    "pi",
    "e",
    "count",
    "sum",
    "avg",
    "min",
    "max",
    "collect",
];

fn dep(e: &Expr, scope: &Scope) -> Dep {
    let all = |es: &[Expr]| es.iter().fold(Dep::CONST, |d, e| d.join(dep(e, scope)));
    match e {
        Expr::Literal(_) | Expr::Parameter(_) | Expr::CountStar => Dep::CONST,
        Expr::Variable(v) => scope.bound(v).unwrap_or(Dep::CONTENT),
        Expr::Property { expr, key } => match expr.as_ref() {
            Expr::Variable(v) if scope.node(v).is_some() => {
                property_dep(scope.node(v).unwrap(), key, scope.cross)
            }
            Expr::Variable(v) if scope.rel == Some(v.as_str()) => {
                rel_property_dep(key, scope.cross)
            }
            other => dep(other, scope),
        },
        Expr::Subscript { expr, index } => match (expr.as_ref(), index.as_ref()) {
            (Expr::Variable(v), Expr::Literal(Literal::Str(key))) if scope.node(v).is_some() => {
                property_dep(scope.node(v).unwrap(), key, scope.cross)
            }
            _ => dep(expr, scope).join(dep(index, scope)),
        },
        Expr::FunctionCall {
            name,
            args,
            distinct,
        } => {
            let lower = name.to_ascii_lowercase();
            let node_arg = match args.as_slice() {
                [Expr::Variable(v)] => scope.node(v),
                _ => None,
            };
            let rel_arg =
                matches!(args.as_slice(), [Expr::Variable(v)] if scope.rel == Some(v.as_str()));
            match lower.as_str() {
                // A bound node or relationship is never null: `count(n)` counts rows.
                // Counting them distinctly reads their identity.
                "count" if (node_arg.is_some() || rel_arg) && !*distinct => Dep::CONST,
                "labels" if node_arg.is_some() => Dep::TAG,
                "keys" => match node_arg {
                    // An annotation's values add keys of their own: its key set is
                    // read off each node, and partitions the type by itself.
                    Some(ctx) => match ctx.tag {
                        Some(t) if t != TAG_ANNOTATION_NODE => Dep::TAG,
                        Some(_) => Dep::KEYS,
                        None => Dep::CONTENT,
                    },
                    None => Dep::CONTENT,
                },
                "type" if rel_arg => Dep::TAG,
                "graphid" if node_arg.is_some() || rel_arg => {
                    if scope.cross {
                        Dep::SOURCE
                    } else {
                        Dep::TAG
                    }
                }
                _ if node_arg.is_some() || rel_arg => Dep::CONTENT,
                _ if PURE.contains(&lower.as_str()) => all(args),
                _ => Dep::CONTENT,
            }
        }
        Expr::Distinct(e) | Expr::Not(e) | Expr::IsNull(e) | Expr::IsNotNull(e) => dep(e, scope),
        Expr::Unary { expr, .. } => dep(expr, scope),
        Expr::Binary { left, right, .. }
        | Expr::Comparison { left, right, .. }
        | Expr::StringOp { left, right, .. }
        | Expr::In { left, right } => dep(left, scope).join(dep(right, scope)),
        Expr::And(a, b) | Expr::Or(a, b) | Expr::Xor(a, b) => dep(a, scope).join(dep(b, scope)),
        Expr::Case {
            test,
            whens,
            else_expr,
        } => {
            let mut d = test.as_deref().map(|t| dep(t, scope)).unwrap_or(Dep::CONST);
            for (w, t) in whens {
                d = d.join(dep(w, scope)).join(dep(t, scope));
            }
            if let Some(e) = else_expr {
                d = d.join(dep(e, scope));
            }
            d
        }
        Expr::ListLiteral(items) => all(items),
        Expr::MapLiteral(entries) => entries
            .iter()
            .fold(Dep::CONST, |d, (_, e)| d.join(dep(e, scope))),
        Expr::ListComprehension {
            variable,
            list,
            filter,
            map,
        } => {
            let d = dep(list, scope);
            let inner = Scope {
                cross: scope.cross,
                nodes: scope.nodes.clone(),
                rel: scope.rel,
                bound: {
                    let mut b = scope.bound.clone();
                    b.push((variable.clone(), d));
                    b
                },
            };
            let f = filter
                .as_deref()
                .map(|f| dep(f, &inner))
                .unwrap_or(Dep::CONST);
            let m = map.as_deref().map(|m| dep(m, &inner)).unwrap_or(Dep::CONST);
            d.join(f).join(m)
        }
        Expr::PredicateFunction {
            variable,
            list,
            predicate,
            ..
        } => {
            let d = dep(list, scope);
            let inner = Scope {
                cross: scope.cross,
                nodes: scope.nodes.clone(),
                rel: scope.rel,
                bound: {
                    let mut b = scope.bound.clone();
                    b.push((variable.clone(), d));
                    b
                },
            };
            let p = predicate
                .as_deref()
                .map(|p| dep(p, &inner))
                .unwrap_or(Dep::CONST);
            d.join(p)
        }
        Expr::Slice { expr, from, to } => {
            let mut d = dep(expr, scope);
            if let Some(f) = from {
                d = d.join(dep(f, scope));
            }
            if let Some(t) = to {
                d = d.join(dep(t, scope));
            }
            d
        }
    }
}

/// `n.<key>` for a node of the context's type.
fn property_dep(ctx: &TagCtx, key: &str, cross: bool) -> Dep {
    if key == "graphId" {
        return if cross { Dep::SOURCE } else { Dep::TAG };
    }
    let Some(tag) = ctx.tag else {
        // A hop's end: only what the graph decides is known without the record.
        return Dep::CONTENT;
    };
    if cross && (key == "elementId" || key == "qualifiedId") {
        return Dep::CONTENT;
    }
    if let Some(i) = ctx.columns.iter().position(|(p, _)| *p == key) {
        return Dep::column(i);
    }
    if tag == TAG_ANNOTATION_NODE {
        // Values and the keys they add.
        return Dep::CONTENT;
    }
    if ctx.keys.iter().any(|k| k == key) {
        return Dep::CONTENT;
    }
    // A property the type never carries: null, or the type name for `type`.
    Dep::TAG
}

/// `r.<key>`: everything a relationship exposes follows from its label byte.
fn rel_property_dep(key: &str, cross: bool) -> Dep {
    match key {
        "graphId" => {
            if cross {
                Dep::SOURCE
            } else {
                Dep::TAG
            }
        }
        "type" | "kind" | "virtual" | "dynamic" => Dep::TAG,
        _ => Dep::TAG,
    }
}

// ---------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------

/// A clause between the match and the aggregation whose expressions the partition
/// must determine.
enum Step {
    Unwind {
        expr: Expr,
        variable: String,
    },
    With {
        items: Vec<ReturnItem>,
        where_clause: Option<Expr>,
    },
}

enum Shape {
    Node {
        variable: String,
        tags: Vec<u8>,
    },
    Hop {
        a: Option<String>,
        r: Option<String>,
        b: Option<String>,
        tags_a: Vec<u8>,
        tags_b: Vec<u8>,
        types: Vec<String>,
        direction: Direction,
    },
}

/// How one type of one graph is enumerated.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Strategy {
    /// One row for the type, weighted by its population.
    Whole,
    /// One row per distinct tuple of the masked columns.
    Columns(u8),
    /// One row per distinct (key set, masked columns), the record decoded for its
    /// keys: the annotations.
    Keys(u8),
    /// One row per node.
    Each,
}

pub struct PartitionPlan {
    shape: Shape,
    where_clause: Option<Expr>,
    /// Per source, per tag: how the type is enumerated (node shape only).
    strategies: Vec<[Strategy; TAG_COUNT]>,
    /// The string pushdown of the WHERE clause, when it has one: survivors come from
    /// it instead of a raw sweep.
    scan: Option<ScanPlan>,
    /// Across graphs, when nothing reads the graph: partitions that are the same
    /// type (or the same hop slot) in every graph are one row, weighing them all
    /// and carrying every graph, placed where the first was met.
    merge: bool,
}

impl PartitionPlan {
    /// Build a plan for the segment starting at `clauses[0]` (a MATCH), or `None`
    /// when the segment is not an aggregation the partition can answer exactly.
    /// Returns the plan and how many clauses it consumed (the MATCH and its WHERE).
    pub fn build(ex: &Executor, clauses: &[Clause]) -> Option<(PartitionPlan, usize)> {
        if !ex.partition || super::optimizations_disabled() || ex.sources.is_empty() {
            return None;
        }
        let (patterns, match_where) = match clauses.first() {
            Some(Clause::Match {
                patterns,
                optional: false,
                where_clause,
            }) => (patterns, where_clause.clone()),
            _ => return None,
        };
        if patterns.len() != 1 {
            return None;
        }
        let p: &Pattern = &patterns[0];
        if p.path_variable.is_some() || p.rels.iter().any(|r| !r.properties.is_empty()) {
            return None;
        }
        let mut consumed = 1;
        let mut where_clause = match_where;
        if let Some(Clause::Where(e)) = clauses.get(1) {
            if where_clause.is_some() {
                return None;
            }
            where_clause = Some(e.clone());
            consumed = 2;
        }
        // A property map `{k: v}` is `n.k = v` for every entry, as the matcher checks it.
        for np in &p.nodes {
            let Some(var) = &np.variable else {
                if !np.properties.is_empty() {
                    return None;
                }
                continue;
            };
            for (key, value) in &np.properties {
                if !matches!(value, Expr::Literal(_)) {
                    return None;
                }
                let eq = Expr::Comparison {
                    op: crate::ast::CmpOp::Eq,
                    left: Box::new(Expr::Property {
                        expr: Box::new(Expr::Variable(var.clone())),
                        key: key.clone(),
                    }),
                    right: Box::new(value.clone()),
                };
                where_clause = Some(match where_clause {
                    Some(w) => Expr::And(Box::new(eq), Box::new(w)),
                    None => eq,
                });
            }
        }
        let tags = |labels: &[String]| match resolve_node_class(labels) {
            NodeClass::Tags(t) => Some(t),
            _ => None,
        };
        let shape = match (p.nodes.len(), p.rels.len()) {
            (1, 0) => Shape::Node {
                variable: p.nodes[0].variable.clone()?,
                tags: tags(&p.nodes[0].labels)?,
            },
            (2, 1) => {
                let rel = &p.rels[0];
                if rel.variable_length || rel.direction == Direction::Both {
                    return None;
                }
                if p.nodes[0].variable.is_some() && p.nodes[0].variable == p.nodes[1].variable {
                    return None;
                }
                if rel.variable.is_some()
                    && (rel.variable == p.nodes[0].variable || rel.variable == p.nodes[1].variable)
                {
                    return None;
                }
                Shape::Hop {
                    a: p.nodes[0].variable.clone(),
                    r: rel.variable.clone(),
                    b: p.nodes[1].variable.clone(),
                    tags_a: tags(&p.nodes[0].labels)?,
                    tags_b: tags(&p.nodes[1].labels)?,
                    types: rel.types.clone(),
                    direction: rel.direction,
                }
            }
            _ => return None,
        };

        // The clauses up to and including the aggregation.
        let mut steps = Vec::new();
        let mut final_items: Option<&[ReturnItem]> = None;
        let mut j = consumed;
        while j < clauses.len() {
            match &clauses[j] {
                Clause::Unwind { expr, variable } => steps.push(Step::Unwind {
                    expr: expr.clone(),
                    variable: variable.clone(),
                }),
                Clause::With {
                    distinct,
                    items: Some(items),
                    where_clause: with_where,
                } => {
                    let aggregated = items
                        .iter()
                        .any(|it| crate::eval::contains_aggregation(&it.expr));
                    if aggregated || *distinct {
                        // The WHERE of the final WITH filters the aggregated rows,
                        // which the pipeline does after this segment.
                        final_items = Some(items);
                        break;
                    }
                    steps.push(Step::With {
                        items: items.clone(),
                        where_clause: with_where.clone(),
                    });
                    // A page or a sort over weighted rows would count each as one.
                    if matches!(
                        clauses.get(j + 1),
                        Some(Clause::OrderBy(_)) | Some(Clause::Skip(_)) | Some(Clause::Limit(_))
                    ) {
                        return None;
                    }
                }
                Clause::Return {
                    distinct,
                    items: Some(items),
                } => {
                    let aggregated = items
                        .iter()
                        .any(|it| crate::eval::contains_aggregation(&it.expr));
                    if !aggregated && !*distinct {
                        return None;
                    }
                    final_items = Some(items);
                    break;
                }
                _ => return None,
            }
            j += 1;
        }
        let final_items = final_items?;
        if !final_items.iter().all(|it| weighable(&it.expr)) {
            return None;
        }
        let final_aggregated = final_items
            .iter()
            .any(|it| crate::eval::contains_aggregation(&it.expr));

        let plan_deps = |scope: &mut Scope| -> Dep {
            let mut total = where_clause
                .as_ref()
                .map(|w| dep(w, scope))
                .unwrap_or(Dep::CONST);
            for step in &steps {
                match step {
                    Step::Unwind { expr, variable } => {
                        let d = dep(expr, scope);
                        total = total.join(d);
                        scope.bound.push((variable.clone(), d));
                    }
                    Step::With {
                        items,
                        where_clause,
                    } => {
                        // After a WITH only its columns are in scope: a node or
                        // relationship carried through by name stays what it was,
                        // anything else is a value with the dependence of its
                        // expression.
                        let mut aliases = Vec::with_capacity(items.len());
                        let mut kept_nodes = Vec::new();
                        let mut kept_rel = None;
                        for it in items {
                            let d = dep(&it.expr, scope);
                            total = total.join(d);
                            let name = it
                                .alias
                                .clone()
                                .unwrap_or_else(|| crate::render::to_cypher_string(&it.expr));
                            match &it.expr {
                                Expr::Variable(v) if name == *v && scope.node(v).is_some() => {
                                    kept_nodes.push((
                                        scope.nodes.iter().find(|(n, _)| n == v).unwrap().0,
                                        scope.node(v).unwrap(),
                                    ));
                                }
                                Expr::Variable(v)
                                    if name == *v && scope.rel == Some(v.as_str()) =>
                                {
                                    kept_rel = scope.rel;
                                }
                                _ => aliases.push((name, d)),
                            }
                        }
                        scope.bound = aliases;
                        scope.nodes = kept_nodes;
                        scope.rel = kept_rel;
                        // The WITH's own WHERE runs on the projected row: a
                        // predicate the partition does not determine (a volatile
                        // `rand()`, a decoded property of a carried node) would
                        // keep or drop a whole weighted partition on the strength
                        // of its representative.
                        if let Some(w) = where_clause {
                            total = total.join(dep(w, scope));
                        }
                    }
                }
            }
            for it in final_items {
                total = total.join(dep(&it.expr, scope));
            }
            total
        };

        match &shape {
            Shape::Hop { a, r, b, .. } => {
                let end = TagCtx {
                    tag: None,
                    keys: &[],
                    columns: &[],
                };
                let mut nodes = Vec::new();
                if let Some(a) = a {
                    nodes.push((a.as_str(), &end));
                }
                if let Some(b) = b {
                    nodes.push((b.as_str(), &end));
                }
                let mut scope = Scope {
                    cross: ex.cross,
                    nodes,
                    rel: r.as_deref(),
                    bound: Vec::new(),
                };
                let d = plan_deps(&mut scope);
                if d.level > Level::Tag {
                    return None;
                }
                Some((
                    PartitionPlan {
                        shape,
                        where_clause,
                        strategies: Vec::new(),
                        scan: None,
                        merge: ex.cross && !d.graph,
                    },
                    consumed,
                ))
            }
            Shape::Node { variable, tags } => {
                let scan = ScanPlan::build(patterns, where_clause.as_ref());
                let columns: Vec<Vec<(&'static str, StringField)>> =
                    (0..TAG_COUNT as u8).map(tag_columns).collect();
                // What the segment reads of a type is decided by the type, not the
                // graph: a type's key set is fixed by its kind (an annotation's is
                // not, and its properties read the record whatever the keys), so the
                // analysis runs once per type present anywhere and every graph reuses
                // it. `None` declines the segment.
                let mut by_tag: [Option<Option<Strategy>>; TAG_COUNT] = [None; TAG_COUNT];
                let mut strategies = Vec::with_capacity(ex.sources.len());
                let mut any_partitioned = false;
                let mut reads_graph = false;
                for (si, s) in ex.sources.iter().enumerate() {
                    let mut per_tag = [Strategy::Each; TAG_COUNT];
                    for &tag in tags {
                        let Some(&first) = s.graph.ids_by_tag(tag).first() else {
                            continue;
                        };
                        let strategy = match by_tag[tag as usize] {
                            Some(decided) => decided,
                            None => {
                                let keys: Vec<String> = ex
                                    .node_properties(NodeRef {
                                        source: si as SourceIdx,
                                        id: first,
                                    })
                                    .keys()
                                    .cloned()
                                    .collect();
                                let ctx = TagCtx {
                                    tag: Some(tag),
                                    keys: &keys,
                                    columns: &columns[tag as usize],
                                };
                                let mut scope = Scope {
                                    cross: ex.cross,
                                    nodes: vec![(variable.as_str(), &ctx)],
                                    rel: None,
                                    bound: Vec::new(),
                                };
                                let d = plan_deps(&mut scope);
                                reads_graph |= d.graph;
                                let decided = match d.level {
                                    // A type whose records the segment reads is a row
                                    // per node, as the pipeline would make anyway; the
                                    // segment is partitioned when at least one other
                                    // type is not.
                                    Level::Content => Some(Strategy::Each),
                                    Level::Column | Level::Keys => {
                                        // A DISTINCT projection without an aggregate
                                        // has no weight to apply: at column level its
                                        // partitions are its own output, enumerated in
                                        // full, where the row pipeline streams the same
                                        // tuples and stops at a LIMIT.
                                        if !final_aggregated && d.columns != 0 {
                                            None
                                        } else if d.level == Level::Keys {
                                            Some(Strategy::Keys(d.columns))
                                        } else {
                                            Some(Strategy::Columns(d.columns))
                                        }
                                    }
                                    _ => Some(Strategy::Whole),
                                };
                                by_tag[tag as usize] = Some(decided);
                                decided
                            }
                        };
                        let strategy = strategy?;
                        if strategy != Strategy::Each {
                            any_partitioned = true;
                        }
                        per_tag[tag as usize] = strategy;
                    }
                    strategies.push(per_tag);
                }
                if !any_partitioned {
                    return None;
                }
                Some((
                    PartitionPlan {
                        shape,
                        where_clause,
                        strategies,
                        scan,
                        merge: ex.cross && !reads_graph,
                    },
                    consumed,
                ))
            }
        }
    }

    /// The weighted rows of the match, WHERE applied, in the order the pipeline would
    /// first meet each partition.
    pub fn rows(&self, ex: &Executor, ev: &Evaluator) -> CypherResult<Vec<Row>> {
        match &self.shape {
            Shape::Node { variable, tags } => self.node_rows(ex, ev, variable, tags),
            Shape::Hop {
                a,
                r,
                b,
                tags_a,
                tags_b,
                types,
                direction,
            } => {
                let swept: Vec<CypherResult<Vec<EdgeSlot>>> = ex
                    .sources
                    .par_iter()
                    .map(|s| {
                        // Edges in the order the matcher meets them: its walk over the
                        // pattern's first node, then each node's edges in position order.
                        // Ascending type lists make that walk id order, so a slot's
                        // first edge is its smallest (id, position); a graph written
                        // out of id order pays for the walk itself, and the sweep ranks
                        // each node by it instead.
                        let walk: Option<(Vec<u32>, Vec<u32>)> =
                            if tags_a.iter().all(|&t| ascending(s.graph.ids_by_tag(t))) {
                                None
                            } else {
                                let mut rank = vec![u32::MAX; s.graph.node_capacity()];
                                let ids: Vec<u32> = MergedWalk::new(
                                    tags_a.iter().map(|&t| (t, s.graph.ids_by_tag(t))),
                                )
                                .map(|(_, id)| id)
                                .collect();
                                for (i, &id) in ids.iter().enumerate() {
                                    rank[id as usize] = i as u32;
                                }
                                Some((rank, ids))
                            };
                        let mut slots = sweep_edges(
                            ex,
                            &s.graph,
                            tags_a,
                            tags_b,
                            types,
                            *direction,
                            walk.as_ref().map(|(rank, _)| rank.as_slice()),
                        )?;
                        slots.sort_by_key(|slot| slot.first);
                        if let Some((_, ids)) = &walk {
                            for slot in &mut slots {
                                slot.first.0 = ids[slot.first.0 as usize];
                            }
                        }
                        Ok(slots)
                    })
                    .collect();
                let mut out = Vec::new();
                let mut shared: HashMap<usize, Option<usize>> = HashMap::new();
                for (si, slots) in swept.into_iter().enumerate() {
                    let source = si as SourceIdx;
                    let graph = &ex.sources[si].graph;
                    let v2 = graph.node_version < 3;
                    let csr = match direction {
                        Direction::Outgoing => &graph.forward,
                        _ => &graph.backward,
                    };
                    for slot in slots? {
                        if self.merge {
                            if let Some(joined) = shared.get(&slot.slot) {
                                if let Some(at) = *joined {
                                    weigh(ex, &mut out[at], source, slot.count);
                                }
                                continue;
                            }
                        }
                        let (id, pos) = slot.first;
                        let (targets, labels) = csr.neighbors(id as usize);
                        let other = targets[pos as usize];
                        let label = labels[pos as usize];
                        let (from, to) = match direction {
                            Direction::Outgoing => (id, other),
                            _ => (other, id),
                        };
                        let edge = Edge {
                            from,
                            to,
                            label,
                            v2,
                        };
                        // The three bindings share one graph: its provenance is
                        // recorded once for the row.
                        let mut row = Row::with_capacity(5);
                        if ex.cross {
                            add_provenance_id(&mut row, ex.sources[si].id.clone());
                        }
                        if let Some(a) = a {
                            row.insert(a.to_string(), Value::Node(NodeRef { source, id }));
                        }
                        if let Some(r) = r {
                            row.insert(r.to_string(), Value::Rel(EdgeRef { source, edge }));
                        }
                        if let Some(b) = b {
                            row.insert(b.to_string(), Value::Node(NodeRef { source, id: other }));
                        }
                        row.insert(INTERNAL_WEIGHT_KEY.to_string(), Value::Int(slot.count));
                        let kept = self.keep(ev, &row)?;
                        if self.merge {
                            shared.insert(slot.slot, kept.then_some(out.len()));
                        }
                        if kept {
                            out.push(row);
                        }
                    }
                }
                Ok(out)
            }
        }
    }

    fn keep(&self, ev: &Evaluator, row: &Row) -> CypherResult<bool> {
        match &self.where_clause {
            Some(w) => Ok(ev.eval(w, row)?.as_bool() == Some(true)),
            None => Ok(true),
        }
    }

    fn node_rows(
        &self,
        ex: &Executor,
        ev: &Evaluator,
        variable: &str,
        tags: &[u8],
    ) -> CypherResult<Vec<Row>> {
        let columns: Vec<Vec<(&'static str, StringField)>> =
            (0..TAG_COUNT as u8).map(tag_columns).collect();
        // Per source: (first id, weight) per partition, keyed by (tag, column ids).
        let mut parts: Vec<Parts> = (0..ex.sources.len()).map(|_| IndexMap::new()).collect();
        let note = |source: usize, tag: u8, id: u32, parts: &mut Vec<Parts>| {
            let graph: &Graph = &ex.sources[source].graph;
            let masked = |mask: u8| -> Vec<StrId> {
                let offset = graph.node_offset(id).unwrap_or(0);
                columns[tag as usize]
                    .iter()
                    .enumerate()
                    .filter(|(i, _)| mask & (1 << i) != 0)
                    .map(|(_, (_, f))| read_string_field(graph.nodedata(), offset, *f))
                    .collect()
            };
            let (ids, keys) = match self.strategies[source][tag as usize] {
                Strategy::Whole => (Vec::new(), Vec::new()),
                Strategy::Columns(mask) => (masked(mask), Vec::new()),
                // The key set is what `keys(n)` returns for the node, read the same way.
                Strategy::Keys(mask) => (
                    masked(mask),
                    ex.node_properties(NodeRef {
                        source: source as SourceIdx,
                        id,
                    })
                    .keys()
                    .cloned()
                    .collect(),
                ),
                // A node of its own: the id keeps it apart.
                Strategy::Each => (vec![id as StrId], Vec::new()),
            };
            parts[source]
                .entry((tag, ids, keys))
                .and_modify(|(_, n)| *n += 1)
                .or_insert((id, 1));
        };
        let verified = self.scan.is_some();
        match &self.scan {
            Some(plan) => {
                // The survivors of the pushdown, WHERE already applied to each.
                plan.run_ids(ex, ev, self.where_clause.as_ref(), &mut |n| {
                    if let Some(tag) = ex.sources[n.source as usize].graph.node_tag(n.id) {
                        note(n.source as usize, tag, n.id, &mut parts);
                    }
                    Ok(true)
                })?;
            }
            None => {
                let mut polled = 0u32;
                for (si, s) in ex.sources.iter().enumerate() {
                    // The walk the matcher makes over the same types, a type
                    // enumerated whole standing in with its first node only: the
                    // nodes after it change nothing the walk would meet first.
                    let lists = tags.iter().map(|&tag| {
                        let ids = s.graph.ids_by_tag(tag);
                        match self.strategies[si][tag as usize] {
                            Strategy::Whole => (tag, &ids[..ids.len().min(1)]),
                            _ => (tag, ids),
                        }
                    });
                    // Ascending lists make that walk id order, so each type can be
                    // swept on its own and the partitions sorted by first node
                    // afterwards, without the merge's comparison per node.
                    let by_id = lists.clone().all(|(_, ids)| ascending(ids));
                    let mut meet = |tag: u8, id: u32| -> CypherResult<()> {
                        polled = polled.wrapping_add(1);
                        if polled & 1023 == 0 {
                            ex.cancel.check()?;
                        }
                        match self.strategies[si][tag as usize] {
                            Strategy::Whole => {
                                parts[si].insert(
                                    (tag, Vec::new(), Vec::new()),
                                    (id, s.graph.count_by_tag(tag) as i64),
                                );
                            }
                            _ => note(si, tag, id, &mut parts),
                        }
                        Ok(())
                    };
                    if by_id {
                        for (tag, ids) in lists {
                            for &id in ids {
                                meet(tag, id)?;
                            }
                        }
                        parts[si].sort_by_cached_key(|_, (first, _)| *first);
                    } else {
                        for (tag, id) in MergedWalk::new(lists) {
                            meet(tag, id)?;
                        }
                    }
                }
            }
        }
        let mut out: Vec<Row> = Vec::new();
        // A whole type, or a key set, met again in a later graph: the row it joined,
        // or `None` when the WHERE dropped it.
        let mut shared: HashMap<(u8, Vec<String>), Option<usize>> = HashMap::new();
        for (si, per_source) in parts.into_iter().enumerate() {
            let source = si as SourceIdx;
            // Partitions in the order the walk first meets them: the pushdown's, or
            // the matcher's, which the group-by then meets in the same order.
            for ((tag, ids, keys), (id, weight)) in per_source {
                ex.tick()?;
                let mergeable = self.merge && ids.is_empty();
                if mergeable {
                    if let Some(joined) = shared.get(&(tag, keys.clone())) {
                        if let Some(at) = *joined {
                            weigh(ex, &mut out[at], source, weight);
                        }
                        continue;
                    }
                }
                let mut row = Row::with_capacity(3);
                bind(ex, &mut row, variable, Value::Node(NodeRef { source, id }));
                if weight != 1 {
                    row.insert(INTERNAL_WEIGHT_KEY.to_string(), Value::Int(weight));
                }
                let kept = verified || self.keep(ev, &row)?;
                if mergeable {
                    shared.insert((tag, keys), kept.then_some(out.len()));
                }
                if kept {
                    out.push(row);
                }
            }
        }
        Ok(out)
    }
}

/// Whether a type's id list is ascending, so the matcher's merged walk over such
/// lists is id order. One comparison per id, against the merge's one per list.
fn ascending(ids: &[u32]) -> bool {
    ids.windows(2).all(|w| w[0] < w[1])
}

/// The partitions of one source: (first id, weight), keyed by (tag, column ids,
/// property keys).
type Parts = IndexMap<(u8, Vec<StrId>, Vec<String>), (u32, i64)>;

/// Add a later graph's partition to the row of the same one: its weight and its
/// graph.
fn weigh(ex: &Executor, row: &mut Row, source: SourceIdx, weight: i64) {
    let total = row_weight(row) + weight;
    row.insert(INTERNAL_WEIGHT_KEY.to_string(), Value::Int(total));
    add_provenance_id(row, ex.sources[source as usize].id.clone());
}

/// Bind a value into a row, with its provenance across graphs.
fn bind(ex: &Executor, row: &mut Row, variable: &str, value: Value) {
    add_provenance(row, ex, &value);
    row.insert(variable.to_string(), value);
}

/// Whether every aggregate in a projected expression can apply a row weight.
fn weighable(e: &Expr) -> bool {
    let mut ok = true;
    walk(e, &mut |x| {
        if let Expr::FunctionCall {
            name,
            distinct,
            args,
        } = x
        {
            if is_aggregation_name(name) {
                let lower = name.to_ascii_lowercase();
                let allowed = if *distinct {
                    matches!(lower.as_str(), "count" | "collect")
                } else {
                    match lower.as_str() {
                        "count" | "min" | "max" => true,
                        // A weighted sum is the row-by-row fold to the bit only when
                        // every term is exact in floating point: bounded integers,
                        // whose partial sums neither round nor depend on their order.
                        "sum" | "avg" => args.len() == 1 && bounded_integer(&args[0]),
                        _ => false,
                    }
                };
                // Aggregates of the row itself (`collect(*)`) read what a
                // representative cannot stand for.
                if !allowed || (args.is_empty() && lower != "count") {
                    ok = false;
                }
            }
        }
    });
    ok
}

/// The largest integer literal a weighted `sum` or `avg` accepts.
const SMALL_LITERAL: i64 = 1 << 20;

/// Whether an expression can only produce a bounded integer or null: a `size` or
/// `length` (at most the 2^31 elements a stored string, list or map can have), the
/// sign of anything, a literal up to 2^20, or a sum, difference, remainder, `abs`,
/// `coalesce` or `CASE` of those. `sum` and `avg` over such values are weighed
/// exactly: each term is below 2^33 and the row pipeline adds the same integers, so
/// as long as the magnitudes total below 2^53 (which `aggregate_weighted` checks)
/// every partial sum of that fold is exact whatever its order, and the weighted
/// result is the same to the bit. A floating input (`sum(0.3)`, `avg(pi())`), a
/// product, a decoded property or a large literal would round differently, or
/// cannot be bounded, and is left to the pipeline.
fn bounded_integer(e: &Expr) -> bool {
    match e {
        Expr::Literal(Literal::Int(i)) => i.unsigned_abs() <= SMALL_LITERAL as u64,
        Expr::Literal(Literal::Null) => true,
        Expr::FunctionCall { name, args, .. } => match name.to_ascii_lowercase().as_str() {
            "size" | "length" => args.len() == 1,
            "sign" => true,
            "abs" | "coalesce" => !args.is_empty() && args.iter().all(bounded_integer),
            _ => false,
        },
        Expr::Unary { expr, .. } => bounded_integer(expr),
        Expr::Binary {
            op: BinOp::Add | BinOp::Sub | BinOp::Mod,
            left,
            right,
        } => bounded_integer(left) && bounded_integer(right),
        Expr::Case {
            whens, else_expr, ..
        } => {
            whens.iter().all(|(_, then)| bounded_integer(then))
                && else_expr.as_deref().is_none_or(bounded_integer)
        }
        _ => false,
    }
}

/// Visit every sub-expression.
fn walk(e: &Expr, f: &mut dyn FnMut(&Expr)) {
    f(e);
    match e {
        Expr::Literal(_) | Expr::Variable(_) | Expr::Parameter(_) | Expr::CountStar => {}
        Expr::Property { expr, .. }
        | Expr::Distinct(expr)
        | Expr::Unary { expr, .. }
        | Expr::IsNull(expr)
        | Expr::IsNotNull(expr)
        | Expr::Not(expr) => walk(expr, f),
        Expr::FunctionCall { args, .. } | Expr::ListLiteral(args) => {
            args.iter().for_each(|a| walk(a, f))
        }
        Expr::Binary { left, right, .. }
        | Expr::Comparison { left, right, .. }
        | Expr::StringOp { left, right, .. }
        | Expr::In { left, right } => {
            walk(left, f);
            walk(right, f);
        }
        Expr::And(a, b) | Expr::Or(a, b) | Expr::Xor(a, b) => {
            walk(a, f);
            walk(b, f);
        }
        Expr::Case {
            test,
            whens,
            else_expr,
        } => {
            if let Some(t) = test {
                walk(t, f);
            }
            for (w, t) in whens {
                walk(w, f);
                walk(t, f);
            }
            if let Some(e) = else_expr {
                walk(e, f);
            }
        }
        Expr::MapLiteral(entries) => entries.iter().for_each(|(_, e)| walk(e, f)),
        Expr::ListComprehension {
            list, filter, map, ..
        } => {
            walk(list, f);
            if let Some(x) = filter {
                walk(x, f);
            }
            if let Some(x) = map {
                walk(x, f);
            }
        }
        Expr::PredicateFunction {
            list, predicate, ..
        } => {
            walk(list, f);
            if let Some(p) = predicate {
                walk(p, f);
            }
        }
        Expr::Subscript { expr, index } => {
            walk(expr, f);
            walk(index, f);
        }
        Expr::Slice { expr, from, to } => {
            walk(expr, f);
            if let Some(x) = from {
                walk(x, f);
            }
            if let Some(x) = to {
                walk(x, f);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Edge partitions
// ---------------------------------------------------------------------------

/// A non-empty slot of a graph's edge table: its count and the earliest
/// (node, position) that opened it.
struct EdgeSlot {
    /// Its index in the (source tag, label, target tag) table.
    slot: usize,
    count: i64,
    first: (u32, u32),
}

/// Count one graph's edges by (source tag, label byte, target tag) in a flat table
/// of 16 x 256 x 16 slots, keeping each slot's earliest (node, position) so the
/// partitions can be met in the order the pipeline meets them. Whether a label
/// byte matches the pattern's types is decided once per byte; a target's tag is
/// read without decoding its record. With `rank`, the walk order of the pattern's
/// first node, the earliest edge is the first the matcher meets rather than the
/// smallest by id, and the slot names the node by its rank. Cancellation is polled on a local counter:
/// the executor's shared one is an atomic that every thread would bounce between
/// cores once per node.
fn sweep_edges(
    ex: &Executor,
    graph: &Graph,
    tags_a: &[u8],
    tags_b: &[u8],
    types: &[String],
    direction: Direction,
    rank: Option<&[u32]>,
) -> CypherResult<Vec<EdgeSlot>> {
    let csr = match direction {
        Direction::Outgoing => &graph.forward,
        _ => &graph.backward,
    };
    let v2 = graph.node_version < 3;
    let mut type_ok: [u8; 256] = [0; 256];
    // The slot table is two zero-initialised arrays, which the allocator hands out
    // as untouched pages, and the list of slots met: a graph pays for the slots it
    // has, not for the 65,536 it could have. A slot's first edge is packed as
    // (node + 1, position) so zero means none yet.
    const SLOTS: usize = TAG_COUNT * 256 * TAG_COUNT;
    let mut counts: Vec<i64> = vec![0; SLOTS];
    let mut firsts: Vec<u64> = vec![0; SLOTS];
    let mut met: Vec<usize> = Vec::new();
    // A target's tag, for the targets the pattern admits, in one byte per node id:
    // the type index is read once, in order, instead of two dependent reads of the
    // node table per edge.
    const NO_TAG: u8 = u8::MAX;
    let mut tag_of = vec![NO_TAG; graph.node_capacity()];
    for &tag_b in tags_b {
        for &id in graph.ids_by_tag(tag_b) {
            tag_of[id as usize] = tag_b;
        }
    }
    let mut polled = 0u32;
    for &tag_a in tags_a {
        for &id in graph.ids_by_tag(tag_a) {
            polled = polled.wrapping_add(1);
            if polled & 1023 == 0 {
                ex.cancel.check()?;
            }
            let (targets, labels) = csr.neighbors(id as usize);
            for (pos, (&other, &label)) in targets.iter().zip(labels).enumerate() {
                let ok = &mut type_ok[label as usize];
                if *ok == 0 {
                    let probe = Edge {
                        from: id,
                        to: other,
                        label,
                        v2,
                    };
                    *ok = if rel_type_matches(types, &probe) {
                        1
                    } else {
                        2
                    };
                }
                if *ok == 2 {
                    continue;
                }
                let tag_b = tag_of[other as usize];
                if tag_b == NO_TAG {
                    continue;
                }
                let slot = (tag_a as usize * 256 + label as usize) * TAG_COUNT + tag_b as usize;
                if counts[slot] == 0 {
                    met.push(slot);
                }
                counts[slot] += 1;
                let node = rank.map_or(id, |r| r[id as usize]);
                let first = ((node as u64 + 1) << 32) | pos as u64;
                if firsts[slot] == 0 || first < firsts[slot] {
                    firsts[slot] = first;
                }
            }
        }
    }
    Ok(met
        .into_iter()
        .map(|slot| EdgeSlot {
            slot,
            count: counts[slot],
            first: ((firsts[slot] >> 32) as u32 - 1, firsts[slot] as u32),
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::super::pipeline::INTERNAL_PROVENANCE_KEY;
    use super::*;
    use crate::engine::{QueryResult, Source};
    use graphite_storage::node::{TAG_CALL_SITE_NODE, TAG_LOCAL_VARIABLE};
    use std::sync::Arc;

    fn expr(text: &str) -> Expr {
        let clauses = crate::parser::parse(&format!("RETURN {text}")).unwrap();
        match clauses.into_iter().next().unwrap() {
            Clause::Return {
                items: Some(items), ..
            } => items.into_iter().next().unwrap().expr,
            other => panic!("unexpected {other:?}"),
        }
    }

    /// A call-site context: four raw columns, a handful of decoded keys.
    fn call_site_ctx<'a>(
        keys: &'a [String],
        columns: &'a [(&'static str, StringField)],
    ) -> TagCtx<'a> {
        TagCtx {
            tag: Some(TAG_CALL_SITE_NODE),
            keys,
            columns,
        }
    }

    fn keys() -> Vec<String> {
        [
            "id",
            "type",
            "caller_class",
            "caller_name",
            "callee_class",
            "callee_name",
            "line",
            "graphId",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect()
    }

    fn classify(text: &str, cross: bool, ctx: &TagCtx) -> Dep {
        let scope = Scope {
            cross,
            nodes: vec![("n", ctx)],
            rel: Some("r"),
            bound: vec![("bound".to_string(), Dep::TAG)],
        };
        dep(&expr(text), &scope)
    }

    #[test]
    fn a_dependence_joins_to_the_finer_level_and_unions_columns() {
        assert_eq!(Dep::CONST.join(Dep::SOURCE), Dep::SOURCE);
        let joined = Dep::TAG.join(Dep::SOURCE);
        assert_eq!(joined.level, Level::Tag);
        assert!(joined.graph && !Dep::TAG.join(Dep::CONTENT).graph);
        assert_eq!(Dep::column(0).join(Dep::column(2)).columns, 0b101);
        assert_eq!(Dep::column(0).join(Dep::CONTENT).level, Level::Content);
        assert!(Level::Const < Level::Source && Level::Column < Level::Content);
    }

    #[test]
    fn expressions_that_read_nothing_of_the_node_are_constant() {
        let keys = keys();
        let cols = tag_columns(TAG_CALL_SITE_NODE);
        let ctx = call_site_ctx(&keys, &cols);
        for e in [
            "1", "'x'", "$p", "count(*)", "count(n)", "count(r)", "[1, 2]", "{a: 1}",
        ] {
            assert_eq!(classify(e, true, &ctx), Dep::CONST, "{e}");
        }
        // Counting distinct nodes reads their identity.
        assert_eq!(classify("count(DISTINCT n)", true, &ctx), Dep::CONTENT);
    }

    #[test]
    fn the_graph_decides_graph_ids_only_across_graphs() {
        let keys = keys();
        let cols = tag_columns(TAG_CALL_SITE_NODE);
        let ctx = call_site_ctx(&keys, &cols);
        for e in ["n.graphId", "graphId(n)", "r.graphId", "graphId(r)"] {
            assert_eq!(classify(e, true, &ctx), Dep::SOURCE, "{e}");
            assert_eq!(classify(e, false, &ctx), Dep::TAG, "{e}");
        }
        // Element ids embed the graph id across graphs, so they are per node there.
        assert_eq!(classify("n.elementId", true, &ctx), Dep::CONTENT);
        assert_eq!(classify("n.qualifiedId", true, &ctx), Dep::CONTENT);
    }

    #[test]
    fn the_type_decides_labels_keys_relationship_types_and_absent_properties() {
        let keys = keys();
        let cols = tag_columns(TAG_CALL_SITE_NODE);
        let ctx = call_site_ctx(&keys, &cols);
        for e in [
            "labels(n)",
            "keys(n)",
            "type(r)",
            "r.kind",
            "r.anything",
            "n.absent",
            "size(labels(n))",
            "toLower(type(r))",
            "labels(n)[0]",
            "labels(n)[0..1]",
            "head(keys(n))",
            "[x IN labels(n) WHERE x <> 'a' | toUpper(x)]",
            "any(x IN keys(n) WHERE x = 'id')",
            "CASE WHEN size(labels(n)) > 1 THEN 'multi' ELSE 'single' END",
            "CASE type(r) WHEN 'CALL' THEN 1 END",
            "NOT (labels(n) IS NULL)",
            "keys(n) IS NOT NULL",
            "-size(keys(n))",
            "bound",
            "labels(n) + [bound]",
            "'x' IN keys(n)",
            "type(r) STARTS WITH 'D'",
            "DISTINCT labels(n)",
        ] {
            assert_eq!(classify(e, true, &ctx), Dep::TAG, "{e}");
        }
    }

    #[test]
    fn raw_string_columns_are_read_by_bit() {
        let keys = keys();
        let cols = tag_columns(TAG_CALL_SITE_NODE);
        let ctx = call_site_ctx(&keys, &cols);
        let callee_class = cols.iter().position(|(p, _)| *p == "callee_class").unwrap();
        let caller_class = cols.iter().position(|(p, _)| *p == "caller_class").unwrap();
        assert_eq!(
            classify("n.callee_class", true, &ctx),
            Dep::column(callee_class)
        );
        assert_eq!(
            classify("n['callee_class']", true, &ctx),
            Dep::column(callee_class)
        );
        let both = classify(
            "n.callee_class STARTS WITH 'a' AND NOT n.caller_class STARTS WITH 'a'",
            true,
            &ctx,
        );
        assert_eq!(both.level, Level::Column);
        assert_eq!(both.columns, (1 << callee_class) | (1 << caller_class));
        // A pure function of a column is at the column's level; the graph id joins in.
        assert_eq!(
            classify(
                "split(replace(n.callee_class, 'a', ''), '.')[0]",
                true,
                &ctx
            ),
            Dep::column(callee_class)
        );
        let with_graph = classify("[n.graphId, n.callee_class]", true, &ctx);
        assert_eq!(with_graph.level, Level::Column);
        assert_eq!(with_graph.columns, Dep::column(callee_class).columns);
        assert!(with_graph.graph);
        // A comprehension over a column-level list stays column-level.
        assert_eq!(
            classify("[x IN split(n.callee_class, '.') | x]", true, &ctx),
            Dep::column(callee_class)
        );
    }

    #[test]
    fn decoded_properties_and_opaque_functions_read_the_record() {
        let keys = keys();
        let cols = tag_columns(TAG_CALL_SITE_NODE);
        let ctx = call_site_ctx(&keys, &cols);
        for e in [
            "n.line",
            "n.id",
            "n",
            "id(n)",
            "elementId(n)",
            "properties(n)",
            "unknown(1)",
            "unbound",
            // Volatile: a representative's value stands for nobody else's.
            "rand()",
            "timestamp()",
            "randomUUID()",
            "size(labels(n)) + n.line",
            "CASE n.line WHEN 1 THEN 'a' END",
            "[x IN labels(n) | n.line]",
            "all(x IN keys(n) WHERE n[x] IS NULL)",
            "n[bound]",
            "keys(n)[n.line]",
            "labels(n)[0..n.line]",
        ] {
            assert_eq!(classify(e, true, &ctx), Dep::CONTENT, "{e}");
        }
    }

    #[test]
    fn annotation_keys_and_hop_ends_are_not_decided_by_the_type_alone() {
        let keys = keys();
        let cols = tag_columns(TAG_ANNOTATION_NODE);
        let annotation = TagCtx {
            tag: Some(TAG_ANNOTATION_NODE),
            keys: &keys,
            columns: &cols,
        };
        assert_eq!(classify("keys(n)", true, &annotation), Dep::KEYS);
        assert_eq!(classify("size(keys(n))", true, &annotation), Dep::KEYS);
        assert_eq!(classify("n.absent", true, &annotation), Dep::CONTENT);
        assert_eq!(
            classify("[keys(n), n.name]", true, &annotation).level,
            Level::Keys
        );
        assert_eq!(classify("labels(n)", true, &annotation), Dep::TAG);
        let name = cols.iter().position(|(p, _)| *p == "name").unwrap();
        assert_eq!(classify("n.name", true, &annotation), Dep::column(name));

        let end = TagCtx {
            tag: None,
            keys: &[],
            columns: &[],
        };
        assert_eq!(classify("keys(n)", true, &end), Dep::CONTENT);
        assert_eq!(classify("n.name", true, &end), Dep::CONTENT);
        assert_eq!(classify("labels(n)", true, &end), Dep::TAG);
        assert_eq!(classify("n.graphId", true, &end), Dep::SOURCE);
    }

    #[test]
    fn local_variable_columns_are_name_and_type() {
        let cols = tag_columns(TAG_LOCAL_VARIABLE);
        assert_eq!(
            cols.iter().map(|(p, _)| *p).collect::<Vec<_>>(),
            vec!["name", "type"]
        );
        assert!(tag_columns(TAG_RETURN_NODE_FOR_TEST).is_empty());
    }
    const TAG_RETURN_NODE_FOR_TEST: u8 = graphite_storage::node::TAG_RETURN_NODE;

    #[test]
    fn only_integer_valued_sums_and_averages_are_weighed() {
        for e in [
            "sum(size(keys(n)))",
            "avg(size(labels(n)) + 1)",
            "sum(CASE WHEN size(labels(n)) > 1 THEN 1 ELSE 0 END)",
            "avg(coalesce(size(n.name), 0))",
            "sum(-abs(length(keys(n))))",
            "sum(sign(size(keys(n)) % 3))",
            "sum(1048576)",
            "count(n.line)",
            "min(n.score)",
            "max(0.5)",
            "count(DISTINCT n.score)",
            "collect(DISTINCT 0.5)",
        ] {
            assert!(weighable(&expr(e)), "{e}");
        }
        for e in [
            "sum(0.3)",
            "avg(pi())",
            "sum(n.line)",
            "avg(toFloat(size(keys(n))))",
            "sum(size(keys(n)) / 2)",
            // Products and unbounded conversions or literals can carry the fold past
            // the exact range.
            "sum(size(labels(n)) * 2)",
            "avg(toInteger(n.line))",
            "sum(1048577)",
            "sum(9007199254740892)",
            "sum(CASE WHEN size(labels(n)) > 1 THEN 1 END + 0.5)",
            "avg(coalesce())",
            "collect(n)",
            "percentileDisc(size(keys(n)), 0.5)",
            "stDev(size(keys(n)))",
        ] {
            assert!(!weighable(&expr(e)), "{e}");
        }
    }

    /// A type enumerated whole stands in the walk with its first node only, and the
    /// walk still meets every type, and every node of the other types, in the same
    /// order as the full walk the matcher makes: whatever order the types' lists are in.
    #[test]
    fn a_type_walked_whole_meets_the_walk_where_its_first_node_does() {
        let a = [5u32, 1, 8];
        let whole = [4u32, 0, 9];
        let b = [3u32, 7];
        let full: Vec<(u8, u32)> =
            MergedWalk::new([(0u8, &a[..]), (1, &whole[..]), (2, &b[..])]).collect();
        let truncated: Vec<(u8, u32)> =
            MergedWalk::new([(0u8, &a[..]), (1, &whole[..1]), (2, &b[..])]).collect();
        let first_met = |walk: &[(u8, u32)]| {
            let mut seen = Vec::new();
            for (t, _) in walk {
                if !seen.contains(t) {
                    seen.push(*t);
                }
            }
            seen
        };
        assert_eq!(first_met(&full), [2, 1, 0]);
        assert_eq!(first_met(&truncated), first_met(&full));
        let others = |walk: &[(u8, u32)]| -> Vec<(u8, u32)> {
            walk.iter().filter(|(t, _)| *t != 1).cloned().collect()
        };
        assert_eq!(others(&truncated), others(&full));
    }

    #[test]
    fn weights_default_to_one_and_are_read_from_the_hidden_key() {
        let mut row = Row::new();
        assert_eq!(row_weight(&row), 1);
        row.insert(INTERNAL_WEIGHT_KEY.to_string(), Value::Int(7));
        assert_eq!(row_weight(&row), 7);
        row.insert(INTERNAL_WEIGHT_KEY.to_string(), Value::Str("x".into()));
        assert_eq!(row_weight(&row), 1);
        assert!(super::super::pipeline::is_internal_key(INTERNAL_WEIGHT_KEY));
    }

    /// The WHERE of an intermediate WITH is part of what the partition must
    /// determine: the same segment partitions without it and not with a volatile one.
    #[test]
    fn a_with_predicate_the_partition_cannot_determine_declines_the_segment() {
        let Some(graph) = fixture() else { return };
        let ex = executor(&graph, &["a"]);
        let plain =
            crate::parser::parse("MATCH (n) WITH labels(n) AS l RETURN l, count(*) AS c").unwrap();
        assert!(PartitionPlan::build(&ex, &plain).is_some());
        let volatile = crate::parser::parse(
            "MATCH (n) WITH labels(n) AS l WHERE rand() < 0.5 RETURN l, count(*) AS c",
        )
        .unwrap();
        assert!(PartitionPlan::build(&ex, &volatile).is_none());
        let determined = crate::parser::parse(
            "MATCH (n) WITH labels(n) AS l WHERE size(l) > 1 RETURN l, count(*) AS c",
        )
        .unwrap();
        assert!(PartitionPlan::build(&ex, &determined).is_some());
    }

    #[test]
    fn nothing_is_partitioned_without_a_source() {
        let ex = Executor::new(vec![], true);
        let clauses = crate::parser::parse("MATCH (n) RETURN labels(n), count(*)").unwrap();
        assert!(PartitionPlan::build(&ex, &clauses).is_none());
    }

    fn fixture() -> Option<Arc<Graph>> {
        let Some(dir) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            eprintln!("GRAPHITE_INDEX_FIXTURE unset; skipping");
            return None;
        };
        Some(Arc::new(Graph::load(std::path::Path::new(&dir)).unwrap()))
    }

    fn executor(graph: &Arc<Graph>, ids: &[&str]) -> Executor {
        let sources: Vec<Source> = ids
            .iter()
            .map(|id| Source {
                id: Arc::from(*id),
                graph: graph.clone(),
            })
            .collect();
        Executor::new(sources, ids.len() > 1)
    }

    fn render(r: &QueryResult) -> Vec<String> {
        let mut out = vec![format!("columns={:?}", r.columns)];
        for row in &r.rows {
            let cells: Vec<String> = r
                .columns
                .iter()
                .map(|c| format!("{:?}", row.get(c)))
                .collect();
            out.push(format!(
                "{} | {:?}",
                cells.join(" | "),
                QueryResult::graph_ids(row)
            ));
        }
        out
    }

    /// Segments the partition answers: every phrasing an agent exploring a graph
    /// might reach for, and compositions no shape list would name.
    const PARTITIONED: &[&str] = &[
        "MATCH (n) RETURN labels(n) AS labels, count(*) AS c ORDER BY c DESC LIMIT 40",
        "MATCH (n) RETURN labels(n), count(*)",
        "MATCH (n) RETURN DISTINCT labels(n) AS labels",
        "MATCH (n) RETURN DISTINCT labels(n) AS labels LIMIT 3",
        "MATCH (n:Constant) RETURN labels(n) AS l, count(n) AS c ORDER BY c DESC, l ASC",
        "MATCH (n) RETURN labels(n) AS labels, keys(n) AS keys, count(*) AS c ORDER BY c DESC LIMIT 40",
        "MATCH (n) RETURN DISTINCT keys(n) AS keys",
        // An annotation's keys depend on its values: one partition per key set.
        "MATCH (n:AnnotationNode) RETURN keys(n) AS k, count(n) AS c ORDER BY c DESC",
        "MATCH (n:AnnotationNode) UNWIND keys(n) AS k RETURN DISTINCT k",
        "MATCH (n:AnnotationNode) RETURN n.name AS name, size(keys(n)) AS k, count(*) AS c ORDER BY c DESC, name LIMIT 10",
        "MATCH (n) RETURN size(labels(n)) AS s, count(*) AS c ORDER BY c DESC",
        "MATCH (n) RETURN head(keys(n)) AS k, count(*) AS c ORDER BY c DESC LIMIT 10",
        "MATCH (n) RETURN CASE WHEN size(labels(n)) > 1 THEN 'multi' ELSE 'single' END AS kind, count(*) AS c ORDER BY c DESC",
        "MATCH (n) WITH labels(n) AS l WHERE size(l) > 1 RETURN l, count(*) AS c ORDER BY c DESC",
        "MATCH (n) WITH DISTINCT labels(n) AS l RETURN l ORDER BY l ASC",
        "MATCH (n) WITH DISTINCT labels(n) AS l RETURN count(*) AS c",
        "MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50",
        "MATCH (n) UNWIND keys(n) AS k RETURN labels(n) AS l, k, count(*) AS c ORDER BY c DESC LIMIT 30",
        "MATCH (n) UNWIND keys(n) AS k WITH k WHERE k STARTS WITH 'c' RETURN k, count(*) AS c ORDER BY c DESC",
        "MATCH (n) UNWIND labels(n) AS l RETURN l, count(*) AS c ORDER BY c DESC",
        "MATCH (n) RETURN n.graphId AS g, count(*) AS c",
        "MATCH (n) RETURN DISTINCT n.graphId AS g",
        "MATCH (n) RETURN count(DISTINCT n.graphId) AS d",
        "MATCH (n:CallSiteNode) RETURN n.graphId AS g, labels(n) AS l, count(n) AS c ORDER BY g DESC LIMIT 5",
        "MATCH (n) RETURN n.graphId AS g, size(keys(n)) AS k, count(*) AS c ORDER BY g ASC, c DESC LIMIT 20",
        "MATCH (n) RETURN labels(n) AS l, sum(size(keys(n))) AS s, avg(size(keys(n))) AS a, min(size(keys(n))) AS mn, max(size(keys(n))) AS mx ORDER BY s DESC",
        "MATCH (n) RETURN sum(size(labels(n)) + 1) AS s, avg(CASE WHEN size(labels(n)) > 1 THEN 1 ELSE 0 END) AS a",
        "MATCH (n) RETURN labels(n) AS l, min(0.5) AS mn, max(pi()) AS mx, sum(1) AS s ORDER BY l ASC",
        "MATCH (n) RETURN labels(n) AS l, count(DISTINCT keys(n)) AS d ORDER BY d DESC, l ASC",
        "MATCH (n) RETURN labels(n) AS l, collect(DISTINCT size(keys(n))) AS d ORDER BY l ASC",
        "MATCH (n) RETURN count(DISTINCT labels(n)) AS d",
        "MATCH (n) RETURN 1 AS one, count(*) AS c",
        // Each segment of a union is partitioned on its own.
        "MATCH (n) RETURN labels(n) AS l, count(*) AS c UNION MATCH (n) RETURN labels(n) AS l, count(*) AS c",
        "MATCH (n) RETURN n.nonexistent AS t, count(*) AS c",
        "MATCH (n) RETURN n.type AS t, count(*) AS c ORDER BY c DESC LIMIT 10",
        // A column of some types, a decoded property of others, absent from the rest.
        "MATCH (n) RETURN labels(n) AS l, n.name AS name, count(*) AS c ORDER BY c DESC LIMIT 5",
        "MATCH (n {type: 'CallSiteNode'}) RETURN n.callee_class AS cc, count(*) AS c ORDER BY c DESC LIMIT 10",
        "MATCH (n {type: 'CallSiteNode'}) WHERE n.callee_class STARTS WITH 'java.util.' AND NOT n.caller_class STARTS WITH 'java.util.' WITH n.graphId AS graphId, split(replace(n.callee_class, 'java.util.', ''), '.')[0] AS provider, count(*) AS calls RETURN graphId, provider, calls ORDER BY graphId ASC, calls DESC LIMIT 160",
        "MATCH (n) WHERE n.callee_class STARTS WITH 'java.util.' RETURN labels(n) AS l, count(*) AS c ORDER BY c DESC",
        "MATCH (n:CallSiteNode) WHERE n.callee_name = 'get' RETURN n.callee_class AS c, count(*) AS k ORDER BY k DESC, c LIMIT 15",
        "MATCH (n:LocalVariable) RETURN n.type AS t, count(*) AS c ORDER BY c DESC, t LIMIT 10",
        "MATCH (n:LocalVariable) WHERE n.name STARTS WITH 'a' RETURN n.name AS t, count(*) AS c ORDER BY c DESC, t LIMIT 10",
        "MATCH ()-[r]->() RETURN type(r) AS t, count(*) AS c ORDER BY c DESC",
        "MATCH ()-[r]->() RETURN DISTINCT type(r) AS t",
        "MATCH ()-[r]->() RETURN count(r) AS c",
        "MATCH ()-[r:DATAFLOW]->() RETURN count(r) AS c",
        "MATCH ()-[r]->() RETURN toLower(type(r)) AS t, count(*) AS c ORDER BY c DESC",
        "MATCH ()-[r]->() RETURN r.graphId AS g, type(r) AS t, count(*) AS c ORDER BY c DESC LIMIT 5",
        "MATCH (a)-[r]->(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC LIMIT 40",
        "MATCH (a)<-[r]-(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC LIMIT 10",
        "MATCH (a:CallSiteNode)-[r:DATAFLOW]->(b:LocalVariable) RETURN type(r) AS t, count(*) AS c",
        "MATCH (a:CallSiteNode)-[r]->(b) RETURN DISTINCT labels(b) AS b",
        "MATCH (a)-[r]->(b) WHERE type(r) = 'DATAFLOW' RETURN labels(a) AS a, labels(b) AS b, count(*) AS c ORDER BY c DESC LIMIT 10",
        "MATCH (a)-[r]->(b) WHERE labels(a) = labels(b) RETURN type(r) AS t, count(*) AS c ORDER BY c DESC",
        "MATCH (a)-[r]->(b) RETURN a.graphId = b.graphId AS same, count(*) AS c",
        "MATCH (a)-[r]->(b) RETURN labels(a) + labels(b) AS ab, count(*) AS c ORDER BY c DESC LIMIT 5",
        "MATCH (a)-[r]->(b) RETURN r.nonexistent AS x, count(*) AS c",
        "MATCH (a)-[r:NOSUCH]->(b) RETURN type(r) AS t, count(*) AS c",
        "MATCH (a)-[r]->(b) RETURN max(size(labels(a))) AS m, min(size(labels(b))) AS n, avg(size(labels(a))) AS a, sum(size(labels(b))) AS s",
        "MATCH (a)-[r]->(b) RETURN labels(a) AS l, count(*) AS c, count(DISTINCT type(r)) AS t ORDER BY c DESC LIMIT 5",
    ];

    /// Segments left to the general pipeline: a record read under a pushdown, a
    /// node carried into the aggregation, an undirected or variable-length hop, an
    /// aggregate that cannot be weighted, a page between the match and the aggregation.
    const NOT_PARTITIONED: &[&str] = &[
        // A DISTINCT projection at column level is its own partition: the row pipeline
        // streams the same tuples and stops at the LIMIT.
        "MATCH (n) WHERE n.value CONTAINS 'java' RETURN DISTINCT n.value ORDER BY n.value LIMIT 5",
        "MATCH (n:CallSite) WHERE n.callee_class CONTAINS 'a' RETURN DISTINCT n.callee_class ORDER BY n.callee_class DESC LIMIT 5",
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class, n.callee_name LIMIT 200",
        "MATCH (n) WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'java' RETURN DISTINCT n.caller_class, n.callee_class LIMIT 200",
        // An unknown label matches nothing: the general pipeline is already instant.
        "MATCH (n:NoSuchLabel) RETURN labels(n) AS l, count(*) AS c",
        "MATCH (a:NoSuch)-[r]->(b) RETURN type(r) AS t, count(*) AS c",
        "MATCH (n) WITH labels(n) AS l, n RETURN l, count(n) AS c ORDER BY c DESC LIMIT 5",
        // The WITH's WHERE reads what its items do not: a volatile predicate, or a
        // decoded property of a node carried through.
        "MATCH (n) WITH labels(n) AS l WHERE rand() < 2 RETURN l, count(*) AS c",
        "MATCH (n:CallSiteNode) WITH n, labels(n) AS l WHERE n.line > 0 RETURN l, count(*) AS c",
        "MATCH (n:CallSiteNode) WITH n, labels(n) AS l WHERE n.callee_name STARTS WITH 'get' RETURN l, count(*) AS c",
        "MATCH (n) RETURN n LIMIT 2",
        "MATCH (n:CallSiteNode) WHERE n.callee_name = 'get' RETURN n.line AS l, count(*) AS c",
        "MATCH (a)-[r]-(b) RETURN type(r) AS t, count(*) AS c ORDER BY c DESC LIMIT 10",
        "MATCH (a)-[r*1..2]->(b) RETURN count(*) AS c",
        "MATCH (a)-[r]->(b) RETURN a.name AS x, count(*) AS c LIMIT 3",
        "MATCH (a)-[r]->(b) RETURN percentileDisc(size(labels(a)), 0.5) AS p",
        // A floating sum or average rounds in the order the rows fold.
        "MATCH (n) RETURN avg(pi()) AS a",
        "MATCH (n) RETURN sum(0.3) AS s",
        "MATCH (n) RETURN labels(n) AS l, sum(toFloat(size(keys(n)))) AS s",
        "MATCH (a)-[r]->(b) RETURN avg(size(labels(a)) / 2) AS a",
        "MATCH (n) RETURN sum(9007199254740892) AS s",
        "MATCH (n) RETURN avg(size(keys(n)) * 2) AS a",
        "MATCH (a)-[r]->(b) RETURN stDev(size(labels(a))) AS p",
        "MATCH (n) WITH labels(n) AS l LIMIT 3 RETURN l, count(*) AS c",
        "MATCH (n) WITH labels(n) AS l ORDER BY l RETURN l, count(*) AS c",
        "MATCH (n), (m) RETURN labels(n) AS l, count(*) AS c",
        "MATCH p = (a)-[r]->(b) RETURN count(*) AS c",
        "MATCH (a)-[r {kind: 'x'}]->(b) RETURN count(*) AS c",
        "MATCH (n {type: n.type}) RETURN count(*) AS c",
        "OPTIONAL MATCH (n) RETURN labels(n) AS l, count(*) AS c",
        "MATCH (n) RETURN labels(n) AS l",
        "MATCH (n) RETURN labels(n) AS l, keys(n) AS k LIMIT 3",
        "MATCH (n) UNWIND keys(n) AS k RETURN k LIMIT 5",
        "MATCH (n) WITH * RETURN labels(n) AS l, count(*) AS c",
        "MATCH (n) RETURN *",
        "MATCH (n)-[r]->(n) RETURN count(*) AS c",
        "MATCH (r)-[r]->(b) RETURN count(*) AS c",
    ];

    /// Against a real graph (`GRAPHITE_INDEX_FIXTURE`, the core jar in CI), as two
    /// sources in cross-graph mode and as one source: every partitioned segment
    /// produces the rows, columns and provenance the row-per-node pipeline produces,
    /// and the plan builds exactly for the segments it claims.
    #[test]
    fn partitioned_segments_answer_as_the_row_per_node_pipeline_does() {
        let Some(graph) = fixture() else { return };
        for ids in [&["a", "b"][..], &["a"][..]] {
            let ex = executor(&graph, ids);
            let plain = executor(&graph, ids).without_partitioning();
            for q in PARTITIONED {
                let clauses = crate::parser::parse(q).unwrap();
                assert!(
                    PartitionPlan::build(&ex, &clauses).is_some(),
                    "should partition: {q}"
                );
                let fast = ex
                    .execute(q, Some(1000))
                    .unwrap_or_else(|e| panic!("partitioned {q}: {e:?}"));
                let slow = plain
                    .execute(q, Some(1000))
                    .unwrap_or_else(|e| panic!("row-per-node {q}: {e:?}"));
                assert_eq!(render(&fast), render(&slow), "{ids:?}: {q}");
            }
            for q in NOT_PARTITIONED {
                let clauses = crate::parser::parse(q).unwrap();
                assert!(
                    PartitionPlan::build(&ex, &clauses).is_none(),
                    "should not partition: {q}"
                );
            }
        }
    }

    /// A copy of the fixture whose every type list is written back to front, so the
    /// matcher's walk over a type is not id order, as a frontend that persists a
    /// type out of id order makes it.
    fn reversed_fixture() -> Option<(Arc<Graph>, std::path::PathBuf)> {
        let dir = std::env::var_os("GRAPHITE_INDEX_FIXTURE")?;
        let src = std::path::PathBuf::from(dir);
        if !src.is_dir() {
            eprintln!("GRAPHITE_INDEX_FIXTURE is not a directory; skipping");
            return None;
        }
        let dst = std::env::temp_dir().join(format!(
            "graphite-reversed-fixture-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dst).unwrap();
        for entry in std::fs::read_dir(&src).unwrap() {
            let entry = entry.unwrap();
            if entry.file_type().unwrap().is_file() {
                std::fs::copy(entry.path(), dst.join(entry.file_name())).unwrap();
            }
        }
        // `graph.typeindex`: a header, then 13-byte entries (tag, big-endian count,
        // big-endian offset) into big-endian id lists.
        let path = dst.join("graph.typeindex");
        let mut data = std::fs::read(&path).unwrap();
        let be32 = |d: &[u8], at: usize| u32::from_be_bytes(d[at..at + 4].try_into().unwrap());
        let entries = be32(&data, 4) as usize;
        for i in 0..entries {
            let p = 8 + i * 13;
            let count = be32(&data, p + 1) as usize;
            let off = u64::from_be_bytes(data[p + 5..p + 13].try_into().unwrap()) as usize;
            let ids: Vec<u32> = (0..count).map(|j| be32(&data, off + j * 4)).collect();
            for (j, id) in ids.iter().rev().enumerate() {
                data[off + j * 4..off + j * 4 + 4].copy_from_slice(&id.to_be_bytes());
            }
        }
        std::fs::write(&path, data).unwrap();
        let graph = Arc::new(Graph::load(&dst).unwrap());
        Some((graph, dst))
    }

    /// On a graph whose types are not in id order, the partition still meets each
    /// group where the matcher first does: the walk over the pattern's first node
    /// ranks the nodes, the sweep keeps each hop slot's earliest edge by that rank,
    /// and so the tie order of a stable `ORDER BY` is the row-per-node pipeline's,
    /// for node and hop segments in either direction.
    #[test]
    fn a_graph_written_out_of_id_order_meets_the_groups_where_the_matcher_does() {
        let Some((graph, dir)) = reversed_fixture() else {
            return;
        };
        assert!(
            !ascending(graph.ids_by_tag(TAG_LOCAL_VARIABLE)),
            "the copy should be out of id order"
        );
        let hops = [
            "MATCH (a)<-[r]-(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC",
            "MATCH (a)-[r]->(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC",
            "MATCH (a)<-[r]-(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c",
            "MATCH (a)-[r]->(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c",
            "MATCH (a)-[r]->(b) WHERE type(r) = 'DATAFLOW' RETURN labels(a) AS a, labels(b) AS b, count(*) AS c ORDER BY c DESC",
        ];
        for ids in [&["a", "b"][..], &["a"][..]] {
            let ex = executor(&graph, ids);
            let plain = executor(&graph, ids).without_partitioning();
            for q in PARTITIONED.iter().chain(hops.iter()) {
                let fast = ex
                    .execute(q, Some(1000))
                    .unwrap_or_else(|e| panic!("partitioned {q}: {e:?}"));
                let slow = plain
                    .execute(q, Some(1000))
                    .unwrap_or_else(|e| panic!("row-per-node {q}: {e:?}"));
                assert_eq!(render(&fast), render(&slow), "{ids:?}: {q}");
            }
        }
        std::fs::remove_dir_all(dir).ok();
    }

    /// Across graphs, a type met in every graph is one row weighing them all and
    /// carrying every graph, unless the segment reads the graph; a hop slot the same.
    #[test]
    fn partitions_alike_in_every_graph_are_one_row_across_graphs() {
        let Some(graph) = fixture() else { return };
        let ex = executor(&graph, &["a", "b"]);
        let ev = Evaluator::new(&ex, &ex.params);
        let types = (0..TAG_COUNT as u8)
            .filter(|&t| graph.count_by_tag(t) > 0)
            .count();
        let rows_of = |q: &str| {
            let clauses = crate::parser::parse(q).unwrap();
            let (plan, _) = PartitionPlan::build(&ex, &clauses).unwrap();
            plan.rows(&ex, &ev).unwrap()
        };
        let both = |row: &Row| match row.get(INTERNAL_PROVENANCE_KEY) {
            Some(Value::List(l)) => l.len() == 2,
            _ => false,
        };

        let rows = rows_of("MATCH (n) RETURN labels(n) AS l, count(*) AS c");
        assert_eq!(rows.len(), types);
        assert!(rows.iter().all(both));
        let total: i64 = rows.iter().map(row_weight).sum();
        assert_eq!(total, 2 * graph.node_count() as i64);

        // The graph id keeps each graph's row apart.
        let rows = rows_of("MATCH (n) RETURN n.graphId AS g, labels(n) AS l, count(*) AS c");
        assert_eq!(rows.len(), 2 * types);
        assert!(rows.iter().all(|r| !both(r)));

        // A hop slot met in both graphs is one row too, and a WHERE the slot fails
        // drops it from both.
        let single = executor(&graph, &["a"]);
        let ev1 = Evaluator::new(&single, &single.params);
        let one = |q: &str| {
            let clauses = crate::parser::parse(q).unwrap();
            let (plan, _) = PartitionPlan::build(&single, &clauses).unwrap();
            plan.rows(&single, &ev1).unwrap()
        };
        for q in [
            "MATCH (a)-[r]->(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c",
            "MATCH (a)-[r]->(b) WHERE type(r) = 'DATAFLOW' RETURN labels(a) AS a, labels(b) AS b, count(*) AS c",
        ] {
            let rows = rows_of(q);
            let alone = one(q);
            assert_eq!(rows.len(), alone.len(), "{q}");
            assert!(rows.iter().all(both), "{q}");
            let total: i64 = rows.iter().map(row_weight).sum();
            let expected: i64 = alone.iter().map(row_weight).sum();
            assert_eq!(total, 2 * expected, "{q}");
        }
    }

    /// The population of a type is the weight of its row: the schema histogram from
    /// the partition matches the type index directly.
    #[test]
    fn a_whole_type_row_weighs_its_population() {
        let Some(graph) = fixture() else { return };
        let ex = executor(&graph, &["a"]);
        let r = ex
            .execute("MATCH (n:CallSiteNode) RETURN count(*) AS c", None)
            .unwrap();
        let expected = graph.count_by_tag(TAG_CALL_SITE_NODE) as i64;
        assert!(expected > 0);
        assert_eq!(
            r.rows[0].get("c").map(|v| format!("{v:?}")),
            Some(format!("{:?}", Value::Int(expected)))
        );

        let clauses =
            crate::parser::parse("MATCH (n:CallSiteNode) RETURN labels(n), count(*)").unwrap();
        let (plan, consumed) = PartitionPlan::build(&ex, &clauses).unwrap();
        assert_eq!(consumed, 1);
        assert_eq!(
            plan.strategies[0][TAG_CALL_SITE_NODE as usize],
            Strategy::Whole
        );
        let ev = Evaluator::new(&ex, &ex.params);
        let rows = plan.rows(&ex, &ev).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(row_weight(&rows[0]), expected);
        assert!(matches!(rows[0].get("n"), Some(Value::Node(_))));
    }

    /// An annotation's key set partitions the type: one row per distinct key list,
    /// weighing as many nodes as share it.
    #[test]
    fn annotations_partition_by_their_key_set() {
        let Some(graph) = fixture() else { return };
        let ex = executor(&graph, &["a"]);
        let q = "MATCH (n:AnnotationNode) RETURN keys(n) AS k, count(*) AS c";
        let clauses = crate::parser::parse(q).unwrap();
        let (plan, _) = PartitionPlan::build(&ex, &clauses).unwrap();
        assert_eq!(
            plan.strategies[0][TAG_ANNOTATION_NODE as usize],
            Strategy::Keys(0)
        );
        let ev = Evaluator::new(&ex, &ex.params);
        let rows = plan.rows(&ex, &ev).unwrap();
        let total: i64 = rows.iter().map(row_weight).sum();
        assert_eq!(total, graph.count_by_tag(TAG_ANNOTATION_NODE) as i64);
        let distinct = ex
            .execute(
                "MATCH (n:AnnotationNode) RETURN DISTINCT keys(n) AS k",
                None,
            )
            .unwrap();
        // How far the type compresses is the graph's business: a graph whose
        // annotations all differ has one row per annotation, and is right to.
        assert_eq!(rows.len(), distinct.rows.len());
    }

    /// A column-level segment enumerates one row per distinct tuple of the columns
    /// it reads, and the WHERE consumes a following clause.
    #[test]
    fn a_column_level_segment_is_one_row_per_distinct_tuple() {
        let Some(graph) = fixture() else { return };
        let ex = executor(&graph, &["a"]);
        let q = "MATCH (n:CallSiteNode) WHERE n.callee_name = 'get' RETURN n.callee_class AS c, count(*) AS k";
        let clauses = crate::parser::parse(q).unwrap();
        let (plan, consumed) = PartitionPlan::build(&ex, &clauses).unwrap();
        assert_eq!(consumed, 2);
        assert!(matches!(
            plan.strategies[0][TAG_CALL_SITE_NODE as usize],
            Strategy::Columns(_)
        ));
        let ev = Evaluator::new(&ex, &ex.params);
        let rows = plan.rows(&ex, &ev).unwrap();
        let distinct = ex
            .execute(
                "MATCH (n:CallSiteNode) WHERE n.callee_name = 'get' RETURN DISTINCT n.callee_class AS c",
                None,
            )
            .unwrap();
        assert_eq!(rows.len(), distinct.rows.len());
        let total: i64 = rows.iter().map(row_weight).sum();
        let counted = ex
            .execute(
                "MATCH (n:CallSiteNode) WHERE n.callee_name = 'get' RETURN count(*) AS c",
                None,
            )
            .unwrap();
        assert_eq!(
            counted.rows[0].get("c").map(|v| format!("{v:?}")),
            Some(format!("{:?}", Value::Int(total)))
        );
    }
}
