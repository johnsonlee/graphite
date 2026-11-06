//! Static Cypher binding and expression validation. Diagnostics originate here,
//! before graph access, even when a pattern would otherwise produce zero rows.
use crate::{ast::*, CypherError, CypherResult};
use std::collections::{BTreeMap, HashSet};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Unknown,
    Null,
    Bool,
    Int,
    Float,
    String,
    List,
    Map,
    Node,
    Rel,
    Path,
    Temporal,
}
type Scope = BTreeMap<String, Kind>;
fn fail(detail: &'static str, message: impl Into<String>) -> CypherError {
    CypherError::Syntax {
        position: 0,
        message: message.into(),
        detail,
    }
}
fn aggregate(expr: &Expr) -> bool {
    match expr {
        Expr::CountStar => true,
        Expr::FunctionCall { name, .. } if crate::eval::is_aggregation_name(name) => true,
        _ => children(expr).into_iter().any(aggregate),
    }
}
fn children(expr: &Expr) -> Vec<&Expr> {
    match expr {
        Expr::LabelPredicate { expr, .. }
        | Expr::Property { expr, .. }
        | Expr::Distinct(expr)
        | Expr::Unary { expr, .. }
        | Expr::IsNull(expr)
        | Expr::IsNotNull(expr)
        | Expr::Not(expr) => vec![expr],
        Expr::FunctionCall { args, .. } | Expr::ListLiteral(args) => args.iter().collect(),
        Expr::Binary { left, right, .. }
        | Expr::Comparison { left, right, .. }
        | Expr::StringOp { left, right, .. }
        | Expr::In { left, right }
        | Expr::And(left, right)
        | Expr::Or(left, right)
        | Expr::Xor(left, right) => vec![left, right],
        Expr::MapLiteral(items) => items.iter().map(|(_, e)| e).collect(),
        Expr::Case {
            test,
            whens,
            else_expr,
        } => test
            .iter()
            .map(|e| e.as_ref())
            .chain(whens.iter().flat_map(|(a, b)| [a, b]))
            .chain(else_expr.iter().map(|e| e.as_ref()))
            .collect(),
        Expr::ListComprehension {
            list, filter, map, ..
        } => std::iter::once(list.as_ref())
            .chain(filter.iter().map(|e| e.as_ref()))
            .chain(map.iter().map(|e| e.as_ref()))
            .collect(),
        Expr::PredicateFunction {
            list, predicate, ..
        } => std::iter::once(list.as_ref())
            .chain(predicate.iter().map(|e| e.as_ref()))
            .collect(),
        Expr::Subscript { expr, index } => vec![expr, index],
        Expr::Slice { expr, from, to } => std::iter::once(expr.as_ref())
            .chain(from.iter().map(|e| e.as_ref()))
            .chain(to.iter().map(|e| e.as_ref()))
            .collect(),
        _ => vec![],
    }
}
fn infer(expr: &Expr, scope: &Scope) -> Kind {
    match expr {
        Expr::Literal(value) => match value {
            Literal::Null => Kind::Null,
            Literal::Bool(_) => Kind::Bool,
            Literal::Int(_) => Kind::Int,
            Literal::Float(_) => Kind::Float,
            Literal::Str(_) => Kind::String,
        },
        Expr::Variable(name) => scope.get(name).copied().unwrap_or(Kind::Unknown),
        Expr::Parameter(_) => Kind::Unknown,
        Expr::ListLiteral(_) | Expr::ListComprehension { .. } => Kind::List,
        Expr::MapLiteral(_) => Kind::Map,
        Expr::CountStar => Kind::Int,
        Expr::Distinct(expr) | Expr::Unary { expr, .. } => infer(expr, scope),
        Expr::LabelPredicate { .. } | Expr::PatternPredicate(_) | Expr::ExistsSubquery(_) => {
            Kind::Bool
        }
        Expr::PatternComprehension { .. } => Kind::List,
        Expr::Not(_)
        | Expr::IsNull(_)
        | Expr::IsNotNull(_)
        | Expr::PredicateFunction { .. }
        | Expr::Comparison { .. }
        | Expr::And(..)
        | Expr::Or(..)
        | Expr::Xor(..)
        | Expr::In { .. }
        | Expr::StringOp { .. } => Kind::Bool,
        Expr::FunctionCall { name, .. } => match name.to_ascii_lowercase().as_str() {
            "startnode" | "endnode" => Kind::Node,
            "count" | "size" | "length" | "id" | "timestamp" | "tointeger" | "toint" => Kind::Int,
            "tostring" | "tolower" | "toupper" | "tolowercase" | "touppercase" | "trim"
            | "ltrim" | "rtrim" | "left" | "right" | "replace" | "substring" | "type"
            | "elementid" | "qualifiedid" | "graphid" => Kind::String,
            "tofloat" | "avg" | "percentilecont" | "percentiledisc" | "stdev" | "stdevp" => {
                Kind::Float
            }
            "collect" | "range" | "nodes" | "relationships" | "labels" | "keys" | "tail"
            | "split" => Kind::List,
            "properties" => Kind::Map,
            "toboolean" | "exists" => Kind::Bool,
            n if n.starts_with("date")
                || n.starts_with("time")
                || n.starts_with("localtime")
                || n.starts_with("localdate")
                || n.starts_with("duration") =>
            {
                Kind::Temporal
            }
            _ => Kind::Unknown,
        },
        Expr::Binary { left, right, .. } => {
            let a = infer(left, scope);
            let b = infer(right, scope);
            if a == b {
                a
            } else if [a, b].contains(&Kind::Float)
                && [a, b].iter().all(|k| matches!(k, Kind::Int | Kind::Float))
            {
                Kind::Float
            } else {
                Kind::Unknown
            }
        }
        _ => Kind::Unknown,
    }
}

// Only infer an element type when every possible literal element agrees.
// Empty, heterogeneous and parameter-dependent lists stay unknown.
fn element_kind(list: &Expr, scope: &Scope) -> Kind {
    let Expr::ListLiteral(items) = list else {
        return Kind::Unknown;
    };
    let mut kinds = items
        .iter()
        .map(|item| infer(item, scope))
        .filter(|kind| *kind != Kind::Null);
    let Some(first) = kinds.next() else {
        return Kind::Unknown;
    };
    if kinds.all(|kind| kind == first) {
        first
    } else {
        Kind::Unknown
    }
}

