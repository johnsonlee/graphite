//! Cypher AST. Mirrors the Kotlin `CypherClause` / `CypherPattern` / `CypherExpr` model.

#[derive(Debug, Clone, PartialEq)]
pub enum Literal {
    Null,
    Bool(bool),
    /// Kotlin `Int` (fits in i32) or `Long`.
    Int(i64),
    Float(f64),
    Str(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BinOp {
    Add,
    Sub,
    Mul,
    Div,
    Mod,
    Pow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CmpOp {
    Eq,
    Ne,
    Lt,
    Gt,
    Le,
    Ge,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StrOp {
    StartsWith,
    EndsWith,
    Contains,
    Regex,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Expr {
    Literal(Literal),
    Variable(String),
    Parameter(String),
    Property {
        expr: Box<Expr>,
        key: String,
    },
    FunctionCall {
        name: String,
        distinct: bool,
        args: Vec<Expr>,
    },
    CountStar,
    /// `DISTINCT expr` used as an atom.
    Distinct(Box<Expr>),
    Binary {
        op: BinOp,
        left: Box<Expr>,
        right: Box<Expr>,
    },
    Unary {
        negate: bool,
        expr: Box<Expr>,
    },
    Comparison {
        op: CmpOp,
        left: Box<Expr>,
        right: Box<Expr>,
    },
    StringOp {
        op: StrOp,
        left: Box<Expr>,
        right: Box<Expr>,
    },
    In {
        left: Box<Expr>,
        right: Box<Expr>,
    },
    IsNull(Box<Expr>),
    IsNotNull(Box<Expr>),
    Not(Box<Expr>),
    And(Box<Expr>, Box<Expr>),
    Or(Box<Expr>, Box<Expr>),
    Xor(Box<Expr>, Box<Expr>),
    Case {
        test: Option<Box<Expr>>,
        whens: Vec<(Expr, Expr)>,
        else_expr: Option<Box<Expr>>,
    },
    ListLiteral(Vec<Expr>),
    MapLiteral(Vec<(String, Expr)>),
    ListComprehension {
        variable: String,
        list: Box<Expr>,
        filter: Option<Box<Expr>>,
        map: Option<Box<Expr>>,
    },
    /// any/all/none/single
    PredicateFunction {
        name: String,
        variable: String,
        list: Box<Expr>,
        predicate: Option<Box<Expr>>,
    },
    Subscript {
        expr: Box<Expr>,
        index: Box<Expr>,
    },
    Slice {
        expr: Box<Expr>,
        from: Option<Box<Expr>>,
        to: Option<Box<Expr>>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    Outgoing,
    Incoming,
    Both,
}

#[derive(Debug, Clone, PartialEq)]
pub struct NodePattern {
    pub variable: Option<String>,
    pub labels: Vec<String>,
    pub properties: Vec<(String, Expr)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RelPattern {
    pub variable: Option<String>,
    pub types: Vec<String>,
    pub direction: Direction,
    pub variable_length: bool,
    pub min_hops: Option<u32>,
    pub max_hops: Option<u32>,
    pub properties: Vec<(String, Expr)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Pattern {
    pub path_variable: Option<String>,
    pub nodes: Vec<NodePattern>,
    /// `rels.len() == nodes.len() - 1`
    pub rels: Vec<RelPattern>,
}

impl Pattern {
    /// All variables introduced by the pattern (path, nodes, relationships) in order.
    pub fn variables(&self) -> Vec<&str> {
        let mut out = Vec::new();
        if let Some(p) = &self.path_variable {
            out.push(p.as_str());
        }
        for (i, n) in self.nodes.iter().enumerate() {
            if let Some(v) = &n.variable {
                out.push(v.as_str());
            }
            if let Some(r) = self.rels.get(i) {
                if let Some(v) = &r.variable {
                    out.push(v.as_str());
                }
            }
        }
        out
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReturnItem {
    pub expr: Expr,
    pub alias: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct OrderItem {
    pub expr: Expr,
    pub descending: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Clause {
    Match {
        patterns: Vec<Pattern>,
        optional: bool,
        /// Only populated for OPTIONAL MATCH; plain MATCH emits a separate Where clause.
        where_clause: Option<Expr>,
    },
    Where(Expr),
    Unwind {
        expr: Expr,
        variable: String,
    },
    With {
        distinct: bool,
        /// None means `WITH *`
        items: Option<Vec<ReturnItem>>,
        where_clause: Option<Expr>,
    },
    Return {
        distinct: bool,
        /// None means `RETURN *`
        items: Option<Vec<ReturnItem>>,
    },
    OrderBy(Vec<OrderItem>),
    Skip(Expr),
    Limit(Expr),
    Create(Vec<Pattern>),
    Delete {
        detach: bool,
        exprs: Vec<Expr>,
    },
    Set,
    Remove,
    /// UNION marker between clause runs; `all` = UNION ALL.
    Union {
        all: bool,
    },
}
