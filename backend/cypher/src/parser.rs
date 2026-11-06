//! Recursive-descent parser mirroring `CypherParser.g4` + `CypherDslAdapter`.
//!
//! Deliberate parity quirks (all verified against the Kotlin adapter):
//! - Comparison chains left-fold: `1 < 2 < 3` parses as `(1 < 2) < 3`.
//! - String predicates bind tighter than comparisons, looser than `+`/`-`.
//! - Every keyword is a legal `symbolicName` (variable, label, property, map key).
//! - `MATCH ... WHERE p` emits `Match` **then** `Where`; `OPTIONAL MATCH` keeps the
//!   predicate inside `Match.where_clause` (it drives null-filling).
//! - `RETURN`/`WITH` are flattened into `Return`/`With` + `OrderBy` + `Skip` + `Limit`.
//! - Mutation clauses retain their operands; `UNION` becomes a marker clause.
//! - A query containing `;` is split, parsed per part, and the clause lists concatenated.

use crate::ast::*;
use crate::error::{CypherError, CypherResult};
use crate::lexer::{tokenize, Keyword as K, Token, TokenKind as T};
use parking_lot::Mutex;
use std::collections::HashMap;
use std::sync::Arc;

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

/// Parse a query into a flat clause list.
pub fn parse(text: &str) -> CypherResult<Vec<Clause>> {
    if text.trim().is_empty() {
        return Ok(Vec::new());
    }
    // `CypherDslAdapter.parse`: split on ';' when it yields more than one non-blank part.
    if text.contains(';') {
        let parts: Vec<&str> = text.split(';').filter(|p| !p.trim().is_empty()).collect();
        if parts.len() > 1 {
            let mut out = Vec::new();
            for p in parts {
                out.extend(parse_single(p)?);
            }
            return Ok(out);
        }
    }
    parse_single(text)
}

fn parse_single(text: &str) -> CypherResult<Vec<Clause>> {
    if text.trim().is_empty() {
        return Ok(Vec::new());
    }
    let tokens = tokenize(text)?;
    let mut p = Parser {
        toks: tokens,
        i: 0,
        source: text,
        parameter_properties_forbidden: true,
    };
    let clauses = p.script()?;
    Ok(clauses)
}

const PARSE_CACHE_CAPACITY: usize = 1024;

static PARSE_CACHE: Mutex<Option<LruCache>> = Mutex::new(None);

struct LruCache {
    map: HashMap<String, Arc<Vec<Clause>>>,
    order: Vec<String>,
}

/// Parse with a process-wide LRU cache keyed by the exact query text.
/// Failed parses are never cached.
pub fn parse_cached(text: &str) -> CypherResult<Arc<Vec<Clause>>> {
    {
        let mut guard = PARSE_CACHE.lock();
        let cache = guard.get_or_insert_with(|| LruCache {
            map: HashMap::new(),
            order: Vec::new(),
        });
        if let Some(hit) = cache.map.get(text).cloned() {
            if let Some(pos) = cache.order.iter().position(|k| k == text) {
                let k = cache.order.remove(pos);
                cache.order.push(k);
            }
            return Ok(hit);
        }
    }
    let parsed = Arc::new(parse(text)?);
    let mut guard = PARSE_CACHE.lock();
    let cache = guard.get_or_insert_with(|| LruCache {
        map: HashMap::new(),
        order: Vec::new(),
    });
    if !cache.map.contains_key(text) {
        cache.map.insert(text.to_string(), parsed.clone());
        cache.order.push(text.to_string());
        while cache.order.len() > PARSE_CACHE_CAPACITY {
            let evict = cache.order.remove(0);
            cache.map.remove(&evict);
        }
    }
    Ok(parsed)
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

struct Parser<'a> {
    source: &'a str,
    toks: Vec<Token>,
    i: usize,
    parameter_properties_forbidden: bool,
}

