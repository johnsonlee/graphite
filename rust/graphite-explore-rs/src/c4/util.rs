//! Kotlin/Java string, ordering and JSON (Gson) compatibility helpers.

use serde_json::Value;
use std::cmp::Ordering;

/// Java `String.compareTo` (UTF-16 code unit order).
#[inline]
pub fn jcmp(a: &str, b: &str) -> Ordering {
    graphite_storage::strings::java_cmp(a, b)
}

/// Sorts strings using Java string ordering (stable).
pub fn sort_java(values: &mut [String]) {
    values.sort_by(|a, b| jcmp(a, b));
}

/// Kotlin `sorted()` on a string collection.
pub fn sorted_java<I: IntoIterator<Item = String>>(values: I) -> Vec<String> {
    let mut out: Vec<String> = values.into_iter().collect();
    sort_java(&mut out);
    out
}

/// Kotlin `distinct()` preserving first occurrence order.
pub fn distinct<I: IntoIterator<Item = String>>(values: I) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for v in values {
        if seen.insert(v.clone()) {
            out.push(v);
        }
    }
    out
}

/// Kotlin `toSortedSet()` on strings → sorted, de-duplicated vector.
pub fn sorted_set<I: IntoIterator<Item = String>>(values: I) -> Vec<String> {
    let mut out = sorted_java(values);
    out.dedup();
    out
}

/// Kotlin `maxByOrNull`: returns the FIRST element with the maximal key.
pub fn first_max_by<T, K: Ord, F: Fn(&T) -> K>(items: impl IntoIterator<Item = T>, key: F) -> Option<T> {
    let mut best: Option<(T, K)> = None;
    for item in items {
        let k = key(&item);
        match &best {
            Some((_, bk)) if k <= *bk => {}
            _ => best = Some((item, k)),
        }
    }
    best.map(|(t, _)| t)
}

/// Kotlin `isBlank()`.
#[inline]
pub fn is_blank(s: &str) -> bool {
    s.chars().all(java_is_whitespace)
}

/// Java `Character.isWhitespace` approximation used by Kotlin `isBlank`/`trim`.
#[inline]
pub fn java_is_whitespace(c: char) -> bool {
    c.is_whitespace() || ('\u{1C}'..='\u{1F}').contains(&c)
}

/// Kotlin `String.trim()`.
#[inline]
pub fn ktrim(s: &str) -> &str {
    s.trim_matches(java_is_whitespace)
}

/// Kotlin `substringAfterLast(delim)` (whole string when missing).
#[inline]
pub fn substring_after_last(s: &str, delim: char) -> &str {
    match s.rfind(delim) {
        Some(i) => &s[i + delim.len_utf8()..],
        None => s,
    }
}

/// Kotlin `substringBeforeLast(delim, missing)`.
#[inline]
pub fn substring_before_last<'a>(s: &'a str, delim: char, missing: &'a str) -> &'a str {
    match s.rfind(delim) {
        Some(i) => &s[..i],
        None => missing,
    }
}

/// Kotlin `substringBefore(delim)` (whole string when missing).
#[inline]
pub fn substring_before(s: &str, delim: char) -> &str {
    match s.find(delim) {
        Some(i) => &s[..i],
        None => s,
    }
}

/// Kotlin `substringAfter(delim)` (whole string when missing).
#[inline]
pub fn substring_after(s: &str, delim: char) -> &str {
    match s.find(delim) {
        Some(i) => &s[i + delim.len_utf8()..],
        None => s,
    }
}

#[inline]
pub fn remove_prefix<'a>(s: &'a str, prefix: &str) -> &'a str {
    s.strip_prefix(prefix).unwrap_or(s)
}

#[inline]
pub fn remove_suffix<'a>(s: &'a str, suffix: &str) -> &'a str {
    s.strip_suffix(suffix).unwrap_or(s)
}

/// Kotlin `replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }`.
pub fn title_first(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        None => String::new(),
        Some(c) => {
            let mut out = String::with_capacity(s.len());
            if c.is_lowercase() {
                out.extend(c.to_uppercase());
            } else {
                out.push(c);
            }
            out.push_str(chars.as_str());
            out
        }
    }
}

/// Kotlin `replaceFirstChar { it.uppercase() }`.
pub fn upper_first(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        None => String::new(),
        Some(c) => {
            let mut out = String::with_capacity(s.len());
            out.extend(c.to_uppercase());
            out.push_str(chars.as_str());
            out
        }
    }
}

