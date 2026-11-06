//! Scalar/list/map portion of the TCK's value notation. Graph, temporal and spatial
//! values remain explicitly unsupported; no lossy conversion to strings or nulls.
use regex::Regex;
use serde_json::{Map, Number, Value};
use std::sync::OnceLock;

#[derive(Clone)]
struct Token {
    kind: &'static str,
    text: String,
}

pub fn value(text: &str) -> Result<Value, String> {
    static TOKEN: OnceLock<Regex> = OnceLock::new();
    let regex = TOKEN.get_or_init(|| Regex::new(r#"^\s*(?:(?P<string>'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*")|(?P<number>-?(?:\d+\.\d*(?:[eE][+-]?\d+)?|\d+[eE][+-]?\d+|\d+))|(?P<name>[A-Za-z_][A-Za-z_0-9]*)|(?P<punct>[\[\]{},:]))"#).unwrap());
    let mut tokens = Vec::new();
    let mut pos = 0;
    while pos < text.trim_end().len() {
        let captures = regex
            .captures(&text[pos..])
            .ok_or_else(|| format!("unsupported TCK value: {text}"))?;
        let kind = ["string", "number", "name", "punct"]
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
            "name" if token.text == "true" => return Ok(Value::Bool(true)),
            "name" if token.text == "false" => return Ok(Value::Bool(false)),
            "name" if token.text == "null" => return Ok(Value::Null),
            _ => {}
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
                return Ok(Value::Object(result));
            }
            loop {
                let key = self.pop()?;
                let key = match key.kind {
                    "string" => string(&key.text)?,
                    "name" => key.text,
                    _ => return Err("unsupported map key".to_owned()),
                };
                if result.contains_key(&key) || !self.consume(":") {
                    return Err("duplicate map key or invalid map delimiter".to_owned());
                }
                result.insert(key, self.value()?);
                if self.consume("}") {
                    return Ok(Value::Object(result));
                }
                if !self.consume(",") {
                    return Err("invalid map delimiter".to_owned());
                }
            }
        }
        Err(format!("unsupported TCK value token: {}", token.text))
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
    fn nested_values_preserve_types_null_and_strings() {
        assert_eq!(
            value("{x: [true, null, 1, 1.0, 'null'], y: 'a\\'b'}").unwrap(),
            json!({"x":[true,null,1,1.0,"null"],"y":"a'b"})
        );
        assert!(value("1").unwrap().as_number().unwrap().is_i64());
        assert!(value("1.0").unwrap().as_number().unwrap().is_f64());
        for input in ["(:Person)", "NaN", "date('2020-01-01')", "1 2", "{x:1,x:2}"] {
            assert!(value(input).is_err(), "{input}");
        }
    }
    #[test]
    fn unicode_and_escaped_strings_are_decoded() {
        assert_eq!(value(r"'\u263a\n\x41\101'").unwrap(), json!("☺\nAA"));
        assert_eq!(value(r"'\q'").unwrap(), json!("\\q"));
    }
}
