//! Procedure calls share the normal expression evaluator, rows and scope pipeline.
use super::{Executor, Row};
use crate::{
    ast::{Clause, Expr, ProcedureCall},
    eval::{contains_aggregation, Evaluator},
    procedures::syntax,
    render::to_cypher_string,
    CypherError, CypherResult, Value,
};
use std::collections::BTreeSet;

pub(super) fn standalone(clauses: &[Clause]) -> bool {
    clauses
        .iter()
        .filter(|c| matches!(c, Clause::Call(_)))
        .count()
        == 1
        && clauses.iter().all(|c| {
            matches!(
                c,
                Clause::Call(_)
                    | Clause::Where(_)
                    | Clause::OrderBy(_)
                    | Clause::Skip(_)
                    | Clause::Limit(_)
            )
        })
}

fn references(
    expr: &Expr,
    locals: &BTreeSet<String>,
    vars: &mut BTreeSet<String>,
    params: &mut BTreeSet<String>,
) {
    match expr {
        Expr::Variable(name) if !locals.contains(name) => {
            vars.insert(name.clone());
        }
        Expr::Parameter(name) => {
            params.insert(name.clone());
        }
        Expr::Property { expr, .. }
        | Expr::Distinct(expr)
        | Expr::Unary { expr, .. }
        | Expr::Not(expr)
        | Expr::IsNull(expr)
        | Expr::IsNotNull(expr) => references(expr, locals, vars, params),
        Expr::FunctionCall { args, .. } | Expr::ListLiteral(args) => {
            for expr in args {
                references(expr, locals, vars, params);
            }
        }
        Expr::MapLiteral(entries) => {
            for (_, expr) in entries {
                references(expr, locals, vars, params);
            }
        }
        Expr::Binary { left, right, .. }
        | Expr::Comparison { left, right, .. }
        | Expr::StringOp { left, right, .. }
        | Expr::In { left, right }
        | Expr::And(left, right)
        | Expr::Or(left, right)
        | Expr::Xor(left, right) => {
            references(left, locals, vars, params);
            references(right, locals, vars, params);
        }
        Expr::Case {
            test,
            whens,
            else_expr,
        } => {
            if let Some(e) = test {
                references(e, locals, vars, params)
            }
            for (a, b) in whens {
                references(a, locals, vars, params);
                references(b, locals, vars, params);
            }
            if let Some(e) = else_expr {
                references(e, locals, vars, params)
            }
        }
        Expr::ListComprehension {
            variable,
            list,
            filter,
            map,
        } => {
            references(list, locals, vars, params);
            let mut scoped = locals.clone();
            scoped.insert(variable.clone());
            if let Some(e) = filter {
                references(e, &scoped, vars, params)
            }
            if let Some(e) = map {
                references(e, &scoped, vars, params)
            }
        }
        Expr::PredicateFunction {
            variable,
            list,
            predicate,
            ..
        } => {
            references(list, locals, vars, params);
            let mut scoped = locals.clone();
            scoped.insert(variable.clone());
            if let Some(e) = predicate {
                references(e, &scoped, vars, params)
            }
        }
        Expr::Subscript { expr, index } => {
            references(expr, locals, vars, params);
            references(index, locals, vars, params);
        }
        Expr::Slice { expr, from, to } => {
            references(expr, locals, vars, params);
            if let Some(e) = from {
                references(e, locals, vars, params)
            }
            if let Some(e) = to {
                references(e, locals, vars, params)
            }
        }
        _ => {}
    }
}

fn check_references(
    expr: &Expr,
    bound: &BTreeSet<String>,
    params: &indexmap::IndexMap<String, Value>,
) -> CypherResult<()> {
    let mut variables = BTreeSet::new();
    let mut parameters = BTreeSet::new();
    references(expr, &BTreeSet::new(), &mut variables, &mut parameters);
    if let Some(name) = variables.difference(bound).next() {
        return Err(syntax(
            "UndefinedVariable",
            format!("Variable {name} is not defined"),
        ));
    }
    if let Some(name) = parameters.iter().find(|name| !params.contains_key(*name)) {
        return Err(CypherError::compilation(
            "ParameterMissing",
            "MissingParameter",
            format!("Parameter {name} is missing"),
        ));
    }
    Ok(())
}

fn constant_type_value(expr: &Expr) -> bool {
    match expr {
        Expr::Literal(_) | Expr::Parameter(_) => true,
        Expr::Unary { expr, .. } => constant_type_value(expr),
        _ => false,
    }
}