/// Java `String.length()` (UTF-16 code units).
#[inline]
pub fn jlen(s: &str) -> usize {
    s.encode_utf16().count()
}

/// Kotlin `String.toLongOrNull()` (radix 10, optional sign, no whitespace).
pub fn kotlin_to_long(s: &str) -> Option<i64> {
    if s.is_empty() {
        return None;
    }
    let (neg, digits) = match s.as_bytes()[0] {
        b'-' => (true, &s[1..]),
        b'+' => (false, &s[1..]),
        _ => (false, s),
    };
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let mut value: i64 = 0;
    for b in digits.bytes() {
        let d = (b - b'0') as i64;
        value = value.checked_mul(10)?;
        value = if neg { value.checked_sub(d)? } else { value.checked_add(d)? };
    }
    Some(value)
}

/// Kotlin `String.toIntOrNull()`.
pub fn kotlin_to_int(s: &str) -> Option<i64> {
    let v = kotlin_to_long(s)?;
    if v < i32::MIN as i64 || v > i32::MAX as i64 {
        None
    } else {
        Some(v)
    }
}

/// Java `Double.toString`.
pub fn java_double_to_string(d: f64) -> String {
    if d.is_nan() {
        return "NaN".to_string();
    }
    if d.is_infinite() {
        return if d > 0.0 { "Infinity".to_string() } else { "-Infinity".to_string() };
    }
    if d == 0.0 {
        return if d.is_sign_negative() { "-0.0".to_string() } else { "0.0".to_string() };
    }
    let sci = format!("{:e}", d.abs());
    let (mantissa, exp) = sci.split_once('e').unwrap_or((sci.as_str(), "0"));
    let exp: i32 = exp.parse().unwrap_or(0);
    let digits: String = mantissa.chars().filter(|c| c.is_ascii_digit()).collect();
    let digits = digits.trim_end_matches('0');
    let digits = if digits.is_empty() { "0" } else { digits };
    let abs = d.abs();
    let mut out = String::new();
    if d.is_sign_negative() {
        out.push('-');
    }
    if (1e-3..1e7).contains(&abs) {
        let n = exp + 1;
        if n <= 0 {
            out.push_str("0.");
            for _ in 0..(-n) {
                out.push('0');
            }
            out.push_str(digits);
        } else if (n as usize) >= digits.len() {
            out.push_str(digits);
            for _ in 0..(n as usize - digits.len()) {
                out.push('0');
            }
            out.push_str(".0");
        } else {
            out.push_str(&digits[..n as usize]);
            out.push('.');
            out.push_str(&digits[n as usize..]);
        }
    } else {
        out.push_str(&digits[..1]);
        out.push('.');
        if digits.len() > 1 {
            out.push_str(&digits[1..]);
        } else {
            out.push('0');
        }
        out.push('E');
        out.push_str(&exp.to_string());
    }
    out
}

/// Kotlin `Any?.toString()` for a JSON value (Gson-materialised object model).
pub fn kotlin_to_string(v: &Value) -> String {
    match v {
        Value::Null => "null".to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => json_number_to_java(n),
        Value::String(s) => s.clone(),
        Value::Array(items) => {
            let parts: Vec<String> = items.iter().map(kotlin_to_string).collect();
            format!("[{}]", parts.join(", "))
        }
        Value::Object(map) => {
            let parts: Vec<String> = map.iter().map(|(k, v)| format!("{k}={}", kotlin_to_string(v))).collect();
            format!("{{{}}}", parts.join(", "))
        }
    }
}

/// Formats a JSON number the way Java would print the boxed value.
pub fn json_number_to_java(n: &serde_json::Number) -> String {
    if let Some(i) = n.as_i64() {
        i.to_string()
    } else if let Some(u) = n.as_u64() {
        u.to_string()
    } else {
        java_double_to_string(n.as_f64().unwrap_or(0.0))
    }
}

/// Gson-compatible JSON writer. `pretty` mirrors `GsonBuilder().setPrettyPrinting()`
/// (two-space indent, `": "` separators); `html_safe` mirrors Gson's default
/// HTML escaping (`<>&='` as `\uXXXX`), which the raw `JsonWriter` does not do.
pub fn gson_json(value: &Value, pretty: bool, html_safe: bool) -> String {
    let mut out = String::new();
    write_gson(value, pretty, html_safe, 0, &mut out);
    out
}

