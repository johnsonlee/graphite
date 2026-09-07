package audit

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"
)

// Design model only: no production dependency, patch or runtime cache.
type wantedCursor struct {
	table  []string
	wanted map[string]bool
	first  map[string]int32
	next   int
	poll   func(int) error
}

func newCursor(table, targets []string, poll func(int) error) *wantedCursor {
	w := map[string]bool{}
	for _, s := range targets {
		w[s] = true
	}
	return &wantedCursor{table: table, wanted: w, first: map[string]int32{}, poll: poll}
}
func (c *wantedCursor) find(text string) (int32, error) {
	// A nonempty old scan checks at j0 on every lookup, even if it can now be cached.
	if len(c.table) > 0 && c.poll != nil {
		if err := c.poll(-1); err != nil {
			return -1, err
		}
	}
	if sid, ok := c.first[text]; ok {
		return sid, nil
	}
	for c.next < len(c.table) {
		i := c.next
		if c.poll != nil {
			if err := c.poll(i); err != nil {
				return -1, err
			}
		}
		c.next++
		value := c.table[i]
		if c.wanted[value] {
			if _, seen := c.first[value]; !seen {
				c.first[value] = int32(i)
			}
		}
		if value == text {
			return int32(i), nil
		}
	}
	return -1, nil
}
func oldFind(table []string, text string, poll func(int) error) (int32, error) {
	for i, s := range table {
		if poll != nil {
			if err := poll(i); err != nil {
				return -1, err
			}
		}
		if s == text {
			return int32(i), nil
		}
	}
	return -1, nil
}
func TestWantedFirstSIDAndExactWTF8(t *testing.T) {
	hi := "\xed\xa0\x80"
	lo := "\xed\xb0\x80"
	table := []string{"", "dup", "dup", "?", hi, lo, "😀", "İ", "i"}
	targets := []string{"dup", hi, "?", lo, "😀", "missing", "", "İ", "i", "dup"}
	c := newCursor(table, targets, nil)
	for _, target := range targets {
		want, _ := oldFind(table, target, nil)
		got, err := c.find(target)
		if err != nil || got != want {
			t.Fatalf("%q old%d got%d %v", target, want, got, err)
		}
	}
	if c.first["dup"] != 1 || c.first[hi] != 4 || c.first["?"] != 3 || c.first[lo] != 5 {
		t.Fatal(c.first)
	}
	if len(c.first) > len(c.wanted) {
		t.Fatal("unrequested whole table retained")
	}
}
func TestOnlyRequestedKeysAndSourceLocalIDs(t *testing.T) {
	a := newCursor([]string{"other", "x", "unused", "y"}, []string{"x", "y"}, nil)
	b := newCursor([]string{"y", "x"}, []string{"x", "y"}, nil)
	x, _ := a.find("y")
	y, _ := b.find("y")
	if x != 3 || y != 0 {
		t.Fatal(x, y)
	}
	if !reflect.DeepEqual(a.first, map[string]int32{"x": 1, "y": 3}) {
		t.Fatal(a.first)
	}
	if _, ok := a.first["unused"]; ok {
		t.Fatal("unrequested key")
	}
}
func TestAliasedTargetsAndPropertyOrder(t *testing.T) {
	props := []string{"caller_class", "callee_name", "graphId", "class"}
	columns := []string{"x", "x", "graph", "nullable"}
	row := map[string]any{"x": "last-wins", "graph": "source", "nullable": nil}
	raw := map[string]bool{"caller_class": true, "callee_name": true}
	wanted := []string{}
	for i, p := range props {
		if raw[p] {
			if text, ok := row[columns[i]].(string); ok {
				wanted = append(wanted, text)
			}
		}
	}
	c := newCursor([]string{"discarded-first", "last-wins"}, wanted, nil)
	events := []string{}
	for i, p := range props {
		if raw[p] {
			sid, err := c.find(row[columns[i]].(string))
			if err != nil {
				t.Fatal(err)
			}
			events = append(events, fmt.Sprintf("Postings(%s,%d)", p, sid))
		}
	}
	if !reflect.DeepEqual(events, []string{"Postings(caller_class,1)", "Postings(callee_name,1)"}) {
		t.Fatal(events)
	}
	if !reflect.DeepEqual(c.wanted, map[string]bool{"last-wins": true}) {
		t.Fatal(c.wanted)
	}
}
func TestEagerBatchCanMaskEarlierPostingsError(t *testing.T) {
	table := make([]string, 1025)
	for i := range table {
		table[i] = "filler"
	}
	table[0] = "early"
	table[1024] = "late"
	targets := []string{"early", "late"}
	bad := errors.New("bad first Postings")
	poll := func(i int) error {
		if i == 1024 {
			return context.Canceled
		}
		return nil
	}
	execute := func(find func(string) (int32, error)) error {
		for _, target := range targets {
			if _, err := find(target); err != nil {
				return err
			}
			return bad
		}
		return nil
	}
	old := execute(func(s string) (int32, error) { return oldFind(table, s, poll) })
	// Proposed eager batch scans for every future wanted text before any Postings.
	eager := func() error {
		for i := range table {
			if err := poll(i); err != nil {
				return err
			}
		}
		return bad
	}()
	cursor := newCursor(table, targets, poll)
	lazy := execute(cursor.find)
	if old != bad || lazy != bad || !errors.Is(eager, context.Canceled) {
		t.Fatalf("old=%v eager=%v lazy=%v", old, eager, lazy)
	}
	t.Logf("old=%v; eager-before-Postings=%v; lazy-at-original-lookup=%v", old, eager, lazy)
}
func TestCachedLookupsStillObserveCancellation(t *testing.T) {
	canceled := false
	poll := func(int) error {
		if canceled {
			return context.Canceled
		}
		return nil
	}
	c := newCursor([]string{"a", "b"}, []string{"a", "b"}, poll)
	if _, err := c.find("b"); err != nil {
		t.Fatal(err)
	}
	canceled = true
	if _, err := c.find("a"); !errors.Is(err, context.Canceled) {
		t.Fatal("cached lookup omitted cancellation", err)
	}
	if _, err := c.find("b"); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
