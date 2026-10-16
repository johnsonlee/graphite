//! Reviewed, directed semantic transitions. This policy must be compiled from
//! the trusted controller source before a feature revision can use it.
//!
//! Digests cover exact query bytes and the complete canonical typed response,
//! including row order, duplicate rows, metadata and graph identity. The caller
//! must not project a response down to counts or selected properties first.
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Observation {
    pub case_id: String,
    pub base_query_sha256: String,
    pub head_query_sha256: String,
    pub base_response_sha256: String,
    pub head_response_sha256: String,
}

struct Rule<'a> {
    base_product_identity: &'a str,
    head_product_identity: &'a str,
    case_id: &'a str,
    base_query_sha256: &'a str,
    head_query_sha256: &'a str,
    base_response_sha256: &'a str,
    head_response_sha256: &'a str,
    reason: &'a str,
}

// No wildcards, external policy file, environment setting or CLI override.
// Land reviewed literal entries in the trusted base first, then rerun the
// feature comparison. Product identities exclude only the exact trusted policy
// files, so merging policy-only commits does not invalidate reviewed products.
const RULES: &[Rule<'static>] = &[];

fn digest(value: &str, bytes: usize) -> bool {
    value.len() == bytes * 2
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub fn evaluate(
    base_product_identity: &str,
    head_product_identity: &str,
    observations: &[Observation],
) -> Value {
    evaluate_rules(
        base_product_identity,
        head_product_identity,
        observations,
        RULES,
    )
}

fn evaluate_rules(
    base_product_identity: &str,
    head_product_identity: &str,
    observations: &[Observation],
    rules: &[Rule<'_>],
) -> Value {
    let mut errors = Vec::new();
    if !digest(base_product_identity, 32) || !digest(head_product_identity, 32) {
        errors.push(
            "Expected full lowercase base and candidate product SHA-256 identities".to_owned(),
        );
    }
    if observations.is_empty() {
        errors.push("No semantic observations supplied".to_owned());
    }
    let mut applicable = BTreeMap::new();
    for rule in rules.iter().filter(|r| {
        r.base_product_identity == base_product_identity
            && r.head_product_identity == head_product_identity
    }) {
        if rule.case_id.is_empty()
            || rule.reason.trim().is_empty()
            || ![
                rule.base_query_sha256,
                rule.head_query_sha256,
                rule.base_response_sha256,
                rule.head_response_sha256,
            ]
            .iter()
            .all(|value| digest(value, 32))
        {
            errors.push(format!(
                "Malformed compiled-in transition for {}",
                rule.case_id
            ));
        }
        if applicable.insert(rule.case_id, rule).is_some() {
            errors.push(format!(
                "Duplicate compiled-in transition for {}",
                rule.case_id
            ));
        }
    }
    let mut seen = BTreeSet::new();
    let mut cases = Vec::new();
    for observation in observations {
        let valid = !observation.case_id.is_empty()
            && [
                &observation.base_query_sha256,
                &observation.head_query_sha256,
                &observation.base_response_sha256,
                &observation.head_response_sha256,
            ]
            .iter()
            .all(|value| digest(value, 32));
        let unique = seen.insert(observation.case_id.as_str());
        let exact = observation.base_query_sha256 == observation.head_query_sha256
            && observation.base_response_sha256 == observation.head_response_sha256;
        let rule = applicable.get(observation.case_id.as_str());
        let matched = rule.is_some_and(|rule| {
            rule.base_query_sha256 == observation.base_query_sha256
                && rule.head_query_sha256 == observation.head_query_sha256
                && rule.base_response_sha256 == observation.base_response_sha256
                && rule.head_response_sha256 == observation.head_response_sha256
        });
        // An applicable expectation must match even if the observed case became
        // equal: changed/missing expected behavior is not the reviewed product.
        let approved = valid && unique && if rule.is_some() { matched } else { exact };
        let reason = if !valid {
            "Invalid case identity or SHA-256 digest"
        } else if !unique {
            "Duplicate case observation"
        } else if matched {
            rule.expect("matched rule").reason
        } else if rule.is_some() {
            "Observed query or complete typed response differs from reviewed expectation"
        } else if exact {
            "Queries and complete typed responses are identical"
        } else {
            "No compiled-in transition approves this directed source and case difference"
        };
        if !approved {
            errors.push(format!("{}: {reason}", observation.case_id));
        }
        cases.push(json!({
            "caseId":observation.case_id,"approved":approved,"exact":valid && unique && exact,
            "mode":if !approved {"rejected"} else if matched {"reviewed-transition"} else {"exact"},
            "reason":reason,"observation":observation,
        }));
    }
    for case in applicable.keys() {
        if !seen.contains(case) {
            errors.push(format!(
                "Missing observation for reviewed transition: {case}"
            ));
        }
    }
    let approved = errors.is_empty();
    json!({"schema":"graphite-semantic-transitions-v1","policy":"compiled-in",
        "baseProductIdentity":base_product_identity,"headProductIdentity":head_product_identity,"approved":approved,
        "exact":approved && cases.iter().all(|case| case["exact"] == true),
        "cases":cases,"errors":errors})
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};
    const BASE: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const HEAD: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn hash(value: &str) -> String {
        Sha256::digest(value.as_bytes())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect()
    }
    fn observation(
        case: &str,
        base_query: &str,
        head_query: &str,
        base: &str,
        head: &str,
    ) -> Observation {
        Observation {
            case_id: case.into(),
            base_query_sha256: hash(base_query),
            head_query_sha256: hash(head_query),
            base_response_sha256: hash(base),
            head_response_sha256: hash(head),
        }
    }
    fn rule(observation: &Observation) -> Rule<'_> {
        Rule {
            base_product_identity: BASE,
            head_product_identity: HEAD,
            case_id: &observation.case_id,
            base_query_sha256: &observation.base_query_sha256,
            head_query_sha256: &observation.head_query_sha256,
            base_response_sha256: &observation.base_response_sha256,
            head_response_sha256: &observation.head_response_sha256,
            reason: "Reviewed node/schema expansion and its exact fixture discovery terms",
        }
    }
    fn intended() -> Vec<Observation> {
        vec![
            observation(
                "node-count",
                "MATCH(n) RETURN count(n)",
                "MATCH(n) RETURN count(n)",
                r#"{"columns":["count(n)"],"rows":[[42]],"rowCount":1}"#,
                r#"{"columns":["count(n)"],"rows":[[43]],"rowCount":1}"#,
            ),
            observation(
                "schema",
                "MATCH(n) RETURN keys(n)",
                "MATCH(n) RETURN keys(n)",
                r#"{"rows":[[["name"]]],"rowCount":1}"#,
                r#"{"rows":[[["name","declaredType"]]],"rowCount":1}"#,
            ),
            observation(
                "discovery-terms",
                "MATCH(n) WHERE n.name='Old' RETURN n",
                "MATCH(n) WHERE n.name='New' RETURN n",
                r#"{"rows":[[{"id":1,"name":"Old"}]],"graphIds":["a","b"]}"#,
                r#"{"rows":[[{"id":1,"name":"New"}]],"graphIds":["a","b"]}"#,
            ),
        ]
    }

    #[test]
    fn default_policy_accepts_equality_and_rejects_every_unreviewed_difference() {
        let same = observation("same", "RETURN 1", "RETURN 1", "[[1]]", "[[1]]");
        let result = evaluate(BASE, HEAD, &[same]);
        assert_eq!(result["approved"], true);
        assert_eq!(result["exact"], true);
        assert_eq!(result["baseProductIdentity"], BASE);
        assert_eq!(result["headProductIdentity"], HEAD);
        assert!(result.get("baseSha").is_none());
        assert!(result.get("headTree").is_none());
        for changed in intended() {
            assert_eq!(evaluate(BASE, HEAD, &[changed])["approved"], false);
        }
    }

    #[test]
    fn both_product_identities_require_full_lowercase_sha256_even_for_equal_results() {
        let same = observation("same", "RETURN 1", "RETURN 1", "[[1]]", "[[1]]");
        for invalid in [
            "".to_owned(),
            "*".to_owned(),
            "a".repeat(40),
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ] {
            for (base, head) in [(invalid.as_str(), HEAD), (BASE, invalid.as_str())] {
                let result = evaluate(base, head, std::slice::from_ref(&same));
                assert_eq!(result["approved"], false, "{base}/{head}");
                assert_eq!(result["exact"], false);
                assert!(!result["errors"].as_array().unwrap().is_empty());
            }
        }
    }

    #[test]
    fn exact_reviewed_counts_schema_and_changed_query_terms_are_approved() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        let mut observed = changes.clone();
        observed.push(observation(
            "unchanged",
            "RETURN 1",
            "RETURN 1",
            "[[1]]",
            "[[1]]",
        ));
        let result = evaluate_rules(BASE, HEAD, &observed, &rules);
        assert_eq!(result["approved"], true);
        assert_eq!(result["exact"], false);
        assert_eq!(result["cases"][0]["mode"], "reviewed-transition");
        assert_eq!(result["cases"][3]["mode"], "exact");
    }

    #[test]
    fn commit_metadata_does_not_change_a_product_bound_expectation() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        // Different commits can have the same approved product identity. Neither
        // commit metadata nor a complete Git tree participates in this key.
        let commits = [
            "cccccccccccccccccccccccccccccccccccccccc",
            "dddddddddddddddddddddddddddddddddddddddd",
        ];
        let results = commits.map(|_| evaluate_rules(BASE, HEAD, &changes, &rules));
        assert_eq!(results[0], results[1]);
        assert_eq!(results[0]["approved"], true);
    }

    #[test]
    fn wrong_base_wrong_head_product_and_reversed_direction_are_not_approved() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        assert_eq!(
            evaluate_rules(HEAD, HEAD, &changes, &rules)["approved"],
            false
        );
        assert_eq!(
            evaluate_rules(BASE, BASE, &changes, &rules)["approved"],
            false
        );
        let mut reverse = changes.clone();
        for row in &mut reverse {
            std::mem::swap(&mut row.base_query_sha256, &mut row.head_query_sha256);
            std::mem::swap(&mut row.base_response_sha256, &mut row.head_response_sha256);
        }
        assert_eq!(
            evaluate_rules(BASE, HEAD, &reverse, &rules)["approved"],
            false
        );
    }

    #[test]
    fn unapproved_extra_changes_query_drift_or_response_drift_fail() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        let mut extra = changes.clone();
        extra.push(observation(
            "unexpected",
            "RETURN n",
            "RETURN n",
            "[[1,1]]",
            "[[1]]",
        ));
        assert_eq!(
            evaluate_rules(BASE, HEAD, &extra, &rules)["approved"],
            false
        );
        let mut query = changes.clone();
        query[2].head_query_sha256 = hash("MATCH(n) RETURN n LIMIT 1");
        assert_eq!(
            evaluate_rules(BASE, HEAD, &query, &rules)["approved"],
            false
        );
        let mut body = changes.clone();
        body[2].head_response_sha256 =
            hash(r#"{"rows":[[{"id":1,"name":"New"}]],"graphIds":["a"]}"#);
        assert_eq!(evaluate_rules(BASE, HEAD, &body, &rules)["approved"], false);
    }

    #[test]
    fn missing_duplicate_and_malformed_observations_fail_closed() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        assert_eq!(
            evaluate_rules(BASE, HEAD, &changes[..2], &rules)["approved"],
            false
        );
        let mut duplicate = changes.clone();
        duplicate.push(changes[0].clone());
        assert_eq!(
            evaluate_rules(BASE, HEAD, &duplicate, &rules)["approved"],
            false
        );
        let mut malformed = changes.clone();
        malformed[0].base_response_sha256 = "*".into();
        assert_eq!(
            evaluate_rules(BASE, HEAD, &malformed, &rules)["approved"],
            false
        );
        assert_eq!(evaluate(BASE, HEAD, &[])["approved"], false);
        assert_eq!(evaluate("*", HEAD, &changes)["approved"], false);
    }
}