fn signature(name: &str) -> Option<(usize, usize)> {
    let name = name.to_ascii_lowercase();
    if [
        "date",
        "localdate",
        "localtime",
        "time",
        "localdatetime",
        "datetime",
    ]
    .contains(&name.as_str())
    {
        return Some((0, 1));
    }
    for kind in ["date", "localtime", "time", "localdatetime", "datetime"] {
        if ["transaction", "statement", "realtime"]
            .iter()
            .any(|clock| name == format!("{kind}.{clock}"))
        {
            return Some((0, 1));
        }
        if name == format!("{kind}.truncate") {
            return Some((2, 3));
        }
    }
    match name.as_str() {
        "timestamp" | "rand" | "pi" | "e" => Some((0, 0)),
        "coalesce" => Some((1, usize::MAX)),
        "range" | "substring" => Some((2, 3)),
        "replace" => Some((3, 3)),
        "split" | "left" | "right" | "atan2" | "percentilecont" | "percentiledisc"
        | "datetime.fromepoch" | "duration.between" | "duration.inmonths" | "duration.indays"
        | "duration.inseconds" => Some((2, 2)),
        "startnode"
        | "endnode"
        | "id"
        | "elementid"
        | "qualifiedid"
        | "graphid"
        | "exists"
        | "tointeger"
        | "toint"
        | "tofloat"
        | "toboolean"
        | "tostring"
        | "properties"
        | "keys"
        | "labels"
        | "type"
        | "nodes"
        | "relationships"
        | "tolower"
        | "tolowercase"
        | "toupper"
        | "touppercase"
        | "trim"
        | "ltrim"
        | "rtrim"
        | "size"
        | "length"
        | "reverse"
        | "head"
        | "tail"
        | "last"
        | "abs"
        | "ceil"
        | "floor"
        | "round"
        | "sqrt"
        | "exp"
        | "log"
        | "log10"
        | "sin"
        | "cos"
        | "tan"
        | "cot"
        | "asin"
        | "acos"
        | "atan"
        | "degrees"
        | "radians"
        | "sign"
        | "duration"
        | "datetime.fromepochmillis"
        | "count"
        | "sum"
        | "avg"
        | "min"
        | "max"
        | "collect"
        | "stdev"
        | "stdevp" => Some((1, 1)),
        _ => None,
    }
}
fn validate_function(name: &str, args: &[Expr], scope: &Scope) -> CypherResult<()> {
    let (min, max) = signature(name)
        .ok_or_else(|| fail("UnknownFunction", format!("Unknown function: {name}")))?;
    if args.len() < min || args.len() > max {
        return Err(fail(
            "InvalidNumberOfArguments",
            format!(
                "Function {name} expects {min}..{max} arguments, got {}",
                args.len()
            ),
        ));
    }
    let lower = name.to_ascii_lowercase();
    let allowed: &[Kind] = match lower.as_str() {
        "labels" => &[Kind::Node],
        "type" => &[Kind::Rel],
        "nodes" | "relationships" => &[Kind::Path],
        "properties" => &[Kind::Node, Kind::Rel, Kind::Map],
        "keys" => &[Kind::Node, Kind::Rel, Kind::Map],
        "tolower" | "tolowercase" | "toupper" | "touppercase" | "trim" | "ltrim" | "rtrim"
        | "left" | "right" | "replace" | "split" | "substring" => &[Kind::String],
        "head" | "last" | "tail" => &[Kind::List],
        "size" => &[Kind::String, Kind::List],
        "length" => &[Kind::Path, Kind::String, Kind::List],
        "abs" | "ceil" | "floor" | "round" | "sqrt" | "exp" | "log" | "log10" | "sin" | "cos"
        | "tan" | "cot" | "asin" | "acos" | "atan" | "degrees" | "radians" | "sign" | "sum"
        | "avg" | "stdev" | "stdevp" => &[Kind::Int, Kind::Float],
        _ => &[],
    };
    if let Some(first) = args.first() {
        let kind = infer(first, scope);
        if !allowed.is_empty()
            && !matches!(kind, Kind::Unknown | Kind::Null)
            && !matches!(first, Expr::PatternPredicate(_))
            && !allowed.contains(&kind)
        {
            return Err(fail(
                "InvalidArgumentType",
                format!("Invalid argument type for {name}"),
            ));
        }
    }
    Ok(())
}

