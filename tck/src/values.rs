//! Scalar/list/map portion of the TCK's value notation. Graph, temporal and spatial
//! values remain explicitly unsupported; no lossy conversion to strings or nulls.
use regex::Regex;
use serde_json::{json, Map, Number, Value};
use std::sync::OnceLock;

#[derive(Clone)]
struct Token {
    kind: &'static str,
    text: String,
}

pub fn value(text: &str) -> Result<Value, String> {
    static TOKEN: OnceLock<Regex> = OnceLock::new();
    let regex = TOKEN.get_or_init(|| Regex::new(r#"^\s*(?:(?P<string>'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*")|(?P<number>-?(?:\d+\.\d*(?:[eE][+-]?\d+)?|\d+[eE][+-]?\d+|\d+))|(?P<quoted>`(?:``|[^`])*`)|(?P<special>(?:-?Infinity|NaN)\b)|(?P<name>[\p{L}_][\p{L}\p{N}_]*)|(?P<arrow><-|->)|(?P<punct>[\[\]{},:()<>-]))"#).unwrap());
    let mut tokens = Vec::new();
    let mut pos = 0;
    while pos < text.trim_end().len() {
        let captures = regex
            .captures(&text[pos..])
            .ok_or_else(|| format!("unsupported TCK value: {text}"))?;
        let kind = [
            "string", "number", "quoted", "special", "name", "arrow", "punct",
        ]
        .into_iter()
        .find(|name| captures.name(name).is_some())
        .unwrap();
        tokens.push(Token {
            kind,
            text: captures.name(kind).unwrap().as_str().to_owned(),
        });
        pos += captures.get(0).unwrap().end();
    }
    let mut parser = Parser { tokens, pos: 0 };
    let result = parser.value()?;
    if parser.pos != parser.tokens.len() {
        return Err(format!("trailing TCK value tokens: {text}"));
    }
    Ok(result)
}

struct Parser {
    tokens: Vec<Token>,
    pos: usize,
}
impl Parser {
    fn pop(&mut self) -> Result<Token, String> {
        let token = self
            .tokens
            .get(self.pos)
            .cloned()
            .ok_or("incomplete TCK value")?;
        self.pos += 1;
        Ok(token)
    }
    fn consume(&mut self, text: &str) -> bool {
        if self
            .tokens
            .get(self.pos)
            .is_some_and(|token| token.text == text)
        {
            self.pos += 1;
            true
        } else {
            false
        }
    }
    fn value(&mut self) -> Result<Value, String> {
        let token = self.pop()?;
        match token.kind {
            "string" => return string(&token.text).map(Value::String),
            "number" => {
                if token.text.contains(['.', 'e', 'E']) {
                    let number: f64 = token.text.parse().map_err(|_| "invalid TCK number")?;
                    return Number::from_f64(number).map(Value::Number).ok_or_else(|| {
                        "Out of range float values are not JSON compliant".to_owned()
                    });
                }
                let number = token
                    .text
                    .parse::<Number>()
                    .map_err(|_| "TCK integer outside JSON number range".to_owned())?;
                if number.is_f64() {
                    return Err("TCK integer outside JSON number range".to_owned());
                }
                return Ok(Value::Number(number));
            }
            "special" => return Ok(json!({"$tck":["float", token.text]})),
            "name" if token.text == "true" => return Ok(Value::Bool(true)),
            "name" if token.text == "false" => return Ok(Value::Bool(false)),
            "name" if token.text == "null" => return Ok(Value::Null),
            _ => {}
        }
        if token.text == "(" {
            return self.node();
        }
        if token.text == "<" {
            return self.path();
        }
        if token.text == "[" && self.tokens.get(self.pos).is_some_and(|t| t.text == ":") {
            return self.relationship();
        }
        if token.text == "[" {
            let mut result = Vec::new();
            if self.consume("]") {
                return Ok(Value::Array(result));
            }
            loop {
                result.push(self.value()?);
                if self.consume("]") {
                    return Ok(Value::Array(result));
                }
                if !self.consume(",") {
                    return Err("invalid list delimiter".to_owned());
                }
            }
        }
        if token.text == "{" {
            let mut result = Map::new();
            if self.consume("}") {
                return Ok(literal_map(result));
            }
            loop {
                let key = self.pop()?;
                let key = match key.kind {
                    "string" => string(&key.text)?,
                    "name" => key.text,
                    "quoted" => key.text[1..key.text.len() - 1].replace("``", "`"),
                    _ => return Err("unsupported map key".to_owned()),
                };
                if result.contains_key(&key) || !self.consume(":") {
                    return Err("duplicate map key or invalid map delimiter".to_owned());
                }
                result.insert(key, self.value()?);
                if self.consume("}") {
                    return Ok(literal_map(result));
                }
                if !self.consume(",") {
                    return Err("invalid map delimiter".to_owned());
                }
            }
        }
        Err(format!("unsupported TCK value token: {}", token.text))
    }
    fn identifier(&mut self) -> Result<String, String> {
        let token = self.pop()?;
        match token.kind {
            "name" | "special" => Ok(token.text),
            "quoted" => Ok(token.text[1..token.text.len() - 1].replace("``", "`")),
            _ => Err("expected a graph label or relationship type".into()),
        }
    }
    fn properties(&mut self) -> Result<Value, String> {
        if self.tokens.get(self.pos).is_some_and(|t| t.text == "{") {
            let parsed = self.value()?;
            if parsed["$tck"][0] == "map" {
                Ok(parsed["$tck"][1].clone())
            } else {
                Ok(parsed)
            }
        } else {
            Ok(json!({}))
        }
    }
    fn node(&mut self) -> Result<Value, String> {
        let mut labels = Vec::new();
        while self.consume(":") {
            labels.push(self.identifier()?);
        }
        labels.sort();
        labels.dedup();
        let properties = self.properties()?;
        if !self.consume(")") {
            return Err("invalid TCK node closing delimiter".into());
        }
        Ok(json!({"$tck":["node", labels, properties]}))
    }
    fn relationship(&mut self) -> Result<Value, String> {
        if !self.consume(":") {
            return Err("TCK relationship must have a type".into());
        }
        let kind = self.identifier()?;
        let properties = self.properties()?;
        if !self.consume("]") {
            return Err("invalid TCK relationship closing delimiter".into());
        }
        Ok(json!({"$tck":["relationship", kind, properties]}))
    }
    fn path(&mut self) -> Result<Value, String> {
        let mut nodes = vec![self.value()?];
        let mut relationships = Vec::new();
        let mut directions = Vec::new();
        while !self.consume(">") {
            let backward = self.consume("<-");
            if !backward && !self.consume("-") {
                return Err("invalid TCK path relationship prefix".into());
            }
            let relationship = self.value()?;
            if relationship["$tck"][0] != "relationship" {
                return Err("TCK path requires a relationship".into());
            }
            if !(if backward {
                self.consume("-")
            } else {
                self.consume("->")
            }) {
                return Err("invalid TCK path relationship suffix".into());
            }
            let node = self.value()?;
            if node["$tck"][0] != "node" {
                return Err("TCK path requires a node".into());
            }
            relationships.push(relationship);
            nodes.push(node);
            directions.push(if backward { -1 } else { 1 });
        }
        if nodes[0]["$tck"][0] != "node" {
            return Err("TCK path must start with a node".into());
        }
        Ok(json!({"$tck":["path", nodes, relationships, directions]}))
    }
}

/// Literal maps containing the reserved wire key are escaped, so they never
/// compare equal to graph values or non-finite numbers with a similar shape.
pub fn literal_map(values: Map<String, Value>) -> Value {
    if values.contains_key("$tck") {
        json!({"$tck":["map", values]})
    } else {
        Value::Object(values)
    }
}

fn string(text: &str) -> Result<String, String> {
    let mut chars = text[1..text.len() - 1].chars().peekable();
    let mut result = String::new();
    while let Some(c) = chars.next() {
        if c != '\\' {
            result.push(c);
            continue;
        }
        let escaped = chars.next().ok_or("incomplete TCK string escape")?;
        match escaped {
            '\\' | '\'' | '"' => result.push(escaped),
            'a' => result.push('\x07'),
            'b' => result.push('\x08'),
            'f' => result.push('\x0c'),
            'n' => result.push('\n'),
            'r' => result.push('\r'),
            't' => result.push('\t'),
            'v' => result.push('\x0b'),
            '\n' => {}
            'x' | 'u' | 'U' => {
                let count = match escaped {
                    'x' => 2,
                    'u' => 4,
                    _ => 8,
                };
                let digits: String = chars.by_ref().take(count).collect();
                if digits.len() != count {
                    return Err("incomplete TCK Unicode escape".to_owned());
                }
                let value =
                    u32::from_str_radix(&digits, 16).map_err(|_| "invalid TCK Unicode escape")?;
                result.push(char::from_u32(value).ok_or("invalid TCK Unicode scalar")?);
            }
            '0'..='7' => {
                let mut digits = escaped.to_string();
                while digits.len() < 3 && chars.peek().is_some_and(|c| ('0'..='7').contains(c)) {
                    digits.push(chars.next().unwrap());
                }
                result.push(
                    char::from_u32(u32::from_str_radix(&digits, 8).unwrap())
                        .ok_or("invalid TCK octal escape")?,
                );
            }
            _ => {
                result.push('\\');
                result.push(escaped);
            }
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn graph_values_preserve_labels_properties_paths_and_value_kind() {
        assert_eq!(
            value("(:B:A {x: 1})").unwrap(),
            json!({"$tck":["node",["A","B"],{"x":1}]})
        );
        assert_eq!(
            value("[:R {x: null}]").unwrap(),
            json!({"$tck":["relationship","R",{"x":null}]})
        );
        let path = value("<(:A)-[:R]->(:B)<-[:S]-()>").unwrap();
        assert_eq!(path["$tck"][0], "path");
        assert_eq!(path["$tck"][1].as_array().unwrap().len(), 3);
        assert_eq!(path["$tck"][3], json!([1, -1]));
        assert_ne!(
            value("(:Person)").unwrap(),
            value("{'$tck': ['node', ['Person'], {}]}").unwrap()
        );
        assert_eq!(value("NaN").unwrap(), json!({"$tck":["float","NaN"]}));
        assert_eq!(
            value("-Infinity").unwrap(),
            json!({"$tck":["float","-Infinity"]})
        );
    }

    #[test]
    fn nested_values_preserve_types_null_and_strings() {
        assert_eq!(
            value("{x: [true, null, 1, 1.0, 'null'], y: 'a\\'b'}").unwrap(),
            json!({"x":[true,null,1,1.0,"null"],"y":"a'b"})
        );
        assert!(value("1").unwrap().as_number().unwrap().is_i64());
        assert!(value("1.0").unwrap().as_number().unwrap().is_f64());
        for input in ["date('2020-01-01')", "1 2", "{x:1,x:2}"] {
            assert!(value(input).is_err(), "{input}");
        }
    }
    #[test]
    fn unicode_and_escaped_strings_are_decoded() {
        assert_eq!(value(r"'\u263a\n\x41\101'").unwrap(), json!("☺\nAA"));
        assert_eq!(value(r"'\q'").unwrap(), json!("\\q"));
    }
}
