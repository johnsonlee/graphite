//! A JSON writer byte-compatible with `GsonBuilder().setPrettyPrinting().create()`.
//!
//! Both the Kotlin server and `graphite query` serialise through exactly that builder,
//! which differs from most writers in two visible ways:
//!
//! * **HTML-safe escaping is on by default.** `<`, `>`, `&`, `=` and `'` are emitted
//!   as their six-character unicode escapes (`\u003c` and friends). A JVM method
//!   name therefore serialises as `"\u003cinit\u003e"`, not `"<init>"`.
//! * **Null-valued object members are omitted** unless serialisation of nulls is enabled.
//!
//! Parsing either form yields the same value, so a structural comparison cannot see the
//! difference — which is precisely why it is easy to get wrong and worth pinning here.

use serde_json::Value as J;

/// Serialise pretty-printed, exactly as Gson's default configuration does.
pub fn to_pretty(value: &J) -> String {
    let mut out = String::new();
    write_value(&mut out, value, 0);
    out
}

fn write_value(out: &mut String, v: &J, depth: usize) {
    match v {
        J::Null => out.push_str("null"),
        J::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        J::Number(n) => out.push_str(&n.to_string()),
        J::String(s) => write_string(out, s),
        J::Array(items) => {
            if items.is_empty() {
                out.push_str("[]");
                return;
            }
            out.push_str("[\n");
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push_str(",\n");
                }
                indent(out, depth + 1);
                write_value(out, item, depth + 1);
            }
            out.push('\n');
            indent(out, depth);
            out.push(']');
        }
        J::Object(map) => {
            // Gson omits null members; a map that is all nulls still renders as `{}`.
            let entries: Vec<(&String, &J)> = map.iter().filter(|(_, v)| !v.is_null()).collect();
            if entries.is_empty() {
                out.push_str("{}");
                return;
            }
            out.push_str("{\n");
            for (i, (k, val)) in entries.iter().enumerate() {
                if i > 0 {
                    out.push_str(",\n");
                }
                indent(out, depth + 1);
                write_string(out, k);
                out.push_str(": ");
                write_value(out, val, depth + 1);
            }
            out.push('\n');
            indent(out, depth);
            out.push('}');
        }
    }
}

fn indent(out: &mut String, depth: usize) {
    for _ in 0..depth {
        out.push_str("  ");
    }
}

fn write_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000c}' => out.push_str("\\f"),
            // HTML-safe escaping, on unless `disableHtmlEscaping()` is called.
            '<' | '>' | '&' | '=' | '\'' => out.push_str(&format!("\\u{:04x}", c as u32)),
            // Gson always escapes the line separators: legal in a JSON string, but not
            // in a JavaScript one.
            '\u{2028}' | '\u{2029}' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::to_pretty;
    use serde_json::json;

    /// The escape Gson emits for one character, built rather than written literally so
    /// the expectation cannot be typo'd into the very form it is checking.
    fn esc(c: char) -> String {
        format!("\\u{:04x}", c as u32)
    }

    #[test]
    fn escapes_the_html_characters_gson_escapes() {
        assert_eq!(
            to_pretty(&json!("<init>")),
            format!("\"{}init{}\"", esc('<'), esc('>'))
        );
        assert_eq!(
            to_pretty(&json!("x&y=z")),
            format!("\"x{}y{}z\"", esc('&'), esc('='))
        );
        assert_eq!(to_pretty(&json!("it's")), format!("\"it{}s\"", esc('\'')));
        // Ordinary JSON escapes are unaffected.
        assert_eq!(to_pretty(&json!("a\"b\\c\nd")), r#""a\"b\\c\nd""#);
        assert_eq!(
            to_pretty(&json!("\u{0001}")),
            format!("\"{}\"", esc('\u{0001}'))
        );
    }

    #[test]
    fn omits_null_members_but_keeps_null_elements() {
        assert_eq!(to_pretty(&json!({"a": null})), "{}");
        assert_eq!(to_pretty(&json!({"a": 1, "b": null})), "{\n  \"a\": 1\n}");
        // Inside an array a null is a value, not a member, so it survives.
        assert_eq!(to_pretty(&json!([null])), "[\n  null\n]");
    }

    #[test]
    fn indents_two_spaces_and_collapses_empties() {
        assert_eq!(to_pretty(&json!([])), "[]");
        assert_eq!(to_pretty(&json!({})), "{}");
        assert_eq!(
            to_pretty(&json!({"rows": [{"n": 1}]})),
            "{\n  \"rows\": [\n    {\n      \"n\": 1\n    }\n  ]\n}"
        );
    }
}