fn expression(
    expr: &Expr,
    scope: &Scope,
    allow_aggregate: bool,
    in_aggregate: bool,
    known: &[Expr],
) -> CypherResult<()> {
    expression_context(expr, scope, allow_aggregate, in_aggregate, known, false)
}
fn predicate(
    expr: &Expr,
    scope: &Scope,
    allow_aggregate: bool,
    in_aggregate: bool,
    known: &[Expr],
) -> CypherResult<()> {
    expression_context(expr, scope, allow_aggregate, in_aggregate, known, true)?;
    if !matches!(infer(expr, scope), Kind::Unknown | Kind::Null | Kind::Bool) {
        return Err(fail(
            "InvalidArgumentType",
            "Predicate requires a boolean value",
        ));
    }
    Ok(())
}
fn expression_context(
    expr: &Expr,
    scope: &Scope,
    allow_aggregate: bool,
    in_aggregate: bool,
    known: &[Expr],
    predicate_position: bool,
) -> CypherResult<()> {
    if known.contains(expr) {
        return Ok(());
    }
    match expr {
        Expr::LabelPredicate { expr, .. } => {
            expression(expr, scope, allow_aggregate, in_aggregate, known)?;
            if !matches!(
                infer(expr, scope),
                Kind::Unknown | Kind::Null | Kind::Node | Kind::Rel
            ) {
                return Err(fail(
                    "InvalidArgumentType",
                    "Label predicate requires a node or relationship",
                ));
            }
            return Ok(());
        }
        Expr::PatternPredicate(pattern) => {
            if !predicate_position {
                return Err(fail(
                    "UnexpectedSyntax",
                    "Graph patterns are not allowed in value expressions",
                ));
            }
            for name in pattern.variables() {
                if !scope.contains_key(name) {
                    return Err(fail(
                        "UndefinedVariable",
                        format!("Variable {name} is not defined"),
                    ));
                }
            }
            let mut local = scope.clone();
            patterns(
                std::slice::from_ref(pattern.as_ref()),
                &mut local,
                false,
                false,
            )?;
            return Ok(());
        }
        Expr::PatternComprehension {
            pattern,
            filter,
            map,
        } => {
            let mut local = scope.clone();
            patterns(
                std::slice::from_ref(pattern.as_ref()),
                &mut local,
                false,
                false,
            )?;
            if let Some(filter) = filter {
                predicate(filter, &local, false, in_aggregate, known)?;
            }
            expression(map, &local, false, in_aggregate, known)?;
            return Ok(());
        }
        Expr::ExistsSubquery(clauses) => {
            if clauses.iter().any(|clause| {
                matches!(
                    clause,
                    Clause::Create(_)
                        | Clause::Merge { .. }
                        | Clause::Set(_)
                        | Clause::Remove(_)
                        | Clause::Delete { .. }
                )
            }) {
                return Err(fail(
                    "InvalidClauseComposition",
                    "Existential subqueries cannot update the graph",
                ));
            }
            resolve_scoped(clauses, None, scope.clone())?;
            return Ok(());
        }
        _ => {}
    }

    match expr {
        Expr::And(a, b) | Expr::Or(a, b) | Expr::Xor(a, b) => {
            for operand in [a.as_ref(), b.as_ref()] {
                if !matches!(
                    infer(operand, scope),
                    Kind::Unknown | Kind::Null | Kind::Bool
                ) {
                    return Err(fail(
                        "InvalidArgumentType",
                        "Boolean operators require boolean operands",
                    ));
                }
            }
        }
        Expr::Not(a) => {
            if !matches!(infer(a, scope), Kind::Unknown | Kind::Null | Kind::Bool) {
                return Err(fail(
                    "InvalidArgumentType",
                    "NOT requires a boolean operand",
                ));
            }
        }
        Expr::Property { expr: base, key } => {
            let kind = infer(base, scope);
            if !(matches!(
                kind,
                Kind::Unknown | Kind::Null | Kind::Map | Kind::Node | Kind::Rel | Kind::Temporal
            ) || (kind == Kind::Path && ["length", "graphId"].contains(&key.as_str())))
            {
                return Err(CypherError::compilation(
                    if kind == Kind::Path {
                        "SyntaxError"
                    } else {
                        "TypeError"
                    },
                    "InvalidArgumentType",
                    "Property access requires a node, relationship, map or temporal value",
                ));
            }
        }
        Expr::Binary {
            op: BinOp::Mod | BinOp::Pow,
            left,
            right,
        } => {
            for operand in [left.as_ref(), right.as_ref()] {
                if !matches!(
                    infer(operand, scope),
                    Kind::Unknown | Kind::Null | Kind::Int | Kind::Float
                ) {
                    return Err(fail(
                        "InvalidArgumentType",
                        "Numeric operator requires numeric operands",
                    ));
                }
            }
        }
        Expr::In { right, .. } => {
            if !matches!(infer(right, scope), Kind::Unknown | Kind::Null | Kind::List) {
                return Err(fail("InvalidArgumentType", "IN requires a list operand"));
            }
        }
        _ => {}
    }
    match expr {
        Expr::And(a, b) | Expr::Or(a, b) | Expr::Xor(a, b) => {
            predicate(a, scope, allow_aggregate, in_aggregate, known)?;
            predicate(b, scope, allow_aggregate, in_aggregate, known)?;
            return Ok(());
        }
        Expr::Not(expr) => return predicate(expr, scope, allow_aggregate, in_aggregate, known),
        Expr::Case {
            test,
            whens,
            else_expr,
        } => {
            if let Some(test) = test {
                expression(test, scope, allow_aggregate, in_aggregate, known)?;
            }
            for (when, then) in whens {
                if test.is_none() {
                    predicate(when, scope, allow_aggregate, in_aggregate, known)?
                } else {
                    expression(when, scope, allow_aggregate, in_aggregate, known)?
                };
                expression_context(
                    then,
                    scope,
                    allow_aggregate,
                    in_aggregate,
                    known,
                    predicate_position,
                )?;
            }
            if let Some(other) = else_expr {
                expression_context(
                    other,
                    scope,
                    allow_aggregate,
                    in_aggregate,
                    known,
                    predicate_position,
                )?;
            }
            return Ok(());
        }
        _ => {}
    }
    match expr {
        Expr::Variable(name) => {
            if !scope.contains_key(name) {
                return Err(fail(
                    "UndefinedVariable",
                    format!("Variable {name} is not defined"),
                ));
            }
        }
        Expr::CountStar => {
            if !allow_aggregate {
                return Err(fail(
                    "InvalidAggregation",
                    "Aggregation is not allowed in this expression",
                ));
            }
            if in_aggregate {
                return Err(fail(
                    "NestedAggregation",
                    "An aggregate cannot contain another aggregate",
                ));
            }
        }
        Expr::FunctionCall { name, args, .. } => {
            validate_function(name, args, scope)?;
            let is_aggregate = crate::eval::is_aggregation_name(name);
            if is_aggregate && !allow_aggregate {
                return Err(fail(
                    "InvalidAggregation",
                    format!("Aggregation {name} is not allowed here"),
                ));
            }
            if is_aggregate && in_aggregate {
                return Err(fail(
                    "NestedAggregation",
                    "An aggregate cannot contain another aggregate",
                ));
            }
            if name.eq_ignore_ascii_case("exists")
                && args
                    .first()
                    .is_some_and(|arg| matches!(arg, Expr::PatternPredicate(_)))
            {
                return predicate(&args[0], scope, allow_aggregate, in_aggregate, known);
            }
            for arg in args {
                if is_aggregate && nondeterministic(arg) {
                    return Err(fail(
                        "NonConstantExpression",
                        "Aggregate arguments cannot contain nondeterministic expressions",
                    ));
                }
                expression(
                    arg,
                    scope,
                    allow_aggregate,
                    in_aggregate || is_aggregate,
                    known,
                )?;
            }
            return Ok(());
        }
        Expr::ListComprehension {
            variable,
            list,
            filter,
            map,
        } => {
            expression(list, scope, allow_aggregate, in_aggregate, known)?;
            let mut local = scope.clone();
            local.insert(variable.clone(), element_kind(list, scope));
            if let Some(filter) = filter {
                predicate(filter, &local, false, in_aggregate, known)?;
            }
            if let Some(map) = map {
                expression(map, &local, false, in_aggregate, known)?;
            }
            return Ok(());
        }
        Expr::PredicateFunction {
            variable,
            list,
            predicate,
            ..
        } => {
            expression(list, scope, allow_aggregate, in_aggregate, known)?;
            let mut local = scope.clone();
            local.insert(variable.clone(), element_kind(list, scope));
            if let Some(predicate) = predicate {
                self::predicate(predicate, &local, allow_aggregate, in_aggregate, known)?;
            }
            return Ok(());
        }
        _ => {}
    }
    for child in children(expr) {
        expression(child, scope, allow_aggregate, in_aggregate, known)?;
    }
    Ok(())
}
fn grouping(expr: &Expr, keys: &[Expr], inside_aggregate: bool) -> CypherResult<()> {
    if inside_aggregate || keys.contains(expr) {
        return Ok(());
    }
    match expr {
        Expr::ListComprehension {
            variable,
            list,
            filter,
            map,
        } => {
            grouping(list, keys, false)?;
            let mut local = keys.to_vec();
            local.push(Expr::Variable(variable.clone()));
            for child in filter.iter().chain(map.iter()) {
                grouping(child, &local, false)?;
            }
            return Ok(());
        }
        Expr::PredicateFunction {
            variable,
            list,
            predicate,
            ..
        } => {
            grouping(list, keys, false)?;
            let mut local = keys.to_vec();
            local.push(Expr::Variable(variable.clone()));
            if let Some(predicate) = predicate {
                grouping(predicate, &local, false)?;
            }
            return Ok(());
        }
        _ => {}
    }
    match expr {
        Expr::CountStar => return Ok(()),
        Expr::FunctionCall { name, .. } if crate::eval::is_aggregation_name(name) => return Ok(()),
        Expr::Variable(_) | Expr::Property { .. } => {
            return Err(fail(
                "AmbiguousAggregationExpression",
                "Expression contains an implicit grouping key",
            ))
        }
        _ => {}
    }
    for child in children(expr) {
        grouping(child, keys, false)?;
    }
    Ok(())
}
fn bind(scope: &mut Scope, name: &str, kind: Kind, path: bool) -> CypherResult<()> {
    if let Some(previous) = scope.get(name) {
        if path {
            return Err(fail(
                "VariableAlreadyBound",
                format!("Path variable {name} is already bound"),
            ));
        }
        if !matches!(previous, Kind::Unknown | Kind::Null) && *previous != kind {
            return Err(fail(
                "VariableTypeConflict",
                format!("Variable {name} has an incompatible binding"),
            ));
        }
    } else {
        scope.insert(name.to_owned(), kind);
    }
    Ok(())
}
fn patterns(
    patterns: &[Pattern],
    scope: &mut Scope,
    create: bool,
    directed: bool,
) -> CypherResult<()> {
    let mut rels = HashSet::new();
    // Bind every pattern before checking property expressions, so references to
    // another pattern in this MATCH observe the same complete lexical scope.
    for pattern in patterns {
        let mut introduced_paths = HashSet::new();
        if let Some(path) = &pattern.path_variable {
            bind(scope, path, Kind::Path, true)?;
            introduced_paths.insert(path.clone());
        }
        for node in &pattern.nodes {
            if let Some(name) = &node.variable {
                if introduced_paths.contains(name) {
                    return Err(fail(
                        "VariableAlreadyBound",
                        format!("Pattern path and node share binding {name}"),
                    ));
                }
                if create
                    && scope.contains_key(name)
                    && (!node.labels.is_empty()
                        || node.properties_present
                        || !node.properties.is_empty()
                        || pattern.rels.is_empty())
                {
                    return Err(fail(
                        "VariableAlreadyBound",
                        format!("Created node {name} is already bound"),
                    ));
                }
                bind(scope, name, Kind::Node, false)?
            }
        }
        for rel in &pattern.rels {
            if let Some(name) = &rel.variable {
                if introduced_paths.contains(name) {
                    return Err(fail(
                        "VariableAlreadyBound",
                        format!("Pattern path and relationship share binding {name}"),
                    ));
                }
                if create && scope.contains_key(name) {
                    return Err(fail(
                        "VariableAlreadyBound",
                        format!("Created relationship {name} is already bound"),
                    ));
                }
                if !rels.insert(name.clone()) {
                    return Err(fail(
                        "RelationshipUniquenessViolation",
                        format!("Relationship {name} is repeated in one MATCH"),
                    ));
                }
                bind(
                    scope,
                    name,
                    if rel.variable_length {
                        Kind::List
                    } else {
                        Kind::Rel
                    },
                    false,
                )?;
            }
            if create {
                if rel.variable_length {
                    return Err(fail(
                        "CreatingVarLength",
                        "Cannot create a variable-length relationship",
                    ));
                }
                if rel.types.len() != 1 {
                    return Err(fail(
                        "NoSingleRelationshipType",
                        "A created relationship requires exactly one type",
                    ));
                }
                if directed && rel.direction == Direction::Both {
                    return Err(fail(
                        "RequiresDirectedRelationship",
                        "A created relationship requires a direction",
                    ));
                }
            }
        }
    }
    for pattern in patterns {
        for expr in pattern
            .nodes
            .iter()
            .flat_map(|n| n.properties.iter().map(|(_, e)| e))
            .chain(
                pattern
                    .rels
                    .iter()
                    .flat_map(|r| r.properties.iter().map(|(_, e)| e)),
            )
        {
            expression(expr, scope, false, false, &[])?;
        }
    }
    Ok(())
}
fn projection(
    items: &mut Option<Vec<ReturnItem>>,
    scope: &Scope,
    _with: bool,
) -> CypherResult<(Scope, bool, Vec<Expr>, Vec<Expr>)> {
    if items.is_none() {
        if scope.is_empty() && !_with {
            return Err(fail(
                "NoVariablesInScope",
                "Star projection has no visible variables",
            ));
        }
        *items = Some(
            scope
                .keys()
                .map(|name| ReturnItem {
                    expr: Expr::Variable(name.clone()),
                    alias: None,
                    source_text: None,
                })
                .collect(),
        );
    }
    let items = items.as_ref().unwrap();
    let mut next = Scope::new();
    let mut columns = HashSet::new();
    let aggregated = items.iter().any(|item| aggregate(&item.expr));
    let keys: Vec<_> = items
        .iter()
        .filter(|item| {
            !aggregate(&item.expr) && matches!(item.expr, Expr::Variable(_) | Expr::Property { .. })
        })
        .map(|item| item.expr.clone())
        .collect();
    for item in items {
        expression(&item.expr, scope, true, false, &[])?;
        if aggregate(&item.expr) {
            grouping(&item.expr, &keys, false)?;
        }
        let column = item.column_name();
        if !columns.insert(column.clone()) {
            return Err(fail(
                "ColumnNameConflict",
                format!("Duplicate projected column {column}"),
            ));
        }
        next.insert(column, infer(&item.expr, scope));
    }
    let expressions = items.iter().map(|item| item.expr.clone()).collect();
    Ok((next, aggregated, keys, expressions))
}
fn nondeterministic(expr: &Expr) -> bool {
    matches!(expr,Expr::FunctionCall{name,..}if name.eq_ignore_ascii_case("rand"))
        || children(expr).into_iter().any(nondeterministic)
}
/// Conservative scope proof for fused plans. Local comprehension variables are
/// scoped only inside their filter/projection; future expression kinds decline.
pub fn references_only(expr: &Expr, allowed: &HashSet<String>) -> bool {
    match expr {
        Expr::Variable(name) => allowed.contains(name),
        Expr::Literal(_) | Expr::Parameter(_) | Expr::CountStar => true,
        Expr::ListComprehension {
            variable,
            list,
            filter,
            map,
        } => {
            if !references_only(list, allowed) {
                return false;
            }
            let mut local = allowed.clone();
            local.insert(variable.clone());
            filter
                .iter()
                .chain(map.iter())
                .all(|child| references_only(child, &local))
        }
        Expr::PredicateFunction {
            variable,
            list,
            predicate,
            ..
        } => {
            if !references_only(list, allowed) {
                return false;
            }
            let mut local = allowed.clone();
            local.insert(variable.clone());
            predicate.iter().all(|child| references_only(child, &local))
        }
        _ => {
            matches!(
                expr,
                Expr::Property { .. }
                    | Expr::Distinct(_)
                    | Expr::Unary { .. }
                    | Expr::IsNull(_)
                    | Expr::IsNotNull(_)
                    | Expr::Not(_)
                    | Expr::FunctionCall { .. }
                    | Expr::ListLiteral(_)
                    | Expr::MapLiteral(_)
                    | Expr::Case { .. }
                    | Expr::Binary { .. }
                    | Expr::Comparison { .. }
                    | Expr::StringOp { .. }
                    | Expr::In { .. }
                    | Expr::And(..)
                    | Expr::Or(..)
                    | Expr::Xor(..)
                    | Expr::Subscript { .. }
                    | Expr::Slice { .. }
            ) && children(expr)
                .into_iter()
                .all(|child| references_only(child, allowed))
        }
    }
}

