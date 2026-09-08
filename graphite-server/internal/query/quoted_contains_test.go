package query_test

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/query"
)

func requireQuotedContainsRows(t *testing.T, source string, parameters map[string]any, columns []string, rows []map[string]any) {
	t.Helper()
	got, err := query.Execute(context.Background(), nil, source, parameters, -1)
	if err != nil {
		t.Fatalf("execute %q: %v", source, err)
	}
	if !reflect.DeepEqual(got.Columns, columns) || !reflect.DeepEqual(got.Rows, rows) {
		t.Fatalf("execute %q = %#v; want columns=%#v rows=%#v", source, got, columns, rows)
	}
}

func TestQuotedContainsPublicValues(t *testing.T) {
	const source = "RETURN $text =~ $pattern AS matched"
	// These are the literals in real64 cases 892, 893 and 894. The public
	// parameter cases have actual-main results in
	// docs/go-server-baseline/native-regex-quoted-contains/public-main.json.
	for _, literal := range []string{"GraphitePressureAbsent45zeroX", "org.apache.tika.", "org"} {
		t.Run(literal, func(t *testing.T) {
			for _, tc := range []struct {
				name string
				text string
				want bool
			}{
				{"empty", "", false},
				{"absent", "unrelated", false},
				{"exact", literal, true},
				{"middle", "head" + literal + "tail", true},
				{"repeated", literal + literal, true},
				{"cr_before", "\r" + literal, false},
				{"lf_after", literal + "\n", false},
				{"crlf_after", literal + "\r\n", false},
				{"nel_before", "\u0085" + literal, false},
				{"ls_after", literal + "\u2028", false},
				{"ps_after", literal + "\u2029", false},
				{"nonascii_before", "é" + literal, true},
				{"supplementary_after", literal + "😀", true},
				{"high_surrogate_before", "\xed\xa0\x80" + literal, true},
				{"low_surrogate_after", literal + "\xed\xb0\x80", true},
				{"vertical_before", "\v" + literal, true},
				{"form_feed_after", literal + "\f", true},
				{"nul_after", literal + "\x00", true},
			} {
				t.Run(tc.name, func(t *testing.T) {
					requireQuotedContainsRows(t, source,
						map[string]any{"text": tc.text, "pattern": `.*\Q` + literal + `\E.*`},
						[]string{"matched"}, []map[string]any{{"matched": tc.want}})
				})
			}
		})
	}
}

func TestQuotedContainsPublicNullAndOperandOrder(t *testing.T) {
	for _, tc := range []struct {
		name, source string
		want         any
	}{
		{"null_skips_division", "RETURN null =~ (1/0) AS matched", nil},
		{"number_skips_division", "RETURN 17 =~ (1/0) AS matched", nil},
		{"null_skips_unknown_function", "RETURN null =~ unknownRegexFunction() AS matched", nil},
		{"null_skips_invalid_pattern", "RETURN null =~ '[' AS matched", nil},
		{"null_pattern", "RETURN 'org' =~ null AS matched", nil},
		{"number_pattern", "RETURN 'org' =~ 17 AS matched", nil},
		{"or_null_operand", "RETURN true OR (null =~ (1/0)) AS matched", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			requireQuotedContainsRows(t, tc.source, nil,
				[]string{"matched"}, []map[string]any{{"matched": tc.want}})
		})
	}
	for _, value := range []any{nil, int32(17), false, []any{"org"}, map[string]any{"value": "org"}} {
		requireQuotedContainsRows(t, "RETURN $text =~ (1/0) AS matched", map[string]any{"text": value},
			[]string{"matched"}, []map[string]any{{"matched": nil}})
	}
}

func TestQuotedContainsPublicORErrorOrder(t *testing.T) {
	const patternError = "Unclosed character class near index 0\n[\n^"
	for _, tc := range []struct {
		name, source, class, message string
	}{
		{"string_evaluates_rhs", "RETURN 'org' =~ (1/0) AS matched", "CypherException", "Division by zero"},
		{"invalid_pattern", "RETURN 'org' =~ '[' AS matched", "PatternSyntaxException", patternError},
		{"true_does_not_skip_regex", "RETURN true OR ('org' =~ '[') AS matched", "PatternSyntaxException", patternError},
		{"false_does_not_skip_regex", "RETURN false OR ('org' =~ '[') AS matched", "PatternSyntaxException", patternError},
		{"regex_error_first", "RETURN ('org' =~ '[') OR (1/0=0) AS matched", "PatternSyntaxException", patternError},
		{"division_error_first", "RETURN (1/0=0) OR ('org' =~ '[') AS matched", "CypherException", "Division by zero"},
		{"null_does_not_skip_regex", "RETURN null OR ('org' =~ '[') AS matched", "PatternSyntaxException", patternError},
		{"fast_match_does_not_skip_division", "RETURN ('org' =~ $pattern) OR (1/0=0) AS matched", "CypherException", "Division by zero"},
		{"division_before_fast_match", "RETURN (1/0=0) OR ('org' =~ $pattern) AS matched", "CypherException", "Division by zero"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := query.Execute(context.Background(), nil, tc.source, map[string]any{"pattern": `.*\Qorg\E.*`}, -1)
			var failure *query.Error
			if !errors.As(err, &failure) || failure.Class != tc.class || failure.Message != tc.message || failure.NullMessage {
				t.Fatalf("execute %q error = %#v; want %s: %s", tc.source, err, tc.class, tc.message)
			}
			if !reflect.DeepEqual(got, query.Result{}) {
				t.Fatalf("failed execute returned partial result: %#v", got)
			}
		})
	}
}

func TestQuotedContainsPublicPatternReuse(t *testing.T) {
	const source = "UNWIND $cases AS c RETURN c.tag AS tag, c.text =~ c.pattern AS matched"
	// Reuse a compiled pattern with changing input eligibility, then revisit it
	// after enough distinct patterns to cross the query's regex-cache capacity.
	for _, literal := range []string{"org", "org.apache.tika."} {
		var cases []any
		var want []map[string]any
		add := func(pattern, text string, matched bool) {
			tag := fmt.Sprintf("case-%d", len(cases))
			cases = append(cases, map[string]any{"tag": tag, "text": text, "pattern": pattern})
			want = append(want, map[string]any{"tag": tag, "matched": matched})
		}
		pattern := `.*\Q` + literal + `\E.*`
		add(pattern, literal, true)
		add(pattern, literal+"\n", false)
		add(pattern, "é"+literal, true)
		add(pattern, "missing", false)
		add(pattern, literal+"\u2028", false)
		for i := 0; i < 260; i++ {
			word := fmt.Sprintf("entry-%d", i)
			add(`.*\Q`+word+`\E.*`, "before"+word+"after", true)
		}
		add(pattern, "missing", false)
		add(pattern, literal, true)
		add(pattern, literal+"😀", true)
		add(`(?s).*\Q`+literal+`\E.*`, literal+"\n", true)
		add(pattern, literal+"\n", false)
		requireQuotedContainsRows(t, source, map[string]any{"cases": cases}, []string{"tag", "matched"}, want)
	}
}
