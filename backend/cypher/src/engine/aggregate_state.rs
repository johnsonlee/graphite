//! Incremental aggregate state; argument evaluation and group finalization stay in
//! the pipeline so errors, encounter order and provenance keep their boundaries.
use super::*;
use crate::semantics::to_double;
use std::collections::HashSet;

pub(super) struct AggregateState {
    dialect: crate::CypherDialect,
    distinct: Option<HashSet<Key>>,
    kind: Kind,
}

enum Kind {
    Count(DeferredCount),
    Number(Box<NumberState>),
    Extreme {
        min: bool,
        best: Option<Value>,
    },
    Collect {
        values: Vec<Value>,
        cap: Option<usize>,
        weighted: bool,
    },
    Inputs {
        values: Vec<Value>,
        weights: Vec<i64>,
    },
}

#[derive(Default)]
pub(super) struct DeferredCount {
    value: i64,
    overflow: bool,
}

impl DeferredCount {
    pub(super) fn add(&mut self, value: i64) {
        self.overflow |= self.value.checked_add(value).is_none();
        self.value = self.value.wrapping_add(value);
    }

    pub(super) fn finish(self) -> i64 {
        assert!(
            !cfg!(debug_assertions) || !self.overflow,
            "attempt to add with overflow"
        );
        self.value
    }
}

struct NumberState {
    average: bool,
    sum: f64,
    count: i64,
    weighted_count: DeferredCount,
    total: i128,
    magnitude: i128,
    overflow: bool,
    non_integer: Option<Value>,
    weighted: bool,
}

impl AggregateState {
    #[cfg(test)]
    pub(super) fn new(agg: &AggCall, prefix: Option<usize>) -> Self {
        Self::with_dialect(agg, prefix, crate::CypherDialect::OpenCypherM23)
    }

    pub(super) fn with_dialect(
        agg: &AggCall,
        prefix: Option<usize>,
        dialect: crate::CypherDialect,
    ) -> Self {
        let kind = match agg.name.to_ascii_lowercase().as_str() {
            "count" => Kind::Count(DeferredCount::default()),
            "sum" | "avg" => Kind::Number(Box::new(NumberState {
                average: agg.name.eq_ignore_ascii_case("avg"),
                sum: std::iter::empty::<f64>().sum(),
                count: 0,
                weighted_count: DeferredCount::default(),
                total: 0,
                magnitude: 0,
                overflow: false,
                non_integer: None,
                weighted: false,
            })),
            "min" | "max" => Kind::Extreme {
                min: agg.name.eq_ignore_ascii_case("min"),
                best: None,
            },
            "collect" => Kind::Collect {
                values: Vec::new(),
                cap: prefix.filter(|_| !agg.distinct),
                weighted: false,
            },
            _ => Kind::Inputs {
                values: Vec::new(),
                weights: Vec::new(),
            },
        };
        Self {
            dialect,
            distinct: agg.distinct.then(HashSet::new),
            kind,
        }
    }

    pub(super) fn push(&mut self, value: Value, mut weight: i64) {
        if let Some(seen) = &mut self.distinct {
            if !seen.insert(value_key(&value)) {
                return;
            }
            // DISTINCT removes the partition weight, including for the NULL input.
            weight = 1;
        }
        match &mut self.kind {
            Kind::Count(count) => {
                if !value.is_null() {
                    count.add(weight);
                }
            }
            Kind::Number(state) => {
                state.weighted |= weight != 1;
                if value.is_null() {
                    return;
                }
                state.sum += to_double(&value);
                if state.average {
                    state.count += 1;
                    state.weighted_count.add(weight);
                }
                if let Value::Int(n) = value {
                    let total = n as i128 * weight as i128;
                    let magnitude = n.unsigned_abs() as i128 * weight as i128;
                    if state.non_integer.is_none()
                        && (state.total.checked_add(total).is_none()
                            || state.magnitude.checked_add(magnitude).is_none())
                    {
                        state.overflow = true;
                    }
                    // Preserve the original release wrapping and debug overflow, but
                    // report it only at the original finalization boundary.
                    state.total = state.total.wrapping_add(total);
                    state.magnitude = state.magnitude.wrapping_add(magnitude);
                } else if state.non_integer.is_none() {
                    state.non_integer = Some(value);
                }
            }
            Kind::Extreme { min, best } => {
                if value.is_null() {
                    return;
                }
                let replace = best.as_ref().is_none_or(|previous| {
                    crate::functions::prefer_extreme(&value, previous, *min, self.dialect)
                });
                if replace {
                    *best = Some(value);
                }
            }
            Kind::Collect {
                values,
                cap,
                weighted,
            } => {
                *weighted |= weight != 1;
                if !value.is_null() && cap.is_none_or(|n| values.len() < n) {
                    values.push(value);
                }
            }
            Kind::Inputs { values, weights } => {
                values.push(value);
                weights.push(weight);
            }
        }
    }

