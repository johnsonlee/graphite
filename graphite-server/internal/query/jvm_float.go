package query

import (
	"math"
	"math/big"
	"math/bits"
	"strconv"
	"strings"
)

// javaFloatString follows the legacy Java 17 floating-point spelling contract.
// In particular its decimal interval excludes halfway boundaries, uses the
// narrower symmetric interval at binary powers, and preserves the legacy
// integral fast-path rounding. Go's shortest representation differs at 1e23,
// subnormal values, and some integral floats. Integer quotient/remainder arithmetic keeps
// those decisions separate from host floating-point rounding.
func javaFloatString(value float64, width int) string {
	if width == 32 {
		value = float64(float32(value))
	}
	if math.IsNaN(value) {
		return "NaN"
	}
	if math.IsInf(value, 1) {
		return "Infinity"
	}
	if math.IsInf(value, -1) {
		return "-Infinity"
	}
	sign := ""
	if math.Signbit(value) {
		sign = "-"
		value = -value
	}
	if value == 0 {
		return sign + "0.0"
	}
	raw := math.Float64bits(value)
	fraction := raw & ((uint64(1) << 52) - 1)
	unbiased := int(raw>>52) - 1023
	significant := 53
	if raw>>52 == 0 {
		significant = bits.Len64(fraction)
		unbiased = -1074 + significant - 1
	} else {
		fraction |= uint64(1) << 52
	}
	if width == 32 {
		raw32 := math.Float32bits(float32(value))
		fraction = uint64(raw32 & ((uint32(1) << 23) - 1))
		unbiased = int(raw32>>23) - 127
		significant = 24
		if raw32>>23 == 0 {
			significant = bits.Len64(fraction)
			unbiased = -149 + significant - 1
		} else {
			fraction |= uint64(1) << 23
		}
	}
	// Java's legacy integer conversion can retain more digits than a shortest
	// conversion would. Round only the decimal places insignificant at this ULP.
	if unbiased <= 62 && value == math.Trunc(value) {
		integer := new(big.Int).SetUint64(uint64(value))
		places := 0
		if unbiased > significant {
			places = len(new(big.Int).Lsh(big.NewInt(1), uint(unbiased-significant-1)).String()) - 1
		}
		if places > 0 {
			unit := pow10(places)
			remainder := new(big.Int)
			integer.QuoRem(integer, unit, remainder)
			if new(big.Int).Lsh(remainder, 1).Cmp(unit) >= 0 {
				integer.Add(integer, big.NewInt(1))
			}
			integer.Mul(integer, unit)
		}
		digits := integer.String()
		return sign + decimalFloat(digits, len(digits)-1)
	}
	digits, exponent := legacyDecimalDigits(fraction, unbiased, significant)
	return sign + decimalFloat(digits, exponent)
}

// FormatJavaFloat exposes the shared JVM spelling for HTTP materialization.
func FormatJavaFloat(value float64, width int) string { return javaFloatString(value, width) }

