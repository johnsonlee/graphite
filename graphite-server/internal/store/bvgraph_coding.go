package store

import (
	"fmt"
	"math/bits"
	"strings"
)

// Numerical coding identifiers and flag slots are part of the WebGraph wire
// format (BVGraph 3.6.12 / CompressionFlags). Missing slots retain defaults.
const (
	codeDelta  = 1
	codeGamma  = 2
	codeGolomb = 3
	codeUnary  = 5
	codeZeta   = 6
	codeNibble = 7
)

type compressionCoding struct{ degree, block, residual, reference, blockCount int }

func parseCompressionFlags(value string) (compressionCoding, error) {
	result := compressionCoding{codeGamma, codeGamma, codeZeta, codeUnary, codeGamma}
	if value == "" {
		return result, nil
	}
	flags := map[string]int{
		"GAMMA": codeGamma, "DELTA": codeDelta,
		"OUTDEGREES_GAMMA": codeGamma, "OUTDEGREES_DELTA": codeDelta,
		"BLOCKS_GAMMA": codeGamma << 4, "BLOCKS_DELTA": codeDelta << 4,
		"RESIDUALS_GAMMA": codeGamma << 8, "RESIDUALS_DELTA": codeDelta << 8,
		"RESIDUALS_ZETA": codeZeta << 8, "RESIDUALS_GOLOMB": codeGolomb << 8, "RESIDUALS_NIBBLE": codeNibble << 8,
		"REFERENCES_GAMMA": codeGamma << 12, "REFERENCES_DELTA": codeDelta << 12, "REFERENCES_UNARY": codeUnary << 12,
		"BLOCK_COUNT_GAMMA": codeGamma << 16, "BLOCK_COUNT_DELTA": codeDelta << 16, "BLOCK_COUNT_UNARY": codeUnary << 16,
		"OFFSETS_GAMMA": codeGamma << 20, "OFFSETS_DELTA": codeDelta << 20,
	}
	mask := 0
	// Java String.split discards trailing empty tokens before trimming names.
	names := strings.Split(value, "|")
	for len(names) > 0 && names[len(names)-1] == "" {
		names = names[:len(names)-1]
	}
	for _, name := range names {
		v, ok := flags[strings.TrimSpace(name)]
		if !ok {
			return result, fmt.Errorf("unsupported BVGraph compression flag %q", name)
		}
		mask |= v
	}
	fields := []*int{&result.degree, &result.block, &result.residual, &result.reference, &result.blockCount}
	for slot, field := range fields {
		if v := mask >> (slot * 4) & 15; v != 0 {
			*field = v
		}
	}
	valid := func(v int, allowed ...int) bool {
		for _, a := range allowed {
			if v == a {
				return true
			}
		}
		return false
	}
	if !valid(result.degree, codeGamma, codeDelta) || !valid(result.block, codeGamma, codeDelta, codeUnary) || !valid(result.residual, codeGamma, codeDelta, codeZeta, codeGolomb, codeNibble) || !valid(result.reference, codeUnary, codeGamma, codeDelta) || !valid(result.blockCount, codeGamma, codeDelta, codeUnary) || !valid(mask>>20&15, 0, codeGamma, codeDelta) {
		return result, fmt.Errorf("unsupported BVGraph compression combination %q", value)
	}
	return result, nil
}

// The codes encode nonnegative integers. Gamma and delta represent n+1;
// Golomb represents a unary quotient and a truncated-binary remainder;
// nibble coding uses a stop bit followed by three payload bits per group.
func (b *bitReader) natural(coding, parameter int) int64 {
	switch coding {
	case codeGamma:
		return b.gamma()
	case codeUnary:
		return int64(b.unary())
	case codeZeta:
		return b.zeta(parameter)
	case codeDelta:
		n := b.gamma()
		if n > 62 {
			b.fail("BVGraph delta overflow")
			return 0
		}
		return (int64(1)<<n | b.bits(int(n))) - 1
	case codeGolomb:
		// Modulus zero is the empty code for zero, used by degenerate streams.
		if parameter == 0 {
			return 0
		}
		q := int64(b.unary())
		width := bits.Len(uint(parameter)) - 1
		cutoff := (int64(1) << (width + 1)) - int64(parameter)
		r := b.bits(width)
		if r >= cutoff {
			r = 2*r + b.bit() - cutoff
		}
		if q > (1<<63-1-r)/int64(parameter) {
			b.fail("BVGraph Golomb overflow")
			return 0
		}
		return q*int64(parameter) + r
	case codeNibble:
		var n int64
		for b.err == nil {
			stop := b.bit()
			if n > (1<<63-1)>>3 {
				b.fail("BVGraph nibble overflow")
				return 0
			}
			n = n<<3 | b.bits(3)
			if stop != 0 {
				return n
			}
		}
		return 0
	default:
		b.fail("unsupported BVGraph integer coding %d", coding)
		return 0
	}
}