fn write_gson(value: &Value, pretty: bool, html_safe: bool, depth: usize, out: &mut String) {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => out.push_str(&json_number_to_java(n)),
        Value::String(s) => gson_escape(s, html_safe, out),
        Value::Array(items) => {
            out.push('[');
            if items.is_empty() {
                out.push(']');
                return;
            }
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                if pretty {
                    gson_newline(depth + 1, out);
                }
                write_gson(item, pretty, html_safe, depth + 1, out);
            }
            if pretty {
                gson_newline(depth, out);
            }
            out.push(']');
        }
        Value::Object(map) => {
            out.push('{');
            if map.is_empty() {
                out.push('}');
                return;
            }
            for (i, (k, v)) in map.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                if pretty {
                    gson_newline(depth + 1, out);
                }
                gson_escape(k, html_safe, out);
                out.push(':');
                if pretty {
                    out.push(' ');
                }
                write_gson(v, pretty, html_safe, depth + 1, out);
            }
            if pretty {
                gson_newline(depth, out);
            }
            out.push('}');
        }
    }
}

fn gson_newline(depth: usize, out: &mut String) {
    out.push('\n');
    for _ in 0..depth {
        out.push_str("  ");
    }
}

fn gson_escape(s: &str, html_safe: bool, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{c}' => out.push_str("\\f"),
            '\u{2028}' => out.push_str("\\u2028"),
            '\u{2029}' => out.push_str("\\u2029"),
            '<' if html_safe => out.push_str("\\u003c"),
            '>' if html_safe => out.push_str("\\u003e"),
            '&' if html_safe => out.push_str("\\u0026"),
            '=' if html_safe => out.push_str("\\u003d"),
            '\'' if html_safe => out.push_str("\\u0027"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Splits text into lines like Kotlin `lineSequence()` (`\r\n`, `\n`, `\r`).
pub fn kotlin_lines(text: &str) -> Vec<&str> {
    let mut lines = Vec::new();
    let bytes = text.as_bytes();
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\r' => {
                lines.push(&text[start..i]);
                if i + 1 < bytes.len() && bytes[i + 1] == b'\n' {
                    i += 1;
                }
                start = i + 1;
            }
            b'\n' => {
                lines.push(&text[start..i]);
                start = i + 1;
            }
            _ => {}
        }
        i += 1;
    }
    lines.push(&text[start..]);
    lines
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn java_double_formatting() {
        assert_eq!(java_double_to_string(5.0), "5.0");
        assert_eq!(java_double_to_string(0.0), "0.0");
        assert_eq!(java_double_to_string(12345678.0), "1.2345678E7");
        assert_eq!(java_double_to_string(10000000.0), "1.0E7");
        assert_eq!(java_double_to_string(0.5), "0.5");
        assert_eq!(java_double_to_string(123.456), "123.456");
        assert_eq!(java_double_to_string(0.0001), "1.0E-4");
        assert_eq!(java_double_to_string(-2.0), "-2.0");
    }

    #[test]
    fn gson_pretty_and_compact() {
        let v = json!({"a": 1, "b": ["x", "y"], "c": {}, "d": [], "e": "<=>&'"});
        assert_eq!(
            gson_json(&v, true, true),
            "{\n  \"a\": 1,\n  \"b\": [\n    \"x\",\n    \"y\"\n  ],\n  \"c\": {},\n  \"d\": [],\n  \"e\": \"\\u003c\\u003d\\u003e\\u0026\\u0027\"\n}"
        );
        assert_eq!(gson_json(&v, false, false), "{\"a\":1,\"b\":[\"x\",\"y\"],\"c\":{},\"d\":[],\"e\":\"<=>&'\"}");
    }

    #[test]
    fn kotlin_string_helpers() {
        assert_eq!(substring_after_last("a.b.c", '.'), "c");
        assert_eq!(substring_after_last("abc", '.'), "abc");
        assert_eq!(substring_before_last("abc", '.', ""), "");
        assert_eq!(substring_before_last("a.b", '.', ""), "a");
        assert_eq!(kotlin_to_long("+3"), Some(3));
        assert_eq!(kotlin_to_long(" 3"), None);
        assert_eq!(kotlin_to_long("3.0"), None);
        assert_eq!(first_max_by(vec![("a", 2), ("b", 3), ("c", 3)], |x| x.1).unwrap().0, "b");
        assert_eq!(kotlin_lines("a\nb\r\nc\n"), vec!["a", "b", "c", ""]);
    }
}

/// `slugify(v)` — lowercase, every non-alphanumeric run becomes one dash, ends trimmed.
pub fn slugify(v: &str) -> String {
    let mut out = String::with_capacity(v.len());
    let mut pending_dash = false;
    for c in v.chars() {
        if c.is_ascii_alphanumeric() {
            let lower = c.to_ascii_lowercase();
            if pending_dash && !out.is_empty() {
                out.push('-');
            }
            pending_dash = false;
            out.push(lower);
        } else {
            pending_dash = true;
        }
    }
    out
}