    pub(super) fn finish(self, agg: &AggCall, percentile: Option<&Value>) -> CypherResult<Value> {
        match self.kind {
            Kind::Count(count) => Ok(Value::Int(count.finish())),
            Kind::Number(state) => {
                if self.dialect == crate::CypherDialect::OpenCypherM23
                    && !state.average
                    && state.non_integer.is_none()
                {
                    if state.overflow {
                        return Err(CypherError::execution(
                            "ArithmeticError",
                            "IntegerOverflow",
                            "Integer sum exceeds signed 64-bit range",
                        ));
                    }
                    return i64::try_from(state.total).map(Value::Int).map_err(|_| {
                        CypherError::execution(
                            "ArithmeticError",
                            "IntegerOverflow",
                            "Integer sum exceeds signed 64-bit range",
                        )
                    });
                }
                let weighted_count = if state.weighted && state.average {
                    let count = state.weighted_count.finish();
                    if count == 0 {
                        return Ok(Value::Null);
                    }
                    count
                } else {
                    0
                };
                let (sum, count) = if state.weighted {
                    assert!(
                        !cfg!(debug_assertions) || !state.overflow,
                        "attempt to add with overflow"
                    );
                    if let Some(value) = state.non_integer {
                        return Err(CypherError::Runtime(format!("Aggregation {} over weighted rows needs integer inputs, found {value:?}", agg.name)));
                    }
                    if state.magnitude > EXACT_SUM_MAGNITUDE {
                        return Err(CypherError::Runtime(format!(
                            "Aggregation {} over weighted rows exceeds the exact floating range",
                            agg.name
                        )));
                    }
                    (state.total as f64, weighted_count)
                } else {
                    (state.sum, state.count)
                };
                Ok(if agg.name.eq_ignore_ascii_case("avg") {
                    if count == 0 {
                        Value::Null
                    } else {
                        Value::Float(sum / count as f64)
                    }
                } else {
                    Value::Float(sum)
                })
            }
            Kind::Extreme { best, .. } => Ok(best.unwrap_or(Value::Null)),
            Kind::Collect {
                mut values,
                weighted: false,
                ..
            } => {
                values.shrink_to_fit();
                Ok(Value::list(values))
            }
            Kind::Collect { weighted: true, .. } => Err(CypherError::Runtime(format!(
                "Aggregation {} cannot be applied to weighted rows",
                agg.name
            ))),
            Kind::Inputs { values, weights } => {
                if weights.iter().all(|w| *w == 1) {
                    if let Some(parameter) =
                        percentile.filter(|_| self.dialect == crate::CypherDialect::OpenCypherM23)
                    {
                        crate::functions::percentile(&agg.name, &values, parameter)
                    } else {
                        aggregate(&agg.name, &values)
                    }
                } else {
                    aggregate_weighted(&agg.name, &values, &weights)
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(name: &str, distinct: bool) -> AggCall {
        AggCall {
            name: name.into(),
            distinct,
            arg: None,
            percentile: None,
        }
    }

    fn same(actual: Value, expected: Value) {
        match (actual, expected) {
            (Value::Float(a), Value::Float(b)) => assert_eq!(a.to_bits(), b.to_bits()),
            (a, b) => assert_eq!(format!("{a:?}"), format!("{b:?}")),
        }
    }

    #[test]
    fn incremental_results_match_original_aggregate_including_numeric_encounter_order() {
        let cases = [
            vec![],
            vec![Value::Null],
            vec![Value::Float(-0.0), Value::Float(0.0)],
            vec![Value::Float(1e16), Value::Int(1), Value::Float(-1e16)],
            vec![
                Value::Int(1),
                Value::str("1"),
                Value::Float(1.0),
                Value::Float32(1.0),
            ],
            vec![
                Value::str("2.5"),
                Value::Bool(true),
                Value::Null,
                Value::Int(-4),
            ],
            vec![
                Value::Float(f64::NAN),
                Value::Float(f64::INFINITY),
                Value::Int(-1),
            ],
            vec![
                Value::Int(4),
                Value::Float(f64::NAN),
                Value::Float(f64::NAN),
            ],
        ];
        for name in [
            "count",
            "sum",
            "avg",
            "min",
            "max",
            "collect",
            "stdev",
            "percentilecont",
        ] {
            for distinct in [false, true] {
                let agg = call(name, distinct);
                for values in &cases {
                    let mut state = AggregateState::new(&agg, None);
                    for value in values {
                        state.push(value.clone(), 1);
                    }
                    let mut unique = HashSet::new();
                    let reference: Vec<_> = values
                        .iter()
                        .filter(|v| !distinct || unique.insert(value_key(v)))
                        .cloned()
                        .collect();
                    same(
                        state.finish(&agg, None).unwrap(),
                        aggregate(name, &reference).unwrap(),
                    );
                }
            }
        }
    }

    #[test]
    fn weighted_results_and_deferred_errors_match_existing_finalization() {
        let cases = [
            (
                vec![Value::Int(2), Value::Null, Value::Int(-3)],
                vec![4, 0, 2],
            ),
            (vec![Value::str("2"), Value::Null], vec![1, 7]),
            (vec![Value::Int(1 << 53), Value::Int(1)], vec![1, 2]),
            (vec![Value::Float(-0.0)], vec![1]),
            (vec![Value::Null], vec![2]),
            (vec![Value::str("2"), Value::Null], vec![0, 2]),
            (vec![Value::str("2"), Value::str("3")], vec![2, -2]),
        ];
        for name in ["count", "sum", "avg", "min", "max", "collect", "stdev"] {
            for (values, weights) in &cases {
                let agg = call(name, false);
                let mut state = AggregateState::new(&agg, None);
                for (value, weight) in values.iter().zip(weights) {
                    state.push(value.clone(), *weight);
                }
                let reference = if weights.iter().all(|w| *w == 1) {
                    aggregate(name, values)
                } else {
                    aggregate_weighted(name, values, weights)
                };
                match (state.finish(&agg, None), reference) {
                    (Ok(actual), Ok(expected)) => same(actual, expected),
                    (Err(actual), Err(expected)) => {
                        assert_eq!(format!("{actual:?}"), format!("{expected:?}"))
                    }
                    (a, b) => panic!("{name}: {a:?} != {b:?}"),
                }
            }
        }
    }

    #[test]
    fn bounded_collect_keeps_only_requested_non_null_prefix() {
        for cap in [0, 1, 3, 20] {
            let agg = call("collect", false);
            let mut state = AggregateState::new(&agg, Some(cap));
            for n in 0..10 {
                state.push(Value::Int(n), 1);
                state.push(Value::Null, 1);
            }
            let Kind::Collect { values, .. } = &state.kind else {
                panic!("collect state")
            };
            assert_eq!(values.len(), cap.min(10));
            same(
                state.finish(&agg, None).unwrap(),
                Value::list((0..cap.min(10)).map(|n| Value::Int(n as i64)).collect()),
            );
        }
    }

    #[test]
    fn weighted_sum_does_not_add_an_unused_overflowing_count() {
        let agg = call("sum", false);
        let mut state = AggregateState::new(&agg, None);
        for _ in 0..2 {
            state.push(Value::Int(0), i64::MAX);
        }
        same(
            state.finish(&agg, None).unwrap(),
            aggregate_weighted(
                "sum",
                &[Value::Int(0), Value::Int(0)],
                &[i64::MAX, i64::MAX],
            )
            .unwrap(),
        );
    }

    #[test]
    fn overflow_keeps_the_original_finalization_boundary() {
        let ex = Executor::new(vec![], true);
        let ev = Evaluator::new(&ex, &ex.params);
        for name in ["count", "sum", "avg"] {
            let rows = [false, false, false, true]
                .into_iter()
                .map(|fail| {
                    Row::from([
                        ("fail".into(), Value::Bool(fail)),
                        ("x".into(), Value::Int(i64::MAX)),
                        (INTERNAL_WEIGHT_KEY.into(), Value::Int(i64::MAX)),
                    ])
                })
                .collect();
            let item = ReturnItem {
                source_text: None,
                alias: Some("result".into()),
                expr: Expr::FunctionCall {
                    name: name.into(),
                    distinct: false,
                    args: vec![Expr::Case {
                        test: None,
                        whens: vec![(
                            Expr::Variable("fail".into()),
                            Expr::FunctionCall {
                                name: "missing_function".into(),
                                distinct: false,
                                args: vec![],
                            },
                        )],
                        else_expr: Some(Box::new(Expr::Variable("x".into()))),
                    }],
                },
            };
            let error = project(&ev, rows, Some(&[item]), false, None).unwrap_err();
            assert!(
                error.to_string().contains("missing_function"),
                "{name}: {error}"
            );
        }
        // A completed weighted COUNT has exactly the old debug-overflow/release-wrap behavior.
        let agg = call("count", false);
        let mut state = AggregateState::new(&agg, None);
        state.push(Value::Int(1), i64::MAX);
        state.push(Value::Int(1), 1);
        let actual =
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| state.finish(&agg, None)));
        let expected = std::panic::catch_unwind(|| {
            aggregate_weighted("count", &[Value::Int(1), Value::Int(1)], &[i64::MAX, 1])
        });
        assert_eq!(actual.is_err(), expected.is_err());
        if let (Ok(Ok(actual)), Ok(Ok(expected))) = (actual, expected) {
            same(actual, expected);
        }
    }

    #[test]
    fn prefix_proof_requires_exclusive_bounded_consumers() {
        for (tail, expected) in [
            ("RETURN names[0..10] AS result", vec![Some(10)]),
            (
                "RETURN names[2..5] AS first, names[4..12] AS second LIMIT 1",
                vec![Some(12)],
            ),
            ("RETURN 1 AS dropped", vec![Some(0)]),
            ("RETURN names", vec![]),
            ("RETURN *", vec![]),
            ("RETURN names[0..10], size(names)", vec![]),
            ("RETURN names[-2..10]", vec![]),
            ("RETURN names[0..$end]", vec![]),
            ("RETURN names[0..10] ORDER BY names", vec![]),
        ] {
            let clauses = crate::parser::parse(&format!(
                "MATCH (n:CallSite) WITH collect(n.callee_name) AS names {tail}"
            ))
            .unwrap();
            let (shape, predicate, consumed) = FusedShape::detect_with(&clauses).unwrap();
            assert!(predicate.is_none());
            assert_eq!(
                collect_prefix_caps(&shape, &clauses[consumed..]),
                expected,
                "{tail}"
            );
        }
        let clauses = crate::parser::parse("MATCH (n:CallSite) WITH any(x IN collect(n.callee_name) WHERE count(*) > 0) AS flag, collect(n.callee_name) AS names, collect(n.caller_name) AS dropped RETURN names[0..10] AS result").unwrap();
        let (shape, _, consumed) = FusedShape::detect_with(&clauses).unwrap();
        assert_eq!(
            AggPlan::new(
                shape.items.as_deref().unwrap(),
                shape.order.as_deref(),
                crate::CypherDialect::OpenCypherM23
            )
            .unwrap()
            .aggs
            .len(),
            4
        );
        assert!(collect_prefix_caps(&shape, &clauses[consumed..]).is_empty());
    }

    #[test]
    fn real_multigraph_collect_prefix_preserves_all_provenance_and_late_errors() {
        let Some(path) = std::env::var_os("GRAPHITE_INDEX_FIXTURE") else {
            return;
        };
        let graph = Arc::new(graphite_storage::Graph::load(std::path::Path::new(&path)).unwrap());
        let sources = ["z", "a"]
            .into_iter()
            .map(|id| super::super::super::Source {
                id: Arc::from(id),
                graph: graph.clone(),
            })
            .collect::<Vec<_>>();
        for range in ["0..0", "0..10", "2..5", "10..3", "0..65536"] {
            let query = format!("MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN names[{range}] AS result");
            let reference = format!("MATCH (n:CallSite) WITH collect(n.callee_name) AS names RETURN (CASE WHEN true THEN names ELSE [] END)[{range}] AS result");
            let actual = Executor::new(sources.clone(), true)
                .execute(&query, None)
                .unwrap();
            let expected = Executor::new(sources.clone(), true)
                .execute(&reference, None)
                .unwrap();
            assert_eq!(format!("{actual:?}"), format!("{expected:?}"), "{range}");
            assert_eq!(QueryResult::graph_ids(&actual.rows[0]), ["a", "z"]);
        }
        let query = "MATCH (n:CallSite) WITH collect(CASE WHEN n.graphId = 'a' THEN missing_function(n.id) ELSE n.callee_name END) AS names RETURN names[0..1] AS result";
        let error = Executor::new(sources, true)
            .execute(query, None)
            .unwrap_err();
        assert!(error.to_string().contains("missing_function"));
    }
}