fn has_variable(expr: &Expr) -> bool {
    matches!(expr, Expr::Variable(_)) || children(expr).into_iter().any(has_variable)
}
fn limit(expr: &Expr, scope: &Scope) -> CypherResult<()> {
    expression(expr, scope, false, false, &[])?;
    if has_variable(expr) {
        return Err(fail(
            "NonConstantExpression",
            "SKIP/LIMIT must be independent of row variables",
        ));
    }
    let kind = infer(expr, scope);
    if !matches!(kind, Kind::Unknown | Kind::Int) {
        return Err(fail(
            "InvalidArgumentType",
            "SKIP/LIMIT requires an integer",
        ));
    }
    if matches!(expr,Expr::Unary{negate:true,expr}if matches!(expr.as_ref(),Expr::Literal(Literal::Int(n)) if *n>0))
        || matches!(expr,Expr::Literal(Literal::Int(n))if *n<0)
    {
        return Err(fail(
            "NegativeIntegerArgument",
            "SKIP/LIMIT requires a non-negative integer",
        ));
    }
    Ok(())
}

fn validate_set(items: &[SetItem], scope: &Scope) -> CypherResult<()> {
    for item in items {
        let (variable, value) = match item {
            SetItem::Property {
                variable, value, ..
            }
            | SetItem::Map {
                variable, value, ..
            } => (variable, Some(value)),
            SetItem::Labels { variable, .. } => (variable, None),
        };
        expression(&Expr::Variable(variable.clone()), scope, false, false, &[])?;
        if let Some(value) = value {
            expression(value, scope, false, false, &[])?;
        }
    }
    Ok(())
}

