//! Hand-written tokenizer mirroring `CypherLexer.g4`.
//!
//! Behavioural notes (all reproduced from the ANTLR grammar):
//! - Keywords are case-insensitive; the *original* spelling is kept in the token text so that
//!   keywords used as symbolic names (`RETURN n AS order`) round-trip verbatim.
//! - Identifiers are ASCII only: `[A-Za-z_][A-Za-z0-9_]*`.
//! - `INTEGER_LITERAL : '0' | [1-9][0-9]*` — `007` lexes as three tokens (`0`, `0`, `7`),
//!   but `007.5` and `007e1` are single float tokens.
//! - `OCTAL_INTEGER : '0o' [0-9]+` accepts `8`/`9` (they fail later, at literal decoding).
//! - An unterminated block comment is not a comment at all: `/` and `*` are emitted as operators.
//! - Lexer errors carry the 0-based column of the offending token start in its line
//!   (ANTLR's `charPositionInLine`) and the message `token recognition error at: '...'`.

use crate::error::{CypherError, CypherResult};

/// Keyword tokens of `CypherLexer.g4`. Every keyword is also a legal `symbolicName`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Keyword {
    Call,
    Yield,
    Filter,
    Extract,
    Count,
    Any,
    None,
    Single,
    All,
    Asc,
    Ascending,
    By,
    Create,
    Delete,
    Desc,
    Descending,
    Detach,
    Exists,
    Limit,
    Match,
    Merge,
    On,
    Optional,
    Order,
    Remove,
    Return,
    Set,
    Skip,
    Where,
    With,
    Union,
    Unwind,
    And,
    As,
    Contains,
    Distinct,
    Ends,
    In,
    Is,
    Not,
    Or,
    Starts,
    Xor,
    False,
    True,
    Null,
    Constraint,
    Do,
    For,
    Require,
    Unique,
    Case,
    When,
    Then,
    Else,
    End,
    Mandatory,
    Scalar,
    Of,
    Add,
    Drop,
}

