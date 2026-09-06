// Package cypher parses the read-only Cypher language accepted by Graphite.
package cypher

// Query contains UNION branches. UnionAll[i] joins Branches[i] and Branches[i+1].
type Query struct {
	Branches []SingleQuery
	UnionAll []bool
}
type SingleQuery struct{ Clauses []Clause }
type Clause interface{ clause() }
type MatchClause struct {
	Patterns []Pattern
	Optional bool
	Where    Expr
}

func (MatchClause) clause() {}

type ProjectionClause struct {
	With, Distinct, All bool
	Items               []ReturnItem
	Where               Expr
	OrderBy             []SortItem
	Skip, Limit         Expr
}

func (ProjectionClause) clause() {}

type UnwindClause struct {
	Expression Expr
	Variable   string
}

func (UnwindClause) clause() {}

type ReturnItem struct {
	Expression Expr
	Alias      string
	Text       string
}
type SortItem struct {
	Expression Expr
	Descending bool
}
type Pattern struct {
	PathVariable  string
	Nodes         []NodePattern
	Relationships []RelationshipPattern
}
type NodePattern struct {
	Variable   string
	Labels     []string
	Properties map[string]Expr
}
type RelationshipPattern struct {
	Variable         string
	Types            []string
	Properties       map[string]Expr
	Direction        Direction
	VariableLength   bool
	MinHops, MaxHops *int
}
type Direction string

const (
	Outgoing Direction = "outgoing"
	Incoming Direction = "incoming"
	Both     Direction = "both"
)

type Expr interface{ expr() }
type Literal struct{ Value any }

func (Literal) expr() {}

type Variable struct{ Name string }

func (Variable) expr() {}

type Parameter struct{ Name string }

func (Parameter) expr() {}

type Property struct {
	Object Expr
	Key    string
}

func (Property) expr() {}

type Binary struct {
	Op          string
	Left, Right Expr
}

func (Binary) expr() {}

type Unary struct {
	Op      string
	Operand Expr
}

func (Unary) expr() {}

type Call struct {
	Name           string
	Arguments      []Expr
	Distinct, Star bool
}

func (Call) expr() {}

type List struct{ Elements []Expr }

func (List) expr() {}

type Map struct {
	Entries map[string]Expr
	Keys    []string
}

func (Map) expr() {}

type Index struct{ Object, Index Expr }

func (Index) expr() {}

type Slice struct{ Object, From, To Expr }

func (Slice) expr() {}

type Case struct {
	Test  Expr
	Whens []When
	Else  Expr
}

func (Case) expr() {}

type When struct{ Condition, Result Expr }
type ListComprehension struct {
	Variable                string
	List, Where, Projection Expr
}

func (ListComprehension) expr() {}

type Predicate struct {
	Name, Variable string
	List, Where    Expr
}

func (Predicate) expr() {}

// Mutation clauses are parsed for source fidelity. Read-only execution rejects
// these AST nodes; recognizing syntax never authorizes a graph modification.
type CreateClause struct{ Patterns []Pattern }

func (CreateClause) clause() {}

type DeleteClause struct {
	Expressions []Expr
	Detach      bool
}

func (DeleteClause) clause() {}

type SetClause struct{ Items []SetItem }

func (SetClause) clause() {}

type SetItem struct {
	Kind               SetKind
	Variable, Property string
	Expression         Expr
	Labels             []string
}
type SetKind string

const (
	SetProperty        SetKind = "property"
	SetAllProperties   SetKind = "all-properties"
	SetMergeProperties SetKind = "merge-properties"
	SetLabels          SetKind = "labels"
)

type RemoveClause struct{ Items []RemoveItem }

func (RemoveClause) clause() {}

type RemoveItem struct {
	Kind               RemoveKind
	Variable, Property string
	Labels             []string
}
type RemoveKind string

const (
	RemoveProperty RemoveKind = "property"
	RemoveLabels   RemoveKind = "labels"
)
