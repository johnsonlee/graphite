package cypher

import (
	"context"
	"github.com/antlr4-go/antlr/v4"
)

// Context checks occur during character lookahead/consumption as well as token
// prediction. Checking only token boundaries cannot interrupt a long quoted
// string, identifier, or comment while the lexer is still constructing it.
type contextCharacters struct {
	antlr.CharStream
	ctx context.Context
}

func (c *contextCharacters) check() {
	if err := c.ctx.Err(); err != nil {
		panic(err)
	}
}
func (c *contextCharacters) LA(offset int) int { c.check(); return c.CharStream.LA(offset) }
func (c *contextCharacters) Consume()          { c.check(); c.CharStream.Consume() }
func (c *contextCharacters) Seek(index int)    { c.check(); c.CharStream.Seek(index) }

type contextTokens struct {
	*antlr.CommonTokenStream
	ctx context.Context
}

func (t *contextTokens) check() {
	if err := t.ctx.Err(); err != nil {
		panic(err)
	}
}
func (t *contextTokens) LA(offset int) int         { t.check(); return t.CommonTokenStream.LA(offset) }
func (t *contextTokens) LT(offset int) antlr.Token { t.check(); return t.CommonTokenStream.LT(offset) }
func (t *contextTokens) Consume()                  { t.check(); t.CommonTokenStream.Consume() }
func (t *contextTokens) Seek(index int)            { t.check(); t.CommonTokenStream.Seek(index) }