impl Keyword {
    /// Look up a keyword by its (case-insensitive) spelling.
    pub fn from_ident(text: &str) -> Option<Keyword> {
        let upper = text.to_ascii_uppercase();
        Some(match upper.as_str() {
            "CALL" => Keyword::Call,
            "YIELD" => Keyword::Yield,
            "FILTER" => Keyword::Filter,
            "EXTRACT" => Keyword::Extract,
            "COUNT" => Keyword::Count,
            "ANY" => Keyword::Any,
            "NONE" => Keyword::None,
            "SINGLE" => Keyword::Single,
            "ALL" => Keyword::All,
            "ASC" => Keyword::Asc,
            "ASCENDING" => Keyword::Ascending,
            "BY" => Keyword::By,
            "CREATE" => Keyword::Create,
            "DELETE" => Keyword::Delete,
            "DESC" => Keyword::Desc,
            "DESCENDING" => Keyword::Descending,
            "DETACH" => Keyword::Detach,
            "EXISTS" => Keyword::Exists,
            "LIMIT" => Keyword::Limit,
            "MATCH" => Keyword::Match,
            "MERGE" => Keyword::Merge,
            "ON" => Keyword::On,
            "OPTIONAL" => Keyword::Optional,
            "ORDER" => Keyword::Order,
            "REMOVE" => Keyword::Remove,
            "RETURN" => Keyword::Return,
            "SET" => Keyword::Set,
            "SKIP" => Keyword::Skip,
            "WHERE" => Keyword::Where,
            "WITH" => Keyword::With,
            "UNION" => Keyword::Union,
            "UNWIND" => Keyword::Unwind,
            "AND" => Keyword::And,
            "AS" => Keyword::As,
            "CONTAINS" => Keyword::Contains,
            "DISTINCT" => Keyword::Distinct,
            "ENDS" => Keyword::Ends,
            "IN" => Keyword::In,
            "IS" => Keyword::Is,
            "NOT" => Keyword::Not,
            "OR" => Keyword::Or,
            "STARTS" => Keyword::Starts,
            "XOR" => Keyword::Xor,
            "FALSE" => Keyword::False,
            "TRUE" => Keyword::True,
            "NULL" => Keyword::Null,
            "CONSTRAINT" => Keyword::Constraint,
            "DO" => Keyword::Do,
            "FOR" => Keyword::For,
            "REQUIRE" => Keyword::Require,
            "UNIQUE" => Keyword::Unique,
            "CASE" => Keyword::Case,
            "WHEN" => Keyword::When,
            "THEN" => Keyword::Then,
            "ELSE" => Keyword::Else,
            "END" => Keyword::End,
            "MANDATORY" => Keyword::Mandatory,
            "SCALAR" => Keyword::Scalar,
            "OF" => Keyword::Of,
            "ADD" => Keyword::Add,
            "DROP" => Keyword::Drop,
            _ => return Option::None,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenKind {
    /// `ID`
    Ident,
    /// `ESC_LITERAL` — text includes the backticks.
    EscIdent,
    /// `STRING_LITERAL` — text includes the quotes, escapes are *not* decoded.
    Str,
    /// `INTEGER_LITERAL`
    Int,
    /// `HEX_INTEGER`
    Hex,
    /// `OCTAL_INTEGER`
    Octal,
    /// `FLOAT_LITERAL`
    Float,
    Keyword(Keyword),
    /// `=`
    Assign,
    /// `+=`
    AddAssign,
    /// `=~`
    Regex,
    /// `<=`
    Le,
    /// `>=`
    Ge,
    /// `<>`
    Ne,
    /// `>`
    Gt,
    /// `<`
    Lt,
    /// `..`
    Range,
    /// `;`
    Semi,
    /// `.`
    Dot,
    /// `,`
    Comma,
    LParen,
    RParen,
    LBrace,
    RBrace,
    LBrack,
    RBrack,
    /// `-`
    Sub,
    /// `+`
    Plus,
    /// `/`
    Div,
    /// `%`
    Mod,
    /// `^`
    Caret,
    /// `*`
    Mult,
    /// `:`
    Colon,
    /// `|`
    Stick,
    /// `$`
    Dollar,
    Eof,
}

impl TokenKind {
    /// `symbolicName : ID | ESC_LITERAL | <every keyword>`
    pub fn is_symbolic_name(self) -> bool {
        matches!(
            self,
            TokenKind::Ident | TokenKind::EscIdent | TokenKind::Keyword(_)
        )
    }

    /// `integerLiteral : INTEGER_LITERAL | HEX_INTEGER | OCTAL_INTEGER`
    pub fn is_integer_literal(self) -> bool {
        matches!(self, TokenKind::Int | TokenKind::Hex | TokenKind::Octal)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Token {
    pub kind: TokenKind,
    /// Original source text of the token (empty for EOF).
    pub text: String,
    /// 1-based line.
    pub line: usize,
    /// 0-based column in line (in characters), like ANTLR's `charPositionInLine`.
    pub col: usize,
}

impl Token {
    /// Text as ANTLR prints it in error messages.
    pub fn display(&self) -> String {
        if self.kind == TokenKind::Eof {
            "<EOF>".to_string()
        } else {
            self.text.clone()
        }
    }
}

struct Lexer {
    chars: Vec<char>,
    i: usize,
    line: usize,
    col: usize,
    tokens: Vec<Token>,
}

impl Lexer {
    fn peek(&self, off: usize) -> Option<char> {
        self.chars.get(self.i + off).copied()
    }

    fn bump(&mut self) {
        if let Some(c) = self.chars.get(self.i) {
            if *c == '\n' {
                self.line += 1;
                self.col = 0;
            } else {
                self.col += 1;
            }
            self.i += 1;
        }
    }

    fn bump_n(&mut self, n: usize) {
        for _ in 0..n {
            self.bump();
        }
    }

    fn text_from(&self, start: usize) -> String {
        self.chars[start..self.i].iter().collect()
    }

    fn push(&mut self, kind: TokenKind, start: usize, line: usize, col: usize) {
        let text = self.text_from(start);
        self.tokens.push(Token {
            kind,
            text,
            line,
            col,
        });
    }

    fn error(&self, start: usize, col: usize) -> CypherError {
        let text: String = self.chars[start..self.i].iter().collect();
        CypherError::Parse {
            position: col,
            message: format!("token recognition error at: '{text}'"),
        }
    }

    fn run(mut self) -> CypherResult<Vec<Token>> {
        while let Some(c) = self.peek(0) {
            let start = self.i;
            let (line, col) = (self.line, self.col);
            match c {
                ' ' | '\t' | '\r' | '\n' | '\u{000C}' => self.bump(),
                '/' if self.peek(1) == Some('*') => {
                    // Non-greedy `/* ... */`; without a terminator this is not a comment at all.
                    let mut j = self.i + 2;
                    let mut closed = false;
                    while j + 1 < self.chars.len() {
                        if self.chars[j] == '*' && self.chars[j + 1] == '/' {
                            closed = true;
                            break;
                        }
                        j += 1;
                    }
                    if closed {
                        let len = j + 2 - self.i;
                        self.bump_n(len);
                    } else {
                        self.bump();
                        self.push(TokenKind::Div, start, line, col);
                    }
                }
                '/' if self.peek(1) == Some('/') => {
                    while let Some(ch) = self.peek(0) {
                        if ch == '\r' || ch == '\n' {
                            break;
                        }
                        self.bump();
                    }
                }
                '\'' | '"' => self.string(c, start, line, col)?,
                '`' => {
                    self.bump();
                    loop {
                        match self.peek(0) {
                            Some('`') => {
                                self.bump();
                                break;
                            }
                            Some(_) => self.bump(),
                            Option::None => return Err(self.error(start, col)),
                        }
                    }
                    self.push(TokenKind::EscIdent, start, line, col);
                }
                '0'..='9' => self.number(start, line, col),
                '.' => {
                    if self.peek(1).is_some_and(|d| d.is_ascii_digit()) {
                        self.bump();
                        self.digits();
                        self.exponent();
                        self.push(TokenKind::Float, start, line, col);
                    } else if self.peek(1) == Some('.') {
                        self.bump_n(2);
                        self.push(TokenKind::Range, start, line, col);
                    } else {
                        self.bump();
                        self.push(TokenKind::Dot, start, line, col);
                    }
                }
                c if c.is_ascii_alphabetic() || c == '_' => {
                    while self
                        .peek(0)
                        .is_some_and(|ch| ch.is_ascii_alphanumeric() || ch == '_')
                    {
                        self.bump();
                    }
                    let text = self.text_from(start);
                    let kind = match Keyword::from_ident(&text) {
                        Some(k) => TokenKind::Keyword(k),
                        Option::None => TokenKind::Ident,
                    };
                    self.tokens.push(Token {
                        kind,
                        text,
                        line,
                        col,
                    });
                }
                _ => {
                    let two = |l: &Self, second: char| l.peek(1) == Some(second);
                    let (kind, len) = match c {
                        '=' if two(&self, '~') => (TokenKind::Regex, 2),
                        '=' => (TokenKind::Assign, 1),
                        '+' if two(&self, '=') => (TokenKind::AddAssign, 2),
                        '+' => (TokenKind::Plus, 1),
                        '<' if two(&self, '=') => (TokenKind::Le, 2),
                        '<' if two(&self, '>') => (TokenKind::Ne, 2),
                        '<' => (TokenKind::Lt, 1),
                        '>' if two(&self, '=') => (TokenKind::Ge, 2),
                        '>' => (TokenKind::Gt, 1),
                        ';' => (TokenKind::Semi, 1),
                        ',' => (TokenKind::Comma, 1),
                        '(' => (TokenKind::LParen, 1),
                        ')' => (TokenKind::RParen, 1),
                        '{' => (TokenKind::LBrace, 1),
                        '}' => (TokenKind::RBrace, 1),
                        '[' => (TokenKind::LBrack, 1),
                        ']' => (TokenKind::RBrack, 1),
                        '-' => (TokenKind::Sub, 1),
                        '/' => (TokenKind::Div, 1),
                        '%' => (TokenKind::Mod, 1),
                        '^' => (TokenKind::Caret, 1),
                        '*' => (TokenKind::Mult, 1),
                        ':' => (TokenKind::Colon, 1),
                        '|' => (TokenKind::Stick, 1),
                        '$' => (TokenKind::Dollar, 1),
                        _ => {
                            self.bump();
                            return Err(self.error(start, col));
                        }
                    };
                    self.bump_n(len);
                    self.push(kind, start, line, col);
                }
            }
        }
        self.tokens.push(Token {
            kind: TokenKind::Eof,
            text: String::new(),
            line: self.line,
            col: self.col,
        });
        Ok(self.tokens)
    }

    fn digits(&mut self) {
        while self.peek(0).is_some_and(|d| d.is_ascii_digit()) {
            self.bump();
        }
    }

    /// `ExponentPart : [e] [+-]? [0-9]+` — consumed only when complete.
    fn exponent(&mut self) -> bool {
        if !matches!(self.peek(0), Some('e') | Some('E')) {
            return false;
        }
        let mut off = 1;
        if matches!(self.peek(off), Some('+') | Some('-')) {
            off += 1;
        }
        if !self.peek(off).is_some_and(|d| d.is_ascii_digit()) {
            return false;
        }
        self.bump_n(off);
        self.digits();
        true
    }

    fn number(&mut self, start: usize, line: usize, col: usize) {
        if self.peek(0) == Some('0') {
            let prefix = self.peek(1).map(|c| c.to_ascii_lowercase());
            if prefix == Some('x') && self.peek(2).is_some_and(|c| c.is_ascii_hexdigit()) {
                self.bump_n(2);
                while self.peek(0).is_some_and(|c| c.is_ascii_hexdigit()) {
                    self.bump();
                }
                self.push(TokenKind::Hex, start, line, col);
                return;
            }
            if prefix == Some('o') && self.peek(2).is_some_and(|c| c.is_ascii_digit()) {
                self.bump_n(2);
                self.digits();
                self.push(TokenKind::Octal, start, line, col);
                return;
            }
        }
        self.digits();
        let int_end = self.i;
        if self.peek(0) == Some('.') && self.peek(1).is_some_and(|d| d.is_ascii_digit()) {
            self.bump();
            self.digits();
            self.exponent();
            self.push(TokenKind::Float, start, line, col);
            return;
        }
        if self.exponent() {
            self.push(TokenKind::Float, start, line, col);
            return;
        }
        // INTEGER_LITERAL : '0' | [1-9][0-9]* — a leading zero only ever forms the token "0".
        if self.chars[start] == '0' && int_end - start > 1 {
            // Rewind to just after the "0".
            let extra = int_end - start - 1;
            self.i -= extra;
            self.col -= extra;
        }
        self.push(TokenKind::Int, start, line, col);
    }

    fn string(&mut self, quote: char, start: usize, line: usize, col: usize) -> CypherResult<()> {
        self.bump();
        loop {
            match self.peek(0) {
                Some('\\') => {
                    if self.peek(1).is_none() {
                        self.bump();
                        return Err(self.error(start, col));
                    }
                    self.bump_n(2);
                }
                Some(c) if c == quote => {
                    if self.peek(1) == Some(quote) {
                        self.bump_n(2);
                    } else {
                        self.bump();
                        break;
                    }
                }
                Some(_) => self.bump(),
                Option::None => return Err(self.error(start, col)),
            }
        }
        self.push(TokenKind::Str, start, line, col);
        Ok(())
    }
}

/// Tokenize `text`. The result always ends with an `Eof` token.
pub fn tokenize(text: &str) -> CypherResult<Vec<Token>> {
    Lexer {
        chars: text.chars().collect(),
        i: 0,
        line: 1,
        col: 0,
        tokens: Vec::new(),
    }
    .run()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kinds(text: &str) -> Vec<TokenKind> {
        tokenize(text)
            .unwrap()
            .into_iter()
            .map(|t| t.kind)
            .collect()
    }

    fn texts(text: &str) -> Vec<String> {
        let mut v: Vec<String> = tokenize(text)
            .unwrap()
            .into_iter()
            .map(|t| t.text)
            .collect();
        v.pop(); // EOF
        v
    }

    fn err(text: &str) -> (usize, String) {
        match tokenize(text) {
            Err(CypherError::Parse { position, message }) => (position, message),
            other => panic!("expected lexer error, got {other:?}"),
        }
    }

    #[test]
    fn keywords_are_case_insensitive_and_keep_spelling() {
        let toks = tokenize("match MaTcH Return").unwrap();
        assert_eq!(toks[0].kind, TokenKind::Keyword(Keyword::Match));
        assert_eq!(toks[1].kind, TokenKind::Keyword(Keyword::Match));
        assert_eq!(toks[1].text, "MaTcH");
        assert_eq!(toks[2].kind, TokenKind::Keyword(Keyword::Return));
        assert_eq!(toks[3].kind, TokenKind::Eof);
    }

    #[test]
    fn identifiers_are_ascii_only() {
        assert_eq!(kinds("_a1"), vec![TokenKind::Ident, TokenKind::Eof]);
        assert_eq!(
            err("RETURN é"),
            (7, "token recognition error at: 'é'".into())
        );
    }

    #[test]
    fn operators() {
        assert_eq!(
            kinds("= += =~ <= >= <> > < .. ; . , ( ) { } [ ] - + / % ^ * : | $"),
            vec![
                TokenKind::Assign,
                TokenKind::AddAssign,
                TokenKind::Regex,
                TokenKind::Le,
                TokenKind::Ge,
                TokenKind::Ne,
                TokenKind::Gt,
                TokenKind::Lt,
                TokenKind::Range,
                TokenKind::Semi,
                TokenKind::Dot,
                TokenKind::Comma,
                TokenKind::LParen,
                TokenKind::RParen,
                TokenKind::LBrace,
                TokenKind::RBrace,
                TokenKind::LBrack,
                TokenKind::RBrack,
                TokenKind::Sub,
                TokenKind::Plus,
                TokenKind::Div,
                TokenKind::Mod,
                TokenKind::Caret,
                TokenKind::Mult,
                TokenKind::Colon,
                TokenKind::Stick,
                TokenKind::Dollar,
                TokenKind::Eof,
            ]
        );
    }

    #[test]
    fn arrows_split_into_operator_tokens() {
        assert_eq!(
            texts("(a)<-[r]->(b)"),
            vec!["(", "a", ")", "<", "-", "[", "r", "]", "-", ">", "(", "b", ")"]
        );
        assert_eq!(
            texts("(a)-->(b)"),
            vec!["(", "a", ")", "-", "-", ">", "(", "b", ")"]
        );
    }

    #[test]
    fn numbers() {
        assert_eq!(kinds("42"), vec![TokenKind::Int, TokenKind::Eof]);
        assert_eq!(kinds("0"), vec![TokenKind::Int, TokenKind::Eof]);
        assert_eq!(kinds("3.14"), vec![TokenKind::Float, TokenKind::Eof]);
        assert_eq!(kinds(".5"), vec![TokenKind::Float, TokenKind::Eof]);
        assert_eq!(kinds("1e5"), vec![TokenKind::Float, TokenKind::Eof]);
        assert_eq!(kinds("1.5E-2"), vec![TokenKind::Float, TokenKind::Eof]);
        assert_eq!(kinds("0xFF"), vec![TokenKind::Hex, TokenKind::Eof]);
        assert_eq!(kinds("0X1f"), vec![TokenKind::Hex, TokenKind::Eof]);
        assert_eq!(kinds("0o17"), vec![TokenKind::Octal, TokenKind::Eof]);
        assert_eq!(kinds("0o8"), vec![TokenKind::Octal, TokenKind::Eof]);
    }

    #[test]
    fn leading_zero_integers_split() {
        assert_eq!(texts("007"), vec!["0", "0", "7"]);
        assert_eq!(texts("007.5"), vec!["007.5"]);
        assert_eq!(texts("007e1"), vec!["007e1"]);
        assert_eq!(
            kinds("0x"),
            vec![TokenKind::Int, TokenKind::Ident, TokenKind::Eof]
        );
    }

    #[test]
    fn number_followed_by_range_or_dot() {
        assert_eq!(texts("1..3"), vec!["1", "..", "3"]);
        assert_eq!(texts("1.5e"), vec!["1.5", "e"]);
        assert_eq!(texts("1e+"), vec!["1", "e", "+"]);
        assert_eq!(texts("[..3]"), vec!["[", "..", "3", "]"]);
        assert_eq!(texts("n.x"), vec!["n", ".", "x"]);
    }

    #[test]
    fn strings_with_escapes_and_doubled_quotes() {
        assert_eq!(texts(r#"'it''s' "say ""hi""" 'a\'b' "c\"d""#).len(), 4);
        let t = tokenize(r"'a\nb'").unwrap();
        assert_eq!(t[0].kind, TokenKind::Str);
        assert_eq!(t[0].text, r"'a\nb'");
    }

    #[test]
    fn unterminated_string_reports_start_column() {
        assert_eq!(
            err("RETURN 'hello"),
            (7, "token recognition error at: ''hello'".into())
        );
        assert_eq!(
            err("RETURN \"x"),
            (7, "token recognition error at: '\"x'".into())
        );
        assert_eq!(
            err("RETURN 'ab\\"),
            (7, "token recognition error at: ''ab\\'".into())
        );
    }

    #[test]
    fn backtick_identifiers() {
        let t = tokenize("`Return Node`").unwrap();
        assert_eq!(t[0].kind, TokenKind::EscIdent);
        assert_eq!(t[0].text, "`Return Node`");
        assert_eq!(
            err("MATCH (n:`Foo) RETURN n"),
            (9, "token recognition error at: '`Foo) RETURN n'".into())
        );
    }

    #[test]
    fn unexpected_character() {
        assert_eq!(
            err("RETURN ~x"),
            (7, "token recognition error at: '~'".into())
        );
        assert_eq!(
            err("RETURN !"),
            (7, "token recognition error at: '!'".into())
        );
    }

    #[test]
    fn comments_are_skipped() {
        assert_eq!(
            texts("MATCH (n) // comment\nRETURN n"),
            vec!["MATCH", "(", "n", ")", "RETURN", "n"]
        );
        assert_eq!(
            texts("MATCH (n) /* block\nmulti */ RETURN n"),
            vec!["MATCH", "(", "n", ")", "RETURN", "n"]
        );
        assert_eq!(texts("RETURN 1 // no newline"), vec!["RETURN", "1"]);
    }

    #[test]
    fn unterminated_block_comment_lexes_as_operators() {
        assert_eq!(
            texts("RETURN 1 /* oops"),
            vec!["RETURN", "1", "/", "*", "oops"]
        );
    }

    #[test]
    fn columns_reset_per_line() {
        let t = tokenize("MATCH (n)\n  RETURN n").unwrap();
        let ret = t
            .iter()
            .find(|t| t.kind == TokenKind::Keyword(Keyword::Return))
            .unwrap();
        assert_eq!((ret.line, ret.col), (2, 2));
        assert_eq!(
            err("MATCH (n)\nRETURN ~"),
            (7, "token recognition error at: '~'".into())
        );
        // '\r' does not reset the column.
        assert_eq!(err("a\r~"), (2, "token recognition error at: '~'".into()));
    }

    #[test]
    fn columns_count_characters_not_bytes() {
        assert_eq!(
            err("RETURN 'é' ~"),
            (11, "token recognition error at: '~'".into())
        );
    }

    #[test]
    fn eof_token_position() {
        let t = tokenize("RETURN").unwrap();
        assert_eq!(t.last().unwrap().kind, TokenKind::Eof);
        assert_eq!(t.last().unwrap().col, 6);
        assert_eq!(t.last().unwrap().display(), "<EOF>");
        assert_eq!(t[0].display(), "RETURN");
    }

    #[test]
    fn form_feed_is_whitespace() {
        assert_eq!(texts("RETURN\u{000C}1"), vec!["RETURN", "1"]);
    }

    #[test]
    fn symbolic_name_predicate() {
        assert!(TokenKind::Ident.is_symbolic_name());
        assert!(TokenKind::EscIdent.is_symbolic_name());
        assert!(TokenKind::Keyword(Keyword::Order).is_symbolic_name());
        assert!(!TokenKind::Int.is_symbolic_name());
        assert!(TokenKind::Hex.is_integer_literal());
        assert!(!TokenKind::Float.is_integer_literal());
    }

    #[test]
    fn every_keyword_round_trips() {
        let words = [
            "CALL",
            "YIELD",
            "FILTER",
            "EXTRACT",
            "COUNT",
            "ANY",
            "NONE",
            "SINGLE",
            "ALL",
            "ASC",
            "ASCENDING",
            "BY",
            "CREATE",
            "DELETE",
            "DESC",
            "DESCENDING",
            "DETACH",
            "EXISTS",
            "LIMIT",
            "MATCH",
            "MERGE",
            "ON",
            "OPTIONAL",
            "ORDER",
            "REMOVE",
            "RETURN",
            "SET",
            "SKIP",
            "WHERE",
            "WITH",
            "UNION",
            "UNWIND",
            "AND",
            "AS",
            "CONTAINS",
            "DISTINCT",
            "ENDS",
            "IN",
            "IS",
            "NOT",
            "OR",
            "STARTS",
            "XOR",
            "FALSE",
            "TRUE",
            "NULL",
            "CONSTRAINT",
            "DO",
            "FOR",
            "REQUIRE",
            "UNIQUE",
            "CASE",
            "WHEN",
            "THEN",
            "ELSE",
            "END",
            "MANDATORY",
            "SCALAR",
            "OF",
            "ADD",
            "DROP",
        ];
        for w in words {
            assert!(Keyword::from_ident(w).is_some(), "{w}");
            assert!(Keyword::from_ident(&w.to_lowercase()).is_some(), "{w}");
        }
        assert_eq!(Keyword::from_ident("foo"), Option::None);
    }
}
