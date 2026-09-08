package javaregex

import "context"

// Java Pattern removes quote delimiters and escapes their contents before
// parsing. Parser error indices refer to this transformed code-point sequence,
// while the exception displays the original source string.
func removeQEQuoting(ctx context.Context, input []rune) ([]rune, error) {
	i, work := 0, 0
	for i < len(input)-1 {
		if work&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		work++
		if input[i] != '\\' {
			i++
		} else if input[i+1] != 'Q' {
			i += 2
		} else {
			break
		}
	}
	if i >= len(input)-1 {
		return input, ctx.Err()
	}
	output := make([]rune, i, len(input))
	copy(output, input[:i])
	i += 2
	inQuote, beginQuote := true, true
	for i < len(input) {
		if work&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		work++
		r := input[i]
		i++
		switch {
		case r >= 128 || r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z':
			output = append(output, r)
		case r >= '0' && r <= '9':
			if beginQuote {
				output = append(output, '\\', 'x', '3')
			}
			output = append(output, r)
		case r != '\\':
			if inQuote {
				output = append(output, '\\')
			}
			output = append(output, r)
		case inQuote:
			if i < len(input) && input[i] == 'E' {
				i++
				inQuote = false
			} else {
				output = append(output, '\\', '\\')
			}
		default:
			if i < len(input) && input[i] == 'Q' {
				i++
				inQuote, beginQuote = true, true
				continue
			}
			output = append(output, r)
			if i < len(input) {
				output = append(output, input[i])
				i++
			}
		}
		beginQuote = false
	}
	return output, ctx.Err()
}