impl Parser<'_> {
    #[inline]
    fn peek(&self) -> &Token {
        &self.toks[self.i.min(self.toks.len() - 1)]
    }

    #[inline]
    fn kind(&self) -> T {
        self.peek().kind
    }

    #[inline]
    fn at(&self, k: T) -> bool {
        self.kind() == k
    }

    #[inline]
    fn at_kw(&self, k: K) -> bool {
        self.kind() == T::Keyword(k)
    }

    /// Look ahead `n` tokens.
    fn peek_at(&self, n: usize) -> T {
        self.toks[(self.i + n).min(self.toks.len() - 1)].kind
    }

    fn bump(&mut self) -> Token {
        let t = self.toks[self.i.min(self.toks.len() - 1)].clone();
        if self.i < self.toks.len() - 1 {
            self.i += 1;
        }
        t
    }

    fn eat(&mut self, k: T) -> bool {
        if self.at(k) {
            self.bump();
            true
        } else {
            false
        }
    }

    fn eat_kw(&mut self, k: K) -> bool {
        self.eat(T::Keyword(k))
    }

    fn err<S: Into<String>>(&self, msg: S) -> CypherError {
        CypherError::Syntax {
            position: self.peek().col,
            message: msg.into(),
            detail: "UnexpectedSyntax",
        }
    }

    fn mismatched(&self, expecting: &str) -> CypherError {
        self.err(format!(
            "mismatched input '{}' expecting {}",
            self.peek().display(),
            expecting
        ))
    }

    fn expect(&mut self, k: T, expecting: &str) -> CypherResult<Token> {
        if self.at(k) {
            Ok(self.bump())
        } else {
            Err(self.mismatched(expecting))
        }
    }

    // -- script / clauses --------------------------------------------------

    fn script(&mut self) -> CypherResult<Vec<Clause>> {
        let mut out = Vec::new();
        loop {
            self.regular_query(&mut out)?;
            if self.eat(T::Semi) {
                if self.at(T::Eof) {
                    break;
                }
                continue;
            }
            break;
        }
        if !self.at(T::Eof) {
            return Err(self.mismatched("<EOF>"));
        }
        Ok(out)
    }

    fn regular_query(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        self.single_query(out)?;
        while self.at_kw(K::Union) {
            self.bump();
            let all = self.eat_kw(K::All);
            out.push(Clause::Union { all });
            self.single_query(out)?;
        }
        Ok(())
    }

    fn single_query(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        let before = out.len();
        while self.starts_clause() {
            self.clause(out)?;
        }
        if out.len() == before {
            return Err(self.mismatched(
                "{OPTIONAL, MATCH, UNWIND, WITH, RETURN, CREATE, DETACH, DELETE, SET, REMOVE, MERGE}",
            ));
        }
        Ok(())
    }

    fn starts_clause(&self) -> bool {
        matches!(
            self.kind(),
            T::Keyword(K::Call)
                | T::Keyword(K::Match)
                | T::Keyword(K::Optional)
                | T::Keyword(K::Unwind)
                | T::Keyword(K::With)
                | T::Keyword(K::Return)
                | T::Keyword(K::Create)
                | T::Keyword(K::Delete)
                | T::Keyword(K::Detach)
                | T::Keyword(K::Set)
                | T::Keyword(K::Remove)
                | T::Keyword(K::Merge)
        )
    }

    fn call_st(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        self.bump();
        let mut name = self.symbolic_name()?;
        while self.eat(T::Dot) {
            name.push('.');
            name.push_str(&self.symbolic_name()?);
        }
        let arguments = if self.eat(T::LParen) {
            let mut values = Vec::new();
            if !self.eat(T::RParen) {
                values.push(self.expression()?);
                while self.eat(T::Comma) {
                    values.push(self.expression()?);
                }
                self.expect(T::RParen, "')'")?;
            }
            Some(values)
        } else {
            None
        };
        let mut yields = None;
        let mut yield_all = false;
        if self.eat_kw(K::Yield) {
            if self.eat(T::Mult) {
                yield_all = true;
            } else {
                let mut fields = Vec::new();
                loop {
                    let source = self.symbolic_name()?;
                    let alias = if self.eat_kw(K::As) {
                        self.symbolic_name()?
                    } else {
                        source.clone()
                    };
                    fields.push((source, alias));
                    if !self.eat(T::Comma) {
                        break;
                    }
                }
                yields = Some(fields);
            }
        }
        out.push(Clause::Call(ProcedureCall {
            name,
            arguments,
            yields,
            yield_all,
        }));
        if self.eat_kw(K::Where) {
            out.push(Clause::Where(self.expression()?));
        }
        Ok(())
    }

    fn clause(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        match self.kind() {
            T::Keyword(K::Call) => self.call_st(out),
            T::Keyword(K::Optional) | T::Keyword(K::Match) => self.match_st(out),
            T::Keyword(K::Unwind) => {
                self.bump();
                let expr = self.expression()?;
                if !self.eat_kw(K::As) {
                    return Err(self.mismatched("AS"));
                }
                let variable = self.variable()?;
                out.push(Clause::Unwind { expr, variable });
                Ok(())
            }
            T::Keyword(K::With) => self.with_st(out),
            T::Keyword(K::Return) => self.return_st(out),
            T::Keyword(K::Create) => {
                self.bump();
                let previous = self.parameter_properties_forbidden;
                self.parameter_properties_forbidden = false;
                let parsed = self.pattern_list();
                self.parameter_properties_forbidden = previous;
                let patterns = parsed?;
                out.push(Clause::Create(patterns));
                Ok(())
            }
            T::Keyword(K::Merge) => {
                self.bump();
                let pattern = self.pattern_part()?;
                let mut on_create = Vec::new();
                let mut on_match = Vec::new();
                while self.eat_kw(K::On) {
                    let create = if self.eat_kw(K::Create) {
                        true
                    } else if self.eat_kw(K::Match) {
                        false
                    } else {
                        return Err(self.mismatched("CREATE or MATCH"));
                    };
                    if !self.eat_kw(K::Set) {
                        return Err(self.mismatched("SET"));
                    }
                    let items = self.set_items()?;
                    if create {
                        on_create.extend(items);
                    } else {
                        on_match.extend(items);
                    }
                }
                out.push(Clause::Merge {
                    pattern,
                    on_create,
                    on_match,
                });
                Ok(())
            }
            T::Keyword(K::Detach) | T::Keyword(K::Delete) => {
                let detach = self.eat_kw(K::Detach);
                if !self.eat_kw(K::Delete) {
                    return Err(self.mismatched("DELETE"));
                }
                let mut exprs = vec![self.expression()?];
                while self.eat(T::Comma) {
                    exprs.push(self.expression()?);
                }
                out.push(Clause::Delete { detach, exprs });
                Ok(())
            }
            T::Keyword(K::Set) => {
                self.bump();
                out.push(Clause::Set(self.set_items()?));
                Ok(())
            }
            T::Keyword(K::Remove) => {
                self.bump();
                out.push(Clause::Remove(self.remove_items()?));
                Ok(())
            }
            _ => Err(self.mismatched("a clause")),
        }
    }

    fn match_st(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        let optional = self.eat_kw(K::Optional);
        if !self.eat_kw(K::Match) {
            return Err(self.mismatched("MATCH"));
        }
        let patterns = self.pattern_list()?;
        let where_clause = if self.at_kw(K::Where) {
            self.bump();
            Some(self.expression()?)
        } else {
            None
        };
        if optional {
            out.push(Clause::Match {
                patterns,
                optional: true,
                where_clause,
            });
        } else {
            out.push(Clause::Match {
                patterns,
                optional: false,
                where_clause: None,
            });
            if let Some(w) = where_clause {
                out.push(Clause::Where(w));
            }
        }
        Ok(())
    }

    fn with_st(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        self.bump();
        let distinct = self.eat_kw(K::Distinct);
        let items = self.return_items()?;
        let where_clause = if self.at_kw(K::Where) {
            self.bump();
            Some(self.expression()?)
        } else {
            None
        };
        out.push(Clause::With {
            distinct,
            items,
            where_clause,
        });
        self.tail_clauses(out)
    }

    fn return_st(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        self.bump();
        let distinct = self.eat_kw(K::Distinct);
        let items = self.return_items()?;
        out.push(Clause::Return { distinct, items });
        self.tail_clauses(out)
    }

    /// ORDER BY / SKIP / LIMIT, emitted as separate clauses in that fixed order.
    fn tail_clauses(&mut self, out: &mut Vec<Clause>) -> CypherResult<()> {
        if self.at_kw(K::Order) {
            self.bump();
            if !self.eat_kw(K::By) {
                return Err(self.mismatched("BY"));
            }
            let mut items = vec![self.order_item()?];
            while self.eat(T::Comma) {
                items.push(self.order_item()?);
            }
            out.push(Clause::OrderBy(items));
        }
        if self.at_kw(K::Skip) {
            self.bump();
            out.push(Clause::Skip(self.expression()?));
        }
        if self.at_kw(K::Limit) {
            self.bump();
            out.push(Clause::Limit(self.expression()?));
        }
        Ok(())
    }

    fn order_item(&mut self) -> CypherResult<OrderItem> {
        let expr = self.expression()?;
        let descending = if self.at_kw(K::Desc) || self.at_kw(K::Descending) {
            self.bump();
            true
        } else {
            if self.at_kw(K::Asc) || self.at_kw(K::Ascending) {
                self.bump();
            }
            false
        };
        Ok(OrderItem { expr, descending })
    }

    /// `MULT` (i.e. `*`) yields `None`, meaning `RETURN *`.
    fn return_items(&mut self) -> CypherResult<Option<Vec<ReturnItem>>> {
        if self.at(T::Mult) {
            self.bump();
            return Ok(None);
        }
        let mut items = vec![self.return_item()?];
        while self.eat(T::Comma) {
            items.push(self.return_item()?);
        }
        Ok(Some(items))
    }

    fn return_item(&mut self) -> CypherResult<ReturnItem> {
        let start = self.i;
        let expr = self.expression()?;
        let last = &self.toks[self.i - 1];
        let first = &self.toks[start];
        let byte_offset = |token: &Token| -> usize {
            let line_start = self
                .source
                .split_inclusive('\n')
                .take(token.line - 1)
                .map(str::len)
                .sum::<usize>();
            line_start
                + self.source[line_start..]
                    .chars()
                    .take(token.col)
                    .map(char::len_utf8)
                    .sum::<usize>()
        };
        let source_span = byte_offset(first)..byte_offset(last) + last.text.len();
        let alias = if self.eat_kw(K::As) {
            Some(self.variable()?)
        } else {
            None
        };
        let source_text = if alias.is_some() || matches!(expr, Expr::Variable(_)) {
            None
        } else {
            Some(self.source[source_span].to_owned())
        };
        Ok(ReturnItem {
            expr,
            alias,
            source_text,
        })
    }

    fn update_variable(&mut self) -> CypherResult<String> {
        if self.eat(T::LParen) {
            let variable = self.update_variable()?;
            self.expect(T::RParen, "')'")?;
            Ok(variable)
        } else {
            self.variable()
        }
    }
    fn set_items(&mut self) -> CypherResult<Vec<SetItem>> {
        let mut items = Vec::new();
        loop {
            let variable = self.update_variable()?;
            let item = if self.eat(T::Dot) {
                let key = self.symbolic_name()?;
                self.expect(T::Assign, "'='")?;
                SetItem::Property {
                    variable,
                    key,
                    value: self.expression()?,
                }
            } else if self.at(T::AddAssign) || self.at(T::Assign) {
                let merge = self.eat(T::AddAssign);
                if !merge {
                    self.expect(T::Assign, "'='")?;
                }
                SetItem::Map {
                    variable,
                    value: self.expression()?,
                    merge,
                }
            } else if self.eat(T::Colon) {
                let mut labels = vec![self.symbolic_name()?];
                while self.eat(T::Colon) {
                    labels.push(self.symbolic_name()?);
                }
                SetItem::Labels { variable, labels }
            } else {
                return Err(self.mismatched("{'.', '=', '+=', ':'}"));
            };
            items.push(item);
            if !self.eat(T::Comma) {
                return Ok(items);
            }
        }
    }
    fn remove_items(&mut self) -> CypherResult<Vec<RemoveItem>> {
        let mut items = Vec::new();
        loop {
            let variable = self.update_variable()?;
            let item = if self.eat(T::Dot) {
                RemoveItem::Property {
                    variable,
                    key: self.symbolic_name()?,
                }
            } else if self.eat(T::Colon) {
                let mut labels = vec![self.symbolic_name()?];
                while self.eat(T::Colon) {
                    labels.push(self.symbolic_name()?);
                }
                RemoveItem::Labels { variable, labels }
            } else {
                return Err(self.mismatched("{'.', ':'}"));
            };
            items.push(item);
            if !self.eat(T::Comma) {
                return Ok(items);
            }
        }
    }

    // -- patterns ----------------------------------------------------------

    fn pattern_list(&mut self) -> CypherResult<Vec<Pattern>> {
        let mut out = vec![self.pattern_part()?];
        while self.eat(T::Comma) {
            out.push(self.pattern_part()?);
        }
        Ok(out)
    }

    fn pattern_part(&mut self) -> CypherResult<Pattern> {
        // `p = (a)-->(b)`
        let mut path_variable = None;
        if self.kind().is_symbolic_name() && self.peek_at(1) == T::Assign {
            path_variable = Some(self.variable()?);
            self.bump(); // '='
        }
        let mut nodes = vec![self.node_pattern()?];
        let mut rels = Vec::new();
        while self.starts_relationship() {
            rels.push(self.relationship_pattern()?);
            nodes.push(self.node_pattern()?);
        }
        Ok(Pattern {
            path_variable,
            nodes,
            rels,
        })
    }

    fn starts_relationship(&self) -> bool {
        matches!(self.kind(), T::Sub | T::Lt)
    }

    fn node_pattern(&mut self) -> CypherResult<NodePattern> {
        self.expect(T::LParen, "'('")?;
        let variable = if self.kind().is_symbolic_name() {
            Some(self.variable()?)
        } else {
            None
        };
        let mut labels = Vec::new();
        while self.eat(T::Colon) {
            labels.push(self.symbolic_name()?);
        }
        if self.parameter_properties_forbidden && self.at(T::Dollar) {
            return Err(CypherError::compilation(
                "SyntaxError",
                "InvalidParameterUse",
                "A pattern predicate must be a literal property map",
            ));
        }
        let properties_present = self.at(T::LBrace);
        let properties = if properties_present {
            self.map_entries()?
        } else {
            Vec::new()
        };
        self.expect(T::RParen, "{')', '{', ':'}")?;
        Ok(NodePattern {
            properties_present,
            variable,
            labels,
            properties,
        })
    }

    fn relationship_pattern(&mut self) -> CypherResult<RelPattern> {
        let left_arrow = self.eat(T::Lt);
        self.expect(T::Sub, "'-'")?;
        let detail = if self.at(T::LBrack) {
            self.relation_detail()?
        } else {
            RelDetail::default()
        };
        self.expect(T::Sub, "'-'")?;
        let right_arrow = self.eat(T::Gt);
        // `<-[..]->` parses as BOTH, matching the Kotlin adapter.
        let direction = match (left_arrow, right_arrow) {
            (true, true) => Direction::Both,
            (true, false) => Direction::Incoming,
            (false, true) => Direction::Outgoing,
            (false, false) => Direction::Both,
        };
        Ok(RelPattern {
            variable: detail.variable,
            types: detail.types,
            direction,
            variable_length: detail.variable_length,
            min_hops: detail.min_hops,
            max_hops: detail.max_hops,
            properties: detail.properties,
        })
    }

    fn relation_detail(&mut self) -> CypherResult<RelDetail> {
        self.expect(T::LBrack, "'['")?;
        let mut d = RelDetail::default();
        if self.kind().is_symbolic_name() && self.peek_at(1) != T::Colon {
            d.variable = Some(self.variable()?);
        } else if self.kind().is_symbolic_name() {
            // `[r:TYPE]` — a name followed by ':'
            d.variable = Some(self.variable()?);
        }
        if self.eat(T::Colon) {
            d.types.push(self.symbolic_name()?);
            while self.eat(T::Stick) {
                self.eat(T::Colon);
                d.types.push(self.symbolic_name()?);
            }
        }
        if self.at(T::Range) {
            return Err(CypherError::compilation(
                "SyntaxError",
                "InvalidRelationshipPattern",
                "Relationship length bounds require an asterisk",
            ));
        }
        if self.at(T::Mult) {
            self.bump();
            d.variable_length = true;
            if matches!(self.kind(), T::Sub | T::Plus | T::Float) {
                return Err(CypherError::compilation(
                    "SyntaxError",
                    "InvalidRelationshipPattern",
                    "Relationship length bounds must be non-negative integers",
                ));
            }
            // `*`, `*n`, `*n..m`, `*n..`, `*..m`, `*..`
            let has_min = self.kind().is_integer_literal();
            let min = if has_min { Some(self.integer()?) } else { None };
            if self.eat(T::Range) {
                if matches!(self.kind(), T::Sub | T::Plus | T::Float) {
                    return Err(CypherError::compilation(
                        "SyntaxError",
                        "InvalidRelationshipPattern",
                        "Relationship length bounds must be non-negative integers",
                    ));
                }
                let max = if self.kind().is_integer_literal() {
                    Some(self.integer()?)
                } else {
                    None
                };
                d.min_hops = Some(min.unwrap_or(1));
                d.max_hops = max;
            } else if let Some(m) = min {
                d.min_hops = Some(m);
                d.max_hops = Some(m);
            }
        }
        if self.parameter_properties_forbidden && self.at(T::Dollar) {
            return Err(CypherError::compilation(
                "SyntaxError",
                "InvalidParameterUse",
                "A relationship pattern predicate must be a literal property map",
            ));
        }
        if self.at(T::LBrace) {
            d.properties = self.map_entries()?;
        }
        self.expect(T::RBrack, "']'")?;
        Ok(d)
    }

    fn integer(&mut self) -> CypherResult<u32> {
        let t = self.bump();
        let n = match t.kind {
            T::Int => t.text.parse::<i64>().ok(),
            T::Hex => i64::from_str_radix(&t.text[2..], 16).ok(),
            T::Octal => i64::from_str_radix(&t.text[2..], 8).ok(),
            _ => None,
        };
        match n {
            Some(v) if v >= 0 => u32::try_from(v).map_err(|_| {
                CypherError::NotImplemented(
                    "Relationship length bounds above u32::MAX are not supported".into(),
                )
            }),
            _ => Err(CypherError::Other(format!(
                "For input string: \"{}\"",
                t.text
            ))),
        }
    }

    // -- names -------------------------------------------------------------

    fn variable(&mut self) -> CypherResult<String> {
        self.symbolic_name()
    }

    /// `symbolicName : ID | ESC_LITERAL | <any keyword>`
    fn symbolic_name(&mut self) -> CypherResult<String> {
        if !self.kind().is_symbolic_name() {
            return Err(self.mismatched("an identifier"));
        }
        let t = self.bump();
        Ok(match t.kind {
            T::EscIdent => t.text[1..t.text.len() - 1].to_string(),
            _ => t.text,
        })
    }

    // -- expressions -------------------------------------------------------

    pub fn expression(&mut self) -> CypherResult<Expr> {
        self.or_expr()
    }

    fn or_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.xor_expr()?;
        while self.at_kw(K::Or) {
            self.bump();
            let right = self.xor_expr()?;
            left = Expr::Or(Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn xor_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.and_expr()?;
        while self.at_kw(K::Xor) {
            self.bump();
            let right = self.and_expr()?;
            left = Expr::Xor(Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn and_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.not_expr()?;
        while self.at_kw(K::And) {
            self.bump();
            let right = self.not_expr()?;
            left = Expr::And(Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn not_expr(&mut self) -> CypherResult<Expr> {
        if self.at_kw(K::Not) {
            self.bump();
            return Ok(Expr::Not(Box::new(self.not_expr()?)));
        }
        self.comparison_expr()
    }

    fn comparison_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.string_predicate_expr()?;
        let mut comparisons: Option<Expr> = None;
        loop {
            let op = match self.kind() {
                T::Assign => CmpOp::Eq,
                T::Ne => CmpOp::Ne,
                T::Lt => CmpOp::Lt,
                T::Gt => CmpOp::Gt,
                T::Le => CmpOp::Le,
                T::Ge => CmpOp::Ge,
                _ => break,
            };
            self.bump();
            let right = self.string_predicate_expr()?;
            let comparison = Expr::Comparison {
                op,
                left: Box::new(left),
                right: Box::new(right.clone()),
            };
            comparisons = Some(match comparisons {
                Some(previous) => Expr::And(Box::new(previous), Box::new(comparison)),
                None => comparison,
            });
            left = right;
        }
        Ok(comparisons.unwrap_or(left))
    }

    fn string_predicate_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.add_sub_expr()?;
        loop {
            match self.kind() {
                T::Keyword(K::Starts) => {
                    self.bump();
                    self.expect_with()?;
                    let right = self.add_sub_expr()?;
                    left = str_op(StrOp::StartsWith, left, right);
                }
                T::Keyword(K::Ends) => {
                    self.bump();
                    self.expect_with()?;
                    let right = self.add_sub_expr()?;
                    left = str_op(StrOp::EndsWith, left, right);
                }
                T::Keyword(K::Contains) => {
                    self.bump();
                    let right = self.add_sub_expr()?;
                    left = str_op(StrOp::Contains, left, right);
                }
                T::Keyword(K::In) => {
                    self.bump();
                    let right = self.add_sub_expr()?;
                    left = Expr::In {
                        left: Box::new(left),
                        right: Box::new(right),
                    };
                }
                T::Regex => {
                    self.bump();
                    let right = self.add_sub_expr()?;
                    left = str_op(StrOp::Regex, left, right);
                }
                T::Keyword(K::Is) => {
                    self.bump();
                    if self.eat_kw(K::Not) {
                        if !self.eat_kw(K::Null) {
                            return Err(self.mismatched("NULL"));
                        }
                        left = Expr::IsNotNull(Box::new(left));
                    } else {
                        if !self.eat_kw(K::Null) {
                            return Err(self.mismatched("{NOT, NULL}"));
                        }
                        left = Expr::IsNull(Box::new(left));
                    }
                }
                T::Keyword(K::Not) => {
                    // NOT CONTAINS / NOT STARTS WITH / NOT ENDS WITH
                    let op = match self.peek_at(1) {
                        T::Keyword(K::Contains) => StrOp::Contains,
                        T::Keyword(K::Starts) => StrOp::StartsWith,
                        T::Keyword(K::Ends) => StrOp::EndsWith,
                        _ => break,
                    };
                    self.bump();
                    self.bump();
                    if op != StrOp::Contains {
                        self.expect_with()?;
                    }
                    let right = self.add_sub_expr()?;
                    left = Expr::Not(Box::new(str_op(op, left, right)));
                }
                _ => break,
            }
        }
        Ok(left)
    }

    fn expect_with(&mut self) -> CypherResult<()> {
        if self.eat_kw(K::With) {
            Ok(())
        } else {
            Err(self.mismatched("WITH"))
        }
    }

    fn add_sub_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.mult_div_expr()?;
        loop {
            let op = match self.kind() {
                T::Plus => BinOp::Add,
                T::Sub => BinOp::Sub,
                _ => break,
            };
            self.bump();
            let right = self.mult_div_expr()?;
            left = Expr::Binary {
                op,
                left: Box::new(left),
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn mult_div_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.power_expr()?;
        loop {
            let op = match self.kind() {
                T::Mult => BinOp::Mul,
                T::Div => BinOp::Div,
                T::Mod => BinOp::Mod,
                _ => break,
            };
            self.bump();
            let right = self.power_expr()?;
            left = Expr::Binary {
                op,
                left: Box::new(left),
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    /// Cypher exponentiation associates left to right.
    fn power_expr(&mut self) -> CypherResult<Expr> {
        let mut left = self.unary_expr()?;
        while self.at(T::Caret) {
            self.bump();
            let right = self.unary_expr()?;
            left = Expr::Binary {
                op: BinOp::Pow,
                left: Box::new(left),
                right: Box::new(right),
            };
        }
        Ok(left)
    }

    fn unary_expr(&mut self) -> CypherResult<Expr> {
        if self.at(T::Sub) {
            self.bump();
            let token = self.peek();
            let magnitude = match token.kind {
                T::Int => token.text.parse::<u64>().ok(),
                T::Hex => u64::from_str_radix(&token.text[2..], 16).ok(),
                T::Octal => u64::from_str_radix(&token.text[2..], 8).ok(),
                _ => None,
            };
            if magnitude == Some(i64::MAX as u64 + 1) {
                self.bump();
                return Ok(Expr::Literal(Literal::Int(i64::MIN)));
            }
            return Ok(Expr::Unary {
                negate: true,
                expr: Box::new(self.unary_expr()?),
            });
        }
        if self.at(T::Plus) {
            // Unary '+' is dropped from the AST.
            self.bump();
            return self.unary_expr();
        }
        self.postfix_expr()
    }

    fn postfix_expr(&mut self) -> CypherResult<Expr> {
        let mut e = self.atom_expr()?;
        loop {
            if self.eat(T::Dot) {
                let key = self.symbolic_name()?;
                e = Expr::Property {
                    expr: Box::new(e),
                    key,
                };
            } else if self.eat(T::Colon) {
                let mut labels = vec![self.symbolic_name()?];
                while self.eat(T::Colon) {
                    labels.push(self.symbolic_name()?);
                }
                e = Expr::LabelPredicate {
                    expr: Box::new(e),
                    labels,
                };
            } else if self.at(T::LBrack) {
                self.bump();
                if self.eat(T::Range) {
                    // `e[..to]`
                    let to = if self.at(T::RBrack) {
                        None
                    } else {
                        Some(Box::new(self.expression()?))
                    };
                    self.expect(T::RBrack, "']'")?;
                    e = Expr::Slice {
                        expr: Box::new(e),
                        from: None,
                        to,
                    };
                } else {
                    let first = self.expression()?;
                    if self.eat(T::Range) {
                        let to = if self.at(T::RBrack) {
                            None
                        } else {
                            Some(Box::new(self.expression()?))
                        };
                        self.expect(T::RBrack, "']'")?;
                        e = Expr::Slice {
                            expr: Box::new(e),
                            from: Some(Box::new(first)),
                            to,
                        };
                    } else {
                        self.expect(T::RBrack, "']'")?;
                        e = Expr::Subscript {
                            expr: Box::new(e),
                            index: Box::new(first),
                        };
                    }
                }
            } else {
                break;
            }
        }
        Ok(e)
    }

    fn atom_expr(&mut self) -> CypherResult<Expr> {
        match self.kind() {
            T::InvalidNumber => Err(CypherError::compilation(
                "SyntaxError",
                "InvalidNumberLiteral",
                format!("Invalid numeric literal: {}", self.peek().text),
            )),

            T::Dollar => {
                self.bump();
                let name = if self.at(T::Int) {
                    self.bump().text
                } else {
                    self.symbolic_name()?
                };
                Ok(Expr::Parameter(name))
            }
            T::Keyword(K::Case) => self.case_expr(),
            T::Keyword(K::Count) if self.peek_at(1) == T::LParen && self.peek_at(2) == T::Mult => {
                self.bump();
                self.bump();
                self.bump();
                self.expect(T::RParen, "')'")?;
                Ok(Expr::CountStar)
            }
            T::LBrack => self.list_or_comprehension(),
            T::Keyword(K::Any)
            | T::Keyword(K::All)
            | T::Keyword(K::None)
            | T::Keyword(K::Single)
                if self.peek_at(1) == T::LParen =>
            {
                self.predicate_function()
            }
            T::Keyword(K::Exists) if self.peek_at(1) == T::LBrace => {
                self.bump();
                self.bump();
                let mut clauses = Vec::new();
                if self.at(T::LParen) {
                    let patterns = self.pattern_list()?;
                    clauses.push(Clause::Match {
                        patterns,
                        optional: false,
                        where_clause: None,
                    });
                    if self.eat_kw(K::Where) {
                        clauses.push(Clause::Where(self.expression()?));
                    }
                } else {
                    while !self.at(T::RBrace) && !self.at(T::Eof) {
                        self.clause(&mut clauses)?;
                    }
                }
                if clauses.is_empty() {
                    return Err(self.mismatched("a subquery"));
                }
                self.expect(T::RBrace, "'}'")?;
                Ok(Expr::ExistsSubquery(clauses))
            }
            T::Keyword(K::Exists) if self.peek_at(1) == T::LParen => {
                self.bump();
                self.bump();
                let inner = self.expression()?;
                self.expect(T::RParen, "')'")?;
                Ok(Expr::FunctionCall {
                    name: "exists".to_string(),
                    distinct: false,
                    args: vec![inner],
                })
            }
            T::LParen => {
                let saved = self.i;
                if let Ok(pattern) = self.pattern_part() {
                    if !pattern.rels.is_empty() {
                        return Ok(Expr::PatternPredicate(Box::new(pattern)));
                    }
                }
                self.i = saved;
                self.bump();
                let e = self.expression()?;
                self.expect(T::RParen, "')'")?;
                Ok(e)
            }
            T::Keyword(K::Distinct) => {
                self.bump();
                Ok(Expr::Distinct(Box::new(self.unary_expr()?)))
            }
            T::LBrace => {
                let entries = self.map_entries()?;
                Ok(Expr::MapLiteral(entries))
            }
            T::Keyword(K::True) => {
                self.bump();
                Ok(Expr::Literal(Literal::Bool(true)))
            }
            T::Keyword(K::False) => {
                self.bump();
                Ok(Expr::Literal(Literal::Bool(false)))
            }
            T::Keyword(K::Null) => {
                self.bump();
                Ok(Expr::Literal(Literal::Null))
            }
            T::Int | T::Hex | T::Octal | T::Float | T::Str => self.literal(),
            k if k.is_symbolic_name() => {
                // function call (possibly dotted) or variable
                let mut name = self.symbolic_name()?;
                if self.at(T::Dot) && self.peek_at(1).is_symbolic_name() {
                    // Only a function call if a '(' eventually follows the dotted name.
                    let save = self.i;
                    let mut dotted = name.clone();
                    while self.at(T::Dot) && self.peek_at(1).is_symbolic_name() {
                        self.bump();
                        dotted.push('.');
                        dotted.push_str(&self.symbolic_name()?);
                    }
                    if self.at(T::LParen) {
                        name = dotted;
                    } else {
                        self.i = save;
                        return Ok(Expr::Variable(name));
                    }
                }
                if self.at(T::LParen) {
                    self.bump();
                    let distinct = self.eat_kw(K::Distinct);
                    let mut args = Vec::new();
                    if !self.at(T::RParen) {
                        args.push(self.expression()?);
                        while self.eat(T::Comma) {
                            args.push(self.expression()?);
                        }
                    }
                    self.expect(T::RParen, "')'")?;
                    Ok(Expr::FunctionCall {
                        name,
                        distinct,
                        args,
                    })
                } else {
                    Ok(Expr::Variable(name))
                }
            }
            _ => Err(self.err(format!(
                "extraneous input '{}' expecting an expression",
                self.peek().display()
            ))),
        }
    }

    fn literal(&mut self) -> CypherResult<Expr> {
        let t = self.bump();
        let lit = match t.kind {
            T::Int => {
                let v: i64 = t.text.parse().map_err(|_| {
                    CypherError::compilation(
                        "SyntaxError",
                        "IntegerOverflow",
                        format!("Integer literal is outside signed 64-bit range: {}", t.text),
                    )
                })?;
                Literal::Int(v)
            }
            T::Hex => {
                let v = i64::from_str_radix(&t.text[2..], 16).map_err(|_| {
                    CypherError::compilation(
                        "SyntaxError",
                        "IntegerOverflow",
                        format!("Integer literal is outside signed 64-bit range: {}", t.text),
                    )
                })?;
                Literal::Int(v)
            }
            T::Octal => {
                let v = i64::from_str_radix(&t.text[2..], 8).map_err(|_| {
                    CypherError::compilation(
                        "SyntaxError",
                        "IntegerOverflow",
                        format!("Integer literal is outside signed 64-bit range: {}", t.text),
                    )
                })?;
                Literal::Int(v)
            }
            T::Float => {
                let value = t.text.parse::<f64>().map_err(|_| {
                    CypherError::compilation(
                        "SyntaxError",
                        "InvalidNumberLiteral",
                        "Invalid floating point literal",
                    )
                })?;
                if !value.is_finite() {
                    return Err(CypherError::compilation(
                        "SyntaxError",
                        "FloatingPointOverflow",
                        "Floating point literal is outside finite range",
                    ));
                }
                Literal::Float(value)
            }
            T::Str => Literal::Str(decode_string_literal(&t.text)),
            _ => return Err(self.err("Unknown literal type")),
        };
        Ok(Expr::Literal(lit))
    }

    fn case_expr(&mut self) -> CypherResult<Expr> {
        self.bump(); // CASE
        let test = if !self.at_kw(K::When) {
            Some(Box::new(self.expression()?))
        } else {
            None
        };
        let mut whens = Vec::new();
        while self.eat_kw(K::When) {
            let cond = self.expression()?;
            if !self.eat_kw(K::Then) {
                return Err(self.mismatched("THEN"));
            }
            let then = self.expression()?;
            whens.push((cond, then));
        }
        if whens.is_empty() {
            return Err(self.mismatched("WHEN"));
        }
        let else_expr = if self.eat_kw(K::Else) {
            Some(Box::new(self.expression()?))
        } else {
            None
        };
        if !self.eat_kw(K::End) {
            return Err(self.mismatched("END"));
        }
        Ok(Expr::Case {
            test,
            whens,
            else_expr,
        })
    }

    fn list_or_comprehension(&mut self) -> CypherResult<Expr> {
        self.expect(T::LBrack, "'['")?;
        // Pattern comprehensions introduce their own node/relationship/path locals.
        let saved = self.i;
        if self.at(T::LParen) || (self.kind().is_symbolic_name() && self.peek_at(1) == T::Assign) {
            if let Ok(pattern) = self.pattern_part() {
                if !pattern.rels.is_empty() {
                    let filter = if self.eat_kw(K::Where) {
                        Some(Box::new(self.expression()?))
                    } else {
                        None
                    };
                    if self.eat(T::Stick) {
                        let map = Box::new(self.expression()?);
                        self.expect(T::RBrack, "']'")?;
                        return Ok(Expr::PatternComprehension {
                            pattern: Box::new(pattern),
                            filter,
                            map,
                        });
                    }
                }
            }
        }
        self.i = saved;
        // `[x IN list WHERE p | m]`
        if self.kind().is_symbolic_name() && self.peek_at(1) == T::Keyword(K::In) {
            let variable = self.variable()?;
            self.bump(); // IN
            let list = self.expression()?;
            let filter = if self.eat_kw(K::Where) {
                Some(Box::new(self.expression()?))
            } else {
                None
            };
            let map = if self.eat(T::Stick) {
                Some(Box::new(self.expression()?))
            } else {
                None
            };
            self.expect(T::RBrack, "']'")?;
            return Ok(Expr::ListComprehension {
                variable,
                list: Box::new(list),
                filter,
                map,
            });
        }
        let mut items = Vec::new();
        if !self.at(T::RBrack) {
            items.push(self.expression()?);
            while self.eat(T::Comma) {
                items.push(self.expression()?);
            }
        }
        self.expect(T::RBrack, "']'")?;
        Ok(Expr::ListLiteral(items))
    }

    fn predicate_function(&mut self) -> CypherResult<Expr> {
        let name = self.bump().text.to_ascii_lowercase();
        self.expect(T::LParen, "'('")?;
        let variable = self.variable()?;
        if !self.eat_kw(K::In) {
            return Err(self.mismatched("IN"));
        }
        let list = self.expression()?;
        let predicate = if self.eat_kw(K::Where) {
            Some(Box::new(self.expression()?))
        } else {
            None
        };
        self.expect(T::RParen, "')'")?;
        Ok(Expr::PredicateFunction {
            name,
            variable,
            list: Box::new(list),
            predicate,
        })
    }

    fn map_entries(&mut self) -> CypherResult<Vec<(String, Expr)>> {
        self.expect(T::LBrace, "'{'")?;
        let mut out: Vec<(String, Expr)> = Vec::new();
        if !self.at(T::RBrace) {
            loop {
                let key = self.symbolic_name()?;
                self.expect(T::Colon, "':'")?;
                let value = self.expression()?;
                // Duplicate keys: last wins, insertion order preserved.
                if let Some(slot) = out.iter_mut().find(|(k, _)| *k == key) {
                    slot.1 = value;
                } else {
                    out.push((key, value));
                }
                if !self.eat(T::Comma) {
                    break;
                }
            }
        }
        self.expect(T::RBrace, "'}'")?;
        Ok(out)
    }
}

#[derive(Default)]
struct RelDetail {
    variable: Option<String>,
    types: Vec<String>,
    variable_length: bool,
    min_hops: Option<u32>,
    max_hops: Option<u32>,
    properties: Vec<(String, Expr)>,
}

fn str_op(op: StrOp, left: Expr, right: Expr) -> Expr {
    Expr::StringOp {
        op,
        left: Box::new(left),
        right: Box::new(right),
    }
}

/// Decode a `STRING_LITERAL` token (quotes included) into its value.
///
/// Unknown escapes keep their backslash (`\q` → `\q`), matching `parseStringLiteral`.
pub fn decode_string_literal(raw: &str) -> String {
    let chars: Vec<char> = raw.chars().collect();
    if chars.len() < 2 {
        return String::new();
    }
    let quote = chars[0];
    let body = &chars[1..chars.len() - 1];
    let mut out = String::with_capacity(body.len());
    let mut i = 0;
    while i < body.len() {
        let c = body[i];
        if c == '\\' && i + 1 < body.len() {
            let n = body[i + 1];
            match n {
                '\\' => {
                    out.push('\\');
                    i += 2;
                }
                '\'' => {
                    out.push('\'');
                    i += 2;
                }
                '"' => {
                    out.push('"');
                    i += 2;
                }
                'n' => {
                    out.push('\n');
                    i += 2;
                }
                'r' => {
                    out.push('\r');
                    i += 2;
                }
                't' => {
                    out.push('\t');
                    i += 2;
                }
                'b' => {
                    out.push('\u{8}');
                    i += 2;
                }
                'u' | 'U' => {
                    let count = if n == 'u' { 4 } else { 8 };
                    if i + count + 2 <= body.len() {
                        let hex: String = body[i + 2..i + count + 2].iter().collect();
                        match u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                            Some(ch) => {
                                out.push(ch);
                                i += count + 2;
                            }
                            None => {
                                out.push('\\');
                                out.push(n);
                                i += 2;
                            }
                        }
                    } else {
                        // Fewer than 4 chars remain: the 'u' is silently dropped.
                        i = body.len();
                    }
                }
                other => {
                    out.push('\\');
                    out.push(other);
                    i += 2;
                }
            }
        } else if c == quote && i + 1 < body.len() && body[i + 1] == quote {
            out.push(quote);
            i += 2;
        } else {
            out.push(c);
            i += 1;
        }
    }
    out
}

#[cfg(test)]
mod mutation_tests {
    use super::*;
    #[test]
    fn retains_set_remove_and_merge_actions_in_the_production_ast() {
        let clauses=parse("MERGE (n:A {id:1}) ON CREATE SET n.x=2, n:B:C ON MATCH SET n += {seen:true} REMOVE n.x, n:B:C RETURN n").unwrap();
        let Clause::Merge {
            pattern,
            on_create,
            on_match,
        } = &clauses[0]
        else {
            panic!("merge")
        };
        assert_eq!(pattern.nodes[0].labels, vec!["A"]);
        assert_eq!(
            pattern.nodes[0].properties,
            vec![("id".into(), Expr::Literal(Literal::Int(1)))]
        );
        assert!(
            matches!(&on_create[0],SetItem::Property{variable,key,value:Expr::Literal(Literal::Int(2))} if variable=="n" && key=="x")
        );
        assert!(
            matches!(&on_create[1],SetItem::Labels{variable,labels} if variable=="n" && labels==&["B","C"])
        );
        assert!(matches!(&on_match[0],SetItem::Map{variable,merge:true,..} if variable=="n"));
        let Clause::Remove(items) = &clauses[1] else {
            panic!("remove")
        };
        assert_eq!(
            items,
            &vec![
                RemoveItem::Property {
                    variable: "n".into(),
                    key: "x".into()
                },
                RemoveItem::Labels {
                    variable: "n".into(),
                    labels: vec!["B".into(), "C".into()]
                }
            ]
        );
    }
}

#[cfg(test)]
mod graph_predicate_tests {
    use super::*;
    #[test]
    fn distinguishes_node_labels_relationship_patterns_and_parenthesized_arithmetic() {
        let clauses =
            parse("MATCH(n) WHERE n:A:B AND NOT (n)-[:R*1..2]->(:C) RETURN (1)-2 AS value")
                .unwrap();
        let Clause::Where(Expr::And(label, not)) = &clauses[1] else {
            panic!("where")
        };
        assert!(
            matches!(label.as_ref(),Expr::LabelPredicate{expr,labels} if matches!(expr.as_ref(),Expr::Variable(v) if v=="n") && labels==&["A","B"])
        );
        let Expr::Not(inner) = not.as_ref() else {
            panic!("not")
        };
        let Expr::PatternPredicate(pattern) = inner.as_ref() else {
            panic!("pattern")
        };
        assert_eq!(pattern.nodes[0].variable.as_deref(), Some("n"));
        assert_eq!(pattern.nodes[1].labels, vec!["C"]);
        assert_eq!(pattern.rels[0].types, vec!["R"]);
        assert_eq!(
            (pattern.rels[0].min_hops, pattern.rels[0].max_hops),
            (Some(1), Some(2))
        );
        assert!(
            matches!(&clauses[2],Clause::Return{items:Some(items),..} if matches!(items[0].expr,Expr::Binary{op:BinOp::Sub,..}))
        );
    }
}

#[cfg(test)]
mod correlated_expression_tests {
    use super::*;
    #[test]
    fn pattern_comprehension_and_exists_keep_patterns_filters_maps_and_native_clauses() {
        let clauses =
            parse("MATCH(a) RETURN [p=(a)-[:R]->(b) WHERE b.x=1 | length(p)] AS paths").unwrap();
        let Clause::Return {
            items: Some(items), ..
        } = &clauses[1]
        else {
            panic!("return")
        };
        let Expr::PatternComprehension {
            pattern,
            filter,
            map,
        } = &items[0].expr
        else {
            panic!("comprehension")
        };
        assert_eq!(pattern.path_variable.as_deref(), Some("p"));
        assert_eq!(pattern.rels[0].types, vec!["R"]);
        assert!(matches!(
            filter.as_deref(),
            Some(Expr::Comparison { op: CmpOp::Eq, .. })
        ));
        assert!(
            matches!(map.as_ref(),Expr::FunctionCall{name,args,..} if name=="length" && matches!(&args[0],Expr::Variable(v) if v=="p"))
        );
        let clauses = parse("MATCH(a) WHERE EXISTS { (a)-->(b) WHERE b.x=1 } RETURN a").unwrap();
        let Clause::Where(Expr::ExistsSubquery(inside)) = &clauses[1] else {
            panic!("exists")
        };
        assert!(
            matches!(&inside[0],Clause::Match{patterns,..} if patterns[0].nodes[1].variable.as_deref()==Some("b"))
        );
        assert!(matches!(&inside[1], Clause::Where(Expr::Comparison { .. })));
    }
}

#[cfg(test)]
mod native_grammar_diagnostics {
    use super::*;
    use crate::{engine::Executor, property_graph::PropertyGraph, Value};
    use std::sync::Arc;

    #[test]
    fn invalid_literals_and_pattern_predicates_have_precise_origin_diagnostics() {
        for (query, detail) in [
            ("RETURN 9223372036854775808", "IntegerOverflow"),
            ("RETURN -9223372036854775809", "IntegerOverflow"),
            ("RETURN 0x8000000000000000", "IntegerOverflow"),
            ("RETURN -0x8000000000000001", "IntegerOverflow"),
            ("RETURN 0o1000000000000000000000", "IntegerOverflow"),
            ("RETURN -0o1000000000000000000001", "IntegerOverflow"),
            ("RETURN 1.34E999", "FloatingPointOverflow"),
            ("MATCH (n $properties) RETURN n", "InvalidParameterUse"),
            (
                "MATCH ()-[r:R $properties]->() RETURN r",
                "InvalidParameterUse",
            ),
            ("MERGE (n $properties) RETURN n", "InvalidParameterUse"),
            (
                "MERGE (a)-[r:R $properties]->(b) RETURN r",
                "InvalidParameterUse",
            ),
            ("MATCH ()-[:R..]->() RETURN 1", "InvalidRelationshipPattern"),
            (
                "MATCH ()-[:R*-2]->() RETURN 1",
                "InvalidRelationshipPattern",
            ),
            (
                "MATCH ()-[:R*1..-2]->() RETURN 1",
                "InvalidRelationshipPattern",
            ),
        ] {
            let error = parse(query).unwrap_err();
            let diagnostic = error.diagnostic().unwrap();
            assert_eq!(
                (diagnostic.error_type, diagnostic.phase, diagnostic.detail),
                ("SyntaxError", "compile time", detail),
                "{query}"
            );
        }
    }

    #[test]
    fn malformed_numbers_keep_identifier_position_diagnostics_distinct() {
        let literal = parse("RETURN 1key").unwrap_err();
        assert_eq!(literal.diagnostic().unwrap().detail, "InvalidNumberLiteral");
        let key = parse("RETURN {1key: 0}").unwrap_err();
        assert_eq!(key.diagnostic().unwrap().detail, "UnexpectedSyntax");
        let label = parse("MATCH (n:1label) RETURN n").unwrap_err();
        assert_eq!(label.diagnostic().unwrap().detail, "UnexpectedSyntax");
    }

    #[test]
    fn valid_signed_boundaries_numeric_parameter_names_and_property_map_parameters_are_preserved() {
        for query in [
            "RETURN -9223372036854775808 AS n",
            "RETURN -0x8000000000000000 AS n",
            "RETURN -0o1000000000000000000000 AS n",
        ] {
            let result = Executor::new(vec![], false).execute(query, None).unwrap();
            assert!(matches!(result.rows[0]["n"], Value::Int(i64::MIN)));
        }
        let parsed = parse("RETURN $1 AS n").unwrap();
        assert!(
            matches!(&parsed[0],Clause::Return {items:Some(items),..} if items[0].expr==Expr::Parameter("1".into()))
        );
        let result = Executor::new(vec![], false)
            .with_params(indexmap::IndexMap::from([("1".into(), Value::Int(7))]))
            .execute("RETURN $1 AS n", None)
            .unwrap();
        assert!(matches!(result.rows[0]["n"], Value::Int(7)));
        let clauses = parse("MATCH (n {key:$1}) RETURN n").unwrap();
        assert!(
            matches!(&clauses[0],Clause::Match {patterns,..} if patterns[0].nodes[0].properties[0].1==Expr::Parameter("1".into()))
        );
        assert!(parse("MATCH ()-[:R*0..2]->() RETURN 1").is_ok());
        let unicode = Executor::new(vec![], false)
            .execute(r"RETURN '\U0001F600' AS n", None)
            .unwrap();
        assert_eq!(unicode.rows[0]["n"].as_str(), Some("😀"));
    }

    #[test]
    fn grammar_failure_occurs_before_an_earlier_mutation_can_execute() {
        let graph = Arc::new(PropertyGraph::new());
        let executor = Executor::property_graph(graph.clone());
        let error = executor
            .execute("CREATE (:A) MATCH (n $properties) RETURN n", None)
            .unwrap_err();
        assert_eq!(error.diagnostic().unwrap().detail, "InvalidParameterUse");
        assert!(graph.snapshot().nodes.is_empty());
    }
}