impl Executor {
    fn procedure_signature(
        &self,
        name: &str,
    ) -> CypherResult<&crate::procedures::ProcedureSignature> {
        self.procedures
            .as_ref()
            .ok_or_else(|| {
                CypherError::compilation(
                    "ProcedureError",
                    "ProcedureNotFound",
                    format!("Unknown procedure: {name}"),
                )
            })?
            .signature(name)
    }
    pub(super) fn procedure_yields(
        &self,
        call: &ProcedureCall,
        standalone: bool,
    ) -> CypherResult<Vec<(String, String)>> {
        let signature = self.procedure_signature(&call.name)?;
        if call.yield_all && !standalone {
            return Err(syntax(
                "UnexpectedSyntax",
                "YIELD * requires a standalone procedure call",
            ));
        }
        let fields = if standalone && call.yields.is_none() || call.yield_all {
            signature
                .outputs
                .iter()
                .map(|f| (f.name.clone(), f.name.clone()))
                .collect()
        } else {
            call.yields.clone().unwrap_or_default()
        };
        for (field, _) in &fields {
            if !signature.outputs.iter().any(|f| f.name == *field) {
                return Err(syntax(
                    "UndefinedVariable",
                    format!("Unknown procedure output {field}"),
                ));
            }
        }
        Ok(fields)
    }
    fn procedure_arguments(
        &self,
        call: &ProcedureCall,
        standalone: bool,
    ) -> CypherResult<Vec<Expr>> {
        let signature = self.procedure_signature(&call.name)?;
        if let Some(arguments) = &call.arguments {
            if arguments.len() != signature.inputs.len() {
                return Err(syntax(
                    "InvalidNumberOfArguments",
                    format!("Invalid argument count for procedure {}", call.name),
                ));
            }
            Ok(arguments.clone())
        } else {
            if !standalone {
                return Err(syntax(
                    "InvalidArgumentPassingMode",
                    "Implicit procedure parameters require a standalone call",
                ));
            }
            Ok(signature
                .inputs
                .iter()
                .map(|f| Expr::Parameter(f.name.clone()))
                .collect())
        }
    }
    pub(super) fn validate_procedure_calls(&self, clauses: &[Clause]) -> CypherResult<()> {
        if !clauses.iter().any(|c| matches!(c, Clause::Call(_))) {
            return Ok(());
        }
        let standalone = standalone(clauses);
        let mut bound = BTreeSet::new();
        let ev = Evaluator::new(self, &self.params);
        for clause in clauses {
            match clause {
                Clause::Match { patterns, .. } | Clause::Create(patterns) => {
                    for pattern in patterns {
                        bound.extend(pattern.variables().into_iter().map(str::to_owned));
                    }
                }
                Clause::Unwind { variable, .. } => {
                    bound.insert(variable.clone());
                }
                Clause::With {
                    items: Some(items), ..
                } => {
                    let mut next = BTreeSet::new();
                    for item in items {
                        next.insert(
                            item.alias
                                .clone()
                                .unwrap_or_else(|| to_cypher_string(&item.expr)),
                        );
                    }
                    bound = next;
                }
                Clause::Return {
                    items: Some(items), ..
                } => {
                    for item in items {
                        check_references(&item.expr, &bound, &self.params)?;
                    }
                }
                Clause::Call(call) => {
                    let arguments = self.procedure_arguments(call, standalone)?;
                    let signature = self.procedure_signature(&call.name)?;
                    for (argument, field) in arguments.iter().zip(&signature.inputs) {
                        if contains_aggregation(argument) {
                            return Err(syntax(
                                "InvalidAggregation",
                                "Procedure arguments cannot contain aggregations",
                            ));
                        }
                        check_references(argument, &bound, &self.params)?;
                        if constant_type_value(argument) {
                            field.coerce(&ev.eval(argument, &Row::new())?)?;
                        }
                    }
                    let fields = self.procedure_yields(call, standalone)?;
                    let mut outputs = BTreeSet::new();
                    for (_, alias) in fields {
                        if bound.contains(&alias) || !outputs.insert(alias.clone()) {
                            return Err(syntax(
                                "VariableAlreadyBound",
                                format!("Variable {alias} is already bound"),
                            ));
                        }
                    }
                    bound.extend(outputs);
                }
                _ => {}
            }
        }
        Ok(())
    }
    pub(super) fn execute_procedure(
        &self,
        rows: Vec<Row>,
        call: &ProcedureCall,
        standalone: bool,
    ) -> CypherResult<(Vec<String>, Vec<Row>)> {
        let expressions = self.procedure_arguments(call, standalone)?;
        let fields = self.procedure_yields(call, standalone)?;
        let ev = Evaluator::new(self, &self.params);
        let registry = self.procedures.as_ref().ok_or_else(|| {
            CypherError::compilation(
                "ProcedureError",
                "ProcedureNotFound",
                format!("Unknown procedure: {}", call.name),
            )
        })?;
        let mut output = Vec::new();
        for row in rows {
            self.tick()?;
            let arguments = expressions
                .iter()
                .map(|expr| ev.eval(expr, &row))
                .collect::<CypherResult<Vec<_>>>()?;
            for returned in registry.invoke(&call.name, &arguments)? {
                let mut next = row.clone();
                for (field, alias) in &fields {
                    next.insert(
                        alias.clone(),
                        returned.get(field).cloned().ok_or_else(|| {
                            CypherError::runtime("procedure provider output is missing")
                        })?,
                    );
                }
                output.push(next);
            }
        }
        if standalone && fields.is_empty() {
            output.clear();
        }
        Ok((fields.into_iter().map(|(_, alias)| alias).collect(), output))
    }
}