/// Validate the complete query and resolve each star from its compile-time scope.
/// Expanding a star must not depend on whether any matching row exists at runtime.
pub fn resolve(clauses: &[Clause]) -> CypherResult<Vec<Clause>> {
    resolve_with_procedures(clauses, None)
}
pub fn resolve_with_procedures(
    clauses: &[Clause],
    procedures: Option<&crate::procedures::ProcedureRegistry>,
) -> CypherResult<Vec<Clause>> {
    resolve_scoped(clauses, procedures, Scope::new())
}
pub fn resolve_with_bindings(
    clauses: &[Clause],
    procedures: Option<&crate::procedures::ProcedureRegistry>,
    bindings: &[String],
) -> CypherResult<Vec<Clause>> {
    resolve_scoped(
        clauses,
        procedures,
        bindings
            .iter()
            .map(|name| (name.clone(), Kind::Unknown))
            .collect(),
    )
}
fn resolve_scoped(
    clauses: &[Clause],
    procedures: Option<&crate::procedures::ProcedureRegistry>,
    mut scope: Scope,
) -> CypherResult<Vec<Clause>> {
    let union_modes: HashSet<_> = clauses
        .iter()
        .filter_map(|clause| {
            if let Clause::Union { all } = clause {
                Some(*all)
            } else {
                None
            }
        })
        .collect();
    if union_modes.len() > 1 {
        return Err(fail(
            "InvalidClauseComposition",
            "UNION and UNION ALL cannot be mixed in one query",
        ));
    }
    let mut resolved = clauses.to_vec();
    let mut order_scope = Scope::new();
    let mut order_known = Vec::new();
    let mut order_keys = Vec::new();
    let mut order_aggregate = false;
    let mut returned = false;
    let mut optional = false;
    let mut missing_alias = false;
    let standalone = clauses
        .iter()
        .filter(|clause| matches!(clause, Clause::Call(_)))
        .count()
        == 1
        && clauses.iter().all(|clause| {
            matches!(
                clause,
                Clause::Call(_)
                    | Clause::Where(_)
                    | Clause::OrderBy(_)
                    | Clause::Skip(_)
                    | Clause::Limit(_)
            )
        });
    for clause in &mut resolved {
        if returned
            && !matches!(
                clause,
                Clause::OrderBy(_) | Clause::Skip(_) | Clause::Limit(_) | Clause::Union { .. }
            )
        {
            return Err(fail(
                "InvalidClauseComposition",
                "RETURN must terminate a query part",
            ));
        }
        match clause {
            Clause::Call(call) => {
                if let Some(arguments) = &call.arguments {
                    for argument in arguments {
                        expression(argument, &scope, false, false, &[])?;
                    }
                }
                let outputs = if let Some(yields) = &call.yields {
                    yields
                        .iter()
                        .map(|(_, alias)| alias.clone())
                        .collect::<Vec<_>>()
                } else if standalone || call.yield_all {
                    if let Some(procedures) = procedures {
                        procedures
                            .signature(&call.name)?
                            .outputs
                            .iter()
                            .map(|field| field.name.clone())
                            .collect()
                    } else {
                        vec![]
                    }
                } else {
                    vec![]
                };
                for alias in outputs {
                    if scope.contains_key(&alias) {
                        return Err(fail(
                            "VariableAlreadyBound",
                            format!("Procedure output {alias} is already bound"),
                        ));
                    }
                    scope.insert(alias, Kind::Unknown);
                }
            }
            Clause::Set(items) => validate_set(items, &scope)?,
            Clause::Remove(items) => {
                for item in items {
                    let name = match item {
                        RemoveItem::Property { variable, .. }
                        | RemoveItem::Labels { variable, .. } => variable,
                    };
                    expression(&Expr::Variable(name.clone()), &scope, false, false, &[])?;
                }
            }
            Clause::Merge {
                pattern,
                on_create,
                on_match,
            } => {
                patterns(std::slice::from_ref(pattern), &mut scope, true, false)?;
                validate_set(on_create, &scope)?;
                validate_set(on_match, &scope)?;
            }
            Clause::Union { .. } => {
                scope.clear();
                order_scope.clear();
                order_known.clear();
                order_keys.clear();
                returned = false;
                optional = false;
                order_aggregate = false;
            }
            Clause::Match {
                patterns: ps,
                optional: is_optional,
                where_clause,
            } => {
                if optional && !*is_optional {
                    return Err(fail(
                        "InvalidClauseComposition",
                        "MATCH after OPTIONAL MATCH requires WITH",
                    ));
                }
                patterns(ps, &mut scope, false, false)?;
                if let Some(expr) = where_clause {
                    predicate(expr, &scope, false, false, &[])?;
                }
                optional |= *is_optional;
            }
            Clause::Create(ps) => patterns(ps, &mut scope, true, true)?,
            Clause::Where(expr) => predicate(expr, &scope, false, false, &[])?,
            Clause::Unwind { expr, variable } => {
                expression(expr, &scope, false, false, &[])?;
                if scope.contains_key(variable) {
                    return Err(fail(
                        "VariableAlreadyBound",
                        format!("UNWIND variable {variable} is already bound"),
                    ));
                }
                scope.insert(variable.clone(), Kind::Unknown);
            }
            Clause::With {
                distinct,
                items,
                where_clause,
            } => {
                missing_alias |= items.as_ref().is_some_and(|items| {
                    items
                        .iter()
                        .any(|item| item.alias.is_none() && !matches!(item.expr, Expr::Variable(_)))
                });
                let (next, agg, keys, known) = projection(items, &scope, true)?;
                order_scope = if *distinct || agg {
                    next.clone()
                } else {
                    let mut all = scope.clone();
                    all.extend(next.clone());
                    all
                };
                order_known = known;
                order_keys = keys;
                order_aggregate = agg;
                if let Some(expr) = where_clause {
                    let mut visible = scope.clone();
                    visible.extend(next.clone());
                    predicate(expr, &visible, false, false, &[])?;
                }
                scope = next;
                optional = false;
            }
            Clause::Return { distinct, items } => {
                let (next, agg, keys, known) = projection(items, &scope, false)?;
                order_scope = if *distinct || agg {
                    next.clone()
                } else {
                    let mut all = scope.clone();
                    all.extend(next.clone());
                    all
                };
                order_known = known;
                order_keys = keys;
                order_aggregate = agg;
                scope = next;
                returned = true;
            }
            Clause::OrderBy(items) => {
                for item in items {
                    expression(
                        &item.expr,
                        &order_scope,
                        order_aggregate,
                        false,
                        &order_known,
                    )?;
                    if aggregate(&item.expr) {
                        let mut keys = order_keys.clone();
                        keys.extend(scope.keys().map(|name| Expr::Variable(name.clone())));
                        grouping(&item.expr, &keys, false)?;
                    }
                }
            }
            Clause::Skip(expr) | Clause::Limit(expr) => limit(expr, &scope)?,
            Clause::Delete { exprs, .. } => {
                for expr in exprs {
                    if matches!(expr, Expr::LabelPredicate { .. }) {
                        return Err(fail(
                            "InvalidDelete",
                            "DELETE cannot remove labels or relationship types",
                        ));
                    }
                    expression(expr, &scope, false, false, &[])?;
                    let kind = infer(expr, &scope);
                    if !matches!(
                        kind,
                        Kind::Unknown | Kind::Null | Kind::Node | Kind::Rel | Kind::Path
                    ) {
                        return Err(fail(
                            "InvalidArgumentType",
                            "DELETE requires a graph element",
                        ));
                    }
                }
            }
        }
    }
    if missing_alias {
        return Err(fail(
            "NoExpressionAlias",
            "WITH expressions must be aliased",
        ));
    }
    Ok(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn validate(query: &str) -> CypherResult<Vec<Clause>> {
        resolve(&crate::parser::parse(query)?)
    }
    fn detail(query: &str) -> &'static str {
        let error = validate(query).unwrap_err();
        error.diagnostic().unwrap().detail
    }
    #[test]
    fn undefined_scopes_and_local_comprehension_bindings_are_distinct() {
        assert_eq!(detail("RETURN missing"), "UndefinedVariable");
        assert_eq!(
            detail("WITH 1 AS x WITH 2 AS y RETURN x"),
            "UndefinedVariable"
        );
        assert!(validate(
            "WITH [1,2] AS xs RETURN [x IN xs WHERE x > 1 | x+1], all(x IN xs WHERE x > 0)"
        )
        .is_ok());
        assert_eq!(
            detail("WITH [1,2] AS xs RETURN [x IN xs | x], x"),
            "UndefinedVariable"
        );
        assert!(validate("MATCH (a),(b {name:a.name}) RETURN a,b").is_ok());
    }
    #[test]
    fn star_projection_is_sorted_and_known_before_rows_exist() {
        let clauses = validate("MATCH (z), (a), (b) RETURN *").unwrap();
        let Clause::Return {
            items: Some(items), ..
        } = clauses.last().unwrap()
        else {
            panic!()
        };
        assert_eq!(
            items
                .iter()
                .map(|item| crate::render::to_cypher_string(&item.expr))
                .collect::<Vec<_>>(),
            vec!["a", "b", "z"]
        );
        assert_eq!(detail("MATCH () RETURN *"), "NoVariablesInScope");
    }
    #[test]
    fn aggregate_validation_descends_all_expression_containers() {
        assert_eq!(detail("RETURN count(count(*))"), "NestedAggregation");
        assert_eq!(
            detail("MATCH (n) WHERE count(n)>0 RETURN n"),
            "InvalidAggregation"
        );
        assert_eq!(
            detail("MATCH (a),(b) RETURN a.x + count(b)"),
            "AmbiguousAggregationExpression"
        );
        assert!(validate("MATCH (a),(b) RETURN a.x, a.x + count(b)").is_ok());
        assert!(validate("MATCH (n) RETURN {count:count(n)}").is_ok());
    }
    #[test]
    fn aliases_function_arity_and_repeated_bindings_report_native_origin() {
        for (query, wanted) in [
            ("RETURN 1 AS a,2 AS a", "ColumnNameConflict"),
            ("MATCH (n) WITH n,count(*) RETURN n", "NoExpressionAlias"),
            ("RETURN noSuchFunction(1)", "UnknownFunction"),
            ("RETURN abs(1,2)", "InvalidNumberOfArguments"),
            ("RETURN coalesce()", "InvalidNumberOfArguments"),
            (
                "MATCH ()-[r]->()-[r]->() RETURN r",
                "RelationshipUniquenessViolation",
            ),
            ("WITH 1 AS n MATCH (n) RETURN n", "VariableTypeConflict"),
            (
                "WITH 1 AS n UNWIND [] AS n RETURN n",
                "VariableAlreadyBound",
            ),
            ("MATCH (n) RETURN n LIMIT n.x", "NonConstantExpression"),
            ("RETURN 1 LIMIT -1", "NegativeIntegerArgument"),
        ] {
            assert_eq!(detail(query), wanted, "{query}");
        }
    }
    #[test]
    fn static_errors_are_reported_on_empty_inputs_and_star_headers_remain_visible() {
        let executor = crate::engine::Executor::new(vec![], false);
        let result = executor.execute("MATCH (z),(a) RETURN *", None).unwrap();
        assert_eq!(result.columns, vec!["a", "z"]);
        assert!(result.rows.is_empty());
        for (query, wanted) in [
            ("RETURN 123 AND true", "InvalidArgumentType"),
            ("RETURN count(rand())", "NonConstantExpression"),
            (
                "MATCH (a) RETURN [x IN [1,2] | count(*)]",
                "InvalidAggregation",
            ),
            (
                "MATCH p=(a)-[]->() MATCH(p) RETURN p",
                "VariableTypeConflict",
            ),
            ("MATCH p=(p)-[]->() RETURN p", "VariableAlreadyBound"),
        ] {
            assert_eq!(detail(query), wanted, "{query}");
        }
        let error = executor
            .execute("WITH 1 AS n RETURN n.p", None)
            .unwrap_err()
            .diagnostic()
            .unwrap();
        assert_eq!(
            (error.error_type, error.phase, error.detail),
            ("TypeError", "compile time", "InvalidArgumentType")
        );
    }
    #[test]
    fn aggregate_sources_do_not_capture_comprehension_local_variables() {
        assert!(validate("MATCH(n) RETURN all(ok IN collect(n IS NULL) WHERE ok)").is_ok());
        assert!(validate("MATCH(n) RETURN [x IN collect(n) | x]").is_ok());
    }
    #[test]
    fn fused_scope_proof_respects_comprehension_locals_and_outer_inputs() {
        let expr = |text: &str| {
            let clauses = crate::parser::parse(&format!("RETURN {text}")).unwrap();
            let Clause::Return {
                items: Some(items), ..
            } = &clauses[0]
            else {
                panic!()
            };
            items[0].expr.clone()
        };
        let allowed = HashSet::from(["xs".to_owned()]);
        assert!(references_only(&expr("all(x IN xs WHERE x > 1)"), &allowed));
        assert!(references_only(&expr("[x IN xs | x + 1]"), &allowed));
        assert!(!references_only(&expr("[x IN hidden | x]"), &allowed));
        assert!(!references_only(
            &expr("all(x IN xs WHERE x > hidden)"),
            &allowed
        ));
        assert!(!references_only(&expr("x IS NULL"), &allowed));
    }
    #[test]
    fn patterns_are_predicates_and_comprehensions_bind_only_local_names() {
        for query in [
            "MATCH (n) RETURN (n)-[]->()",
            "MATCH (n) RETURN size((n)-[]->())",
            "MATCH (n) SET n.x = head(nodes(head((n)-[]->()))).x",
        ] {
            assert_eq!(detail(query), "UnexpectedSyntax", "{query}");
        }
        assert!(validate("MATCH(n) WHERE (n)-->() RETURN n").is_ok());
        assert!(
            validate("MATCH(n) RETURN NOT (n)-->() AS absent, exists((n)-->() ) AS found").is_ok()
        );
        assert!(validate("MATCH(n) RETURN [(n)-->(x) WHERE x.name = 'A' | x.name]").is_ok());
        assert_eq!(
            detail("MATCH(n) RETURN [(n)-->(x) | x], x"),
            "UndefinedVariable"
        );
        assert_eq!(
            detail("MATCH(n) WHERE (missing)-->() RETURN n"),
            "UndefinedVariable"
        );
    }
    #[test]
    fn exists_validation_is_correlated_and_rejects_writes_before_execution() {
        assert!(validate("MATCH(n) RETURN EXISTS { MATCH (n)-->(m) WHERE m.x = n.x }").is_ok());
        assert_eq!(
            detail("MATCH(n) RETURN EXISTS { CREATE (m) RETURN m }"),
            "InvalidClauseComposition"
        );
        let graph = std::sync::Arc::new(crate::property_graph::PropertyGraph::new());
        let ex = crate::engine::Executor::property_graph(graph);
        let error = ex
            .execute("MATCH(n) RETURN EXISTS { CREATE (m) RETURN m }", None)
            .unwrap_err();
        assert_eq!(
            error.diagnostic().unwrap().detail,
            "InvalidClauseComposition"
        );
        ex.execute(
            "CREATE (:Person {name:'A'})-[:KNOWS]->(:Person {name:'B'})",
            None,
        )
        .unwrap();
        let result = ex.execute("MATCH(n:Person) WHERE EXISTS { MATCH (n)-->(m) WHERE m.name = 'B' } RETURN n.name AS name", None).unwrap();
        assert_eq!(result.rows.len(), 1);
        assert_eq!(result.rows[0]["name"].as_str(), Some("A"));
    }
    #[test]
    fn homogeneous_list_elements_constrain_numeric_predicates() {
        assert_eq!(
            detail("RETURN none(x IN ['Clara'] WHERE x % 2 = 0)"),
            "InvalidArgumentType"
        );
        assert_eq!(
            detail("RETURN all(x IN [false,true] WHERE x % 2 = 0)"),
            "InvalidArgumentType"
        );
        assert!(validate("RETURN all(x IN [1,2,null] WHERE x % 2 = 0)").is_ok());
        assert!(validate("RETURN all(x IN $items WHERE x % 2 = 0)").is_ok());
    }
    #[test]
    fn empty_star_and_explicit_empty_node_maps_preserve_write_semantics() {
        assert!(validate("WITH * CREATE (:Person)").is_ok());
        assert_eq!(
            detail("CREATE(n:Foo) CREATE(n {})-[:OWNS]->(:Dog)"),
            "VariableAlreadyBound"
        );
        assert_eq!(detail("MATCH(n) MERGE(n)"), "VariableAlreadyBound");
        assert!(validate("MATCH(n) MERGE(n)-[:KNOWS]->(:Person)").is_ok());
        let parsed = crate::parser::parse("CREATE(n {})").unwrap();
        let Clause::Create(patterns) = &parsed[0] else {
            panic!()
        };
        assert!(patterns[0].nodes[0].properties_present);
        assert_eq!(crate::render::pattern_to_string(&patterns[0]), "(n {})");
        let graph = std::sync::Arc::new(crate::property_graph::PropertyGraph::new());
        let ex = crate::engine::Executor::property_graph(graph.clone());
        ex.execute("CREATE (), ()", None).unwrap();
        ex.execute("MATCH () CREATE () WITH * CREATE ()", None)
            .unwrap();
        assert_eq!(graph.snapshot().nodes.len(), 6);
    }
    #[test]
    fn delete_labels_and_invalid_path_properties_have_distinct_diagnostics() {
        assert_eq!(detail("MATCH(n) DELETE n:Person"), "InvalidDelete");
        assert_eq!(detail("MATCH()-[r:T]-() DELETE r:T"), "InvalidDelete");
        let error = validate("MATCH p=()-->() WHERE p.name = 'x' RETURN p")
            .unwrap_err()
            .diagnostic()
            .unwrap();
        assert_eq!(
            (error.error_type, error.phase, error.detail),
            ("SyntaxError", "compile time", "InvalidArgumentType")
        );
    }
    #[test]
    fn relationship_type_predicates_are_case_sensitive() {
        let graph = std::sync::Arc::new(crate::property_graph::PropertyGraph::new());
        let ex = crate::engine::Executor::property_graph(graph);
        ex.execute("CREATE ()-[:T]->(),()-[:t]->()", None).unwrap();
        let result = ex
            .execute("MATCH()-[r]->() RETURN r:T AS match ORDER BY match", None)
            .unwrap();
        assert_eq!(result.rows.len(), 2);
        assert!(matches!(result.rows[0]["match"], crate::Value::Bool(false)));
        assert!(matches!(result.rows[1]["match"], crate::Value::Bool(true)));
    }
}