// legacyDecimalDigits generates significant digits using integer quotient and
// remainder intervals. The Java17 machine-word branches intentionally retain
// signed overflow in the final rounding decision; reproducing that distinction
// matters for Float values such as raw bits0x6a6b55de.
func legacyDecimalDigits(fraction uint64, binaryExponent, significant int) (string, int) {
	length := bits.Len64(fraction)
	normalized := fraction << uint(53-length)
	mantissa := math.Float64frombits((uint64(1023) << 52) | (normalized & ((uint64(1) << 52) - 1)))
	estimate := (mantissa - 1.5) * 0.289529654
	estimate += 0.176091259
	estimate += float64(binaryExponent) * 0.301029995663981
	decimalExponent := int(math.Floor(estimate))
	usefulBits := length - bits.TrailingZeros64(fraction)
	smallBits := max(0, usefulBits-binaryExponent-1)
	numeratorFive := max(0, -decimalExponent)
	denominatorFive := max(0, decimalExponent)
	numeratorTwo := numeratorFive + smallBits + binaryExponent
	denominatorTwo := denominatorFive + smallBits
	toleranceTwo := numeratorTwo - significant
	numeratorTwo -= usefulBits - 1
	common := min(numeratorTwo, denominatorTwo)
	numeratorTwo -= common
	denominatorTwo -= common
	toleranceTwo -= common
	if usefulBits == 1 {
		toleranceTwo--
	}
	if toleranceTwo < 0 {
		numeratorTwo -= toleranceTwo
		denominatorTwo -= toleranceTwo
		toleranceTwo = 0
	}
	makeScaled := func(five, two int) *big.Int {
		return new(big.Int).Lsh(new(big.Int).Exp(big.NewInt(5), big.NewInt(int64(five)), nil), uint(two))
	}
	numerator := makeScaled(numeratorFive, numeratorTwo)
	numerator.Mul(numerator, new(big.Int).SetUint64(fraction>>uint(bits.TrailingZeros64(fraction))))
	denominator := makeScaled(denominatorFive, denominatorTwo)
	tolerance := makeScaled(numeratorFive, toleranceTwo)
	tenDenominator := new(big.Int).Mul(denominator, big.NewInt(10))
	fiveBits := func(exponent int) int {
		if exponent == 0 {
			return 0
		}
		if exponent >= 27 {
			return exponent * 3
		}
		return new(big.Int).Exp(big.NewInt(5), big.NewInt(int64(exponent)), nil).BitLen()
	}
	numeratorBound := usefulBits + numeratorTwo + fiveBits(numeratorFive)
	denominatorBound := denominatorTwo + 1 + fiveBits(denominatorFive+1)
	machineBits := 0
	if numeratorBound < 64 && denominatorBound < 64 {
		machineBits = 64
		if numeratorBound < 32 && denominatorBound < 32 {
			machineBits = 32
		}
	}
	wrap := func(value *big.Int) *big.Int {
		if machineBits == 0 {
			return value
		}
		modulus := new(big.Int).Lsh(big.NewInt(1), uint(machineBits))
		value.Mod(value, modulus)
		if value.Bit(machineBits-1) != 0 {
			value.Sub(value, modulus)
		}
		return value
	}
	next := func() (byte, bool, bool) {
		quotient, remainder := new(big.Int), new(big.Int)
		quotient.QuoRem(numerator, denominator, remainder)
		numerator = wrap(remainder.Mul(remainder, big.NewInt(10)))
		tolerance = wrap(tolerance.Mul(tolerance, big.NewInt(10)))
		low := numerator.Cmp(tolerance) < 0
		combined := wrap(new(big.Int).Add(numerator, tolerance))
		high := combined.Cmp(tenDenominator) > 0
		if machineBits == 0 {
			high = combined.Cmp(tenDenominator) >= 0
		}
		return byte(quotient.Int64()) + '0', low, high
	}
	digit, low, high := next()
	digits := []byte{}
	if digit == '0' && !high {
		decimalExponent--
	} else {
		digits = append(digits, digit)
	}
	if decimalExponent < -3 || decimalExponent >= 8 {
		low = false
		high = false
	}
	for !low && !high {
		digit, low, high = next()
		if machineBits != 0 && tolerance.Sign() <= 0 {
			low = true
			high = true
		}
		digits = append(digits, digit)
		if len(digits) > 25 {
			panic("Java floating point digit generation did not converge")
		}
	}
	difference := wrap(new(big.Int).Sub(wrap(new(big.Int).Lsh(new(big.Int).Set(numerator), 1)), tenDenominator)).Sign()
	if high && (!low || difference > 0 || difference == 0 && (digits[len(digits)-1]-'0')%2 != 0) {
		i := len(digits) - 1
		for i > 0 && digits[i] == '9' {
			digits[i] = '0'
			i--
		}
		if digits[i] == '9' {
			digits[i] = '1'
			decimalExponent++
		} else {
			digits[i]++
		}
	}
	return string(digits), decimalExponent
}

func pow10(n int) *big.Int { return new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(n)), nil) }
func decimalFloat(digits string, exponent int) string {
	digits = strings.TrimRight(digits, "0")
	if digits == "" {
		digits = "0"
	}
	if exponent >= -3 && exponent < 7 {
		position := exponent + 1
		if position <= 0 {
			return "0." + strings.Repeat("0", -position) + digits
		}
		if position >= len(digits) {
			return digits + strings.Repeat("0", position-len(digits)) + ".0"
		}
		return digits[:position] + "." + digits[position:]
	}
	tail := "0"
	if len(digits) > 1 {
		tail = digits[1:]
	}
	return digits[:1] + "." + tail + "E" + strconv.Itoa(exponent)
}