/// `diagramId(v)` — every character outside `[A-Za-z0-9_]` becomes a single underscore.
pub fn diagram_id(v: &str) -> String {
    v.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '_' { c } else { '_' })
        .collect()
}

/// Split camelCase, dashes and underscores into capitalised words.
pub fn humanize_identifier(id: &str) -> String {
    let mut spaced = String::with_capacity(id.len() * 2);
    let chars: Vec<char> = id.chars().collect();
    for (i, c) in chars.iter().enumerate() {
        if c.is_uppercase() && i > 0 {
            let prev = chars[i - 1];
            if prev.is_lowercase() || prev.is_ascii_digit() {
                spaced.push(' ');
            }
        }
        spaced.push(if *c == '-' || *c == '_' { ' ' } else { *c });
    }
    spaced
        .split(' ')
        .filter(|t| !t.trim().is_empty())
        .map(|t| {
            let lower = t.to_lowercase();
            let mut cs = lower.chars();
            match cs.next() {
                Some(f) => f.to_uppercase().collect::<String>() + cs.as_str(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Strip a trailing version suffix (`lucene-core-9.12.0` -> `lucene-core`).
pub fn artifact_base_name(name: &str) -> String {
    let bytes: Vec<char> = name.chars().collect();
    // Find the last '-' followed by a digit, then require the remainder to look like a version.
    for i in (0..bytes.len()).rev() {
        if bytes[i] == '-' && bytes.get(i + 1).is_some_and(|c| c.is_ascii_digit()) {
            let tail: String = bytes[i + 1..].iter().collect();
            if tail
                .split(['.', '-'])
                .all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric()))
            {
                return bytes[..i].iter().collect();
            }
        }
    }
    name.to_string()
}

/// Artifact display label: version stripped, words title-cased, short words upper-cased.
pub fn humanize_artifact_label(name: &str) -> String {
    artifact_base_name(name)
        .split(['-', '_'])
        .filter(|t| !t.trim().is_empty())
        .map(|t| {
            if t.len() <= 3 {
                t.to_uppercase()
            } else {
                let mut cs = t.chars();
                match cs.next() {
                    Some(f) if f.is_lowercase() => f.to_uppercase().collect::<String>() + cs.as_str(),
                    _ => t.to_string(),
                }
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Same as `humanize_artifact_label` but always title-cased (no acronym rule).
pub fn humanize_subject_artifact_label(name: &str) -> String {
    artifact_base_name(name)
        .split(['-', '_'])
        .filter(|t| !t.trim().is_empty())
        .map(|t| {
            let mut cs = t.chars();
            match cs.next() {
                Some(f) if f.is_lowercase() => f.to_uppercase().collect::<String>() + cs.as_str(),
                _ => t.to_string(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod added_tests {
    use super::*;

    #[test]
    fn slugify_collapses_runs_and_trims() {
        assert_eq!(slugify("Order Service"), "order-service");
        assert_eq!(slugify("container:application-runtime"), "container-application-runtime");
        assert_eq!(slugify("--a--"), "a");
        assert_eq!(slugify("!!!"), "");
    }

    #[test]
    fn diagram_id_replaces_each_character() {
        assert_eq!(diagram_id("system:library"), "system_library");
        assert_eq!(diagram_id("a.b-c"), "a_b_c");
    }

    #[test]
    fn humanize_splits_camel_case_and_separators() {
        assert_eq!(humanize_identifier("orderService"), "Order Service");
        assert_eq!(humanize_identifier("order-service"), "Order Service");
        assert_eq!(humanize_identifier("api"), "Api");
    }

    #[test]
    fn artifact_labels_strip_versions() {
        assert_eq!(artifact_base_name("lucene-core-9.12.0"), "lucene-core");
        assert_eq!(artifact_base_name("postgresql-42.7.3"), "postgresql");
        assert_eq!(artifact_base_name("plain"), "plain");
        assert_eq!(humanize_artifact_label("lucene-core-9.12.0"), "Lucene Core");
    }

    #[test]
    fn short_tokens_become_acronyms_only_for_artifacts() {
        assert_eq!(humanize_artifact_label("api-gateway"), "API Gateway");
        assert_eq!(humanize_subject_artifact_label("api-gateway"), "Api Gateway");
    }
}
