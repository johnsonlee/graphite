// Package javamath implements a Java 17 HotSpot ARM64 Math numerical target.
// Its pure Go operations specify rounding independently of host architecture.
package javamath

// Derived from Sun fdlibm, https://netlib.org/fdlibm/.
// Copyright (C) 1993, 2004 by Sun Microsystems, Inc. All rights reserved.
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this software is freely
// granted, provided that this notice is preserved.

import "math"

func hi(x float64) uint32 { return uint32(math.Float64bits(x) >> 32) }
func withHi(x float64, h uint32) float64 {
	return math.Float64frombits(uint64(h)<<32 | math.Float64bits(x)&0xffffffff)
}
func highPart(x float64) float64 {
	return math.Float64frombits(math.Float64bits(x) & 0xffffffff00000000)
}

// Explicit rounding conversions preserve the fdlibm multiply/add sequence even
// on compilers permitted to contract ordinary expressions into fused operations.
func mul(a, b float64) float64  { return float64(a * b) }
func invalid(x float64) float64 { zero := x - x; return zero / zero }

func Exp(x float64) float64 {
	const ln2hi = 6.93147180369123816490e-01
	const ln2lo = 1.90821492927058770002e-10
	const p1 = 1.66666666666666019037e-01
	const p2 = -2.77777777770155933842e-03
	const p3 = 6.61375632143793436117e-05
	const p4 = -1.65339022054652515390e-06
	const p5 = 4.13813679705723846039e-08
	hx := hi(x) & 0x7fffffff
	negative := math.Signbit(x)
	if hx >= 0x40862e42 {
		if math.IsNaN(x) {
			return x + x
		}
		if math.IsInf(x, 1) {
			return x
		}
		if math.IsInf(x, -1) {
			return 0
		}
		if x > 7.09782712893383973096e2 {
			return math.Inf(1)
		}
		if x < -7.45133219101941108420e2 {
			return 0
		}
	}
	h, l := 0., 0.
	k := 0
	if hx > 0x3fd62e42 {
		if hx < 0x3ff0a2b2 {
			if negative {
				h = x + ln2hi
				l = -ln2lo
				k = -1
			} else {
				h = x - ln2hi
				l = ln2lo
				k = 1
			}
		} else {
			half := .5
			if negative {
				half = -.5
			}
			k = int(mul(1.44269504088896338700e0, x) + half)
			t := float64(k)
			h = x - mul(t, ln2hi)
			l = mul(t, ln2lo)
		}
		x = h - l
	} else if hx < 0x3e300000 {
		return 1 + x
	}
	t := mul(x, x)
	c := x - mul(t, p1+mul(t, p2+mul(t, p3+mul(t, p4+mul(t, p5)))))
	if k == 0 {
		return 1 - (mul(x, c)/(c-2) - x)
	}
	y := 1 - ((l - mul(x, c)/(2-c)) - h)
	if k >= -1021 {
		return withHi(y, uint32(int64(hi(y))+int64(k)*1048576))
	}
	y = withHi(y, uint32(int64(hi(y))+int64(k+1000)*1048576))
	return mul(y, 9.33263618503218878990e-302)
}

const pio2hi = 1.57079632679489655800e0
const pio2lo = 6.12323399573676603587e-17
const pio4hi = 7.85398163397448278999e-1

func asinRational(t float64) (float64, float64) {
	p := mul(t, 1.66666666666666657415e-1+mul(t, -3.25565818622400915405e-1+mul(t, 2.01212532134862925881e-1+mul(t, -4.00555345006794114027e-2+mul(t, 7.91534994289814532176e-4+mul(t, 3.47933107596021167570e-5))))))
	q := 1 + mul(t, -2.40339491173441421878+mul(t, 2.02094576023350569471+mul(t, -6.88283971605453293030e-1+mul(t, 7.70381505559019352791e-2))))
	return p, q
}
func Asin(x float64) float64 {
	ix := hi(x) & 0x7fffffff
	if ix >= 0x3ff00000 {
		if math.Abs(x) == 1 {
			return mul(x, pio2hi) + mul(x, pio2lo)
		}
		return invalid(x)
	}
	if ix < 0x3fe00000 {
		if ix < 0x3e400000 {
			return x
		}
		p, q := asinRational(mul(x, x))
		return x + mul(x, p/q)
	}
	t := mul(1-math.Abs(x), .5)
	p, q := asinRational(t)
	s := Sqrt(t)
	if ix >= 0x3fef3333 {
		w := p / q
		t = pio2hi - (mul(2, s+mul(s, w)) - pio2lo)
	} else {
		w := highPart(s)
		c := (t - mul(w, w)) / (s + w)
		r := p / q
		p = mul(mul(2, s), r) - (pio2lo - mul(2, c))
		q = pio4hi - mul(2, w)
		t = pio4hi - (p - q)
	}
	if math.Signbit(x) {
		return -t
	}
	return t
}
func Acos(x float64) float64 {
	ix := hi(x) & 0x7fffffff
	if ix >= 0x3ff00000 {
		if x == 1 {
			return 0
		}
		if x == -1 {
			return math.Pi + mul(2, pio2lo)
		}
		return invalid(x)
	}
	if ix < 0x3fe00000 {
		if ix <= 0x3c600000 {
			return pio2hi + pio2lo
		}
		p, q := asinRational(mul(x, x))
		return pio2hi - (x - (pio2lo - mul(x, p/q)))
	}
	if x < 0 {
		z := mul(1+x, .5)
		p, q := asinRational(z)
		s := Sqrt(z)
		w := mul(p/q, s) - pio2lo
		return math.Pi - mul(2, s+w)
	}
	z := mul(1-x, .5)
	s := Sqrt(z)
	df := highPart(s)
	c := (z - mul(df, df)) / (s + df)
	p, q := asinRational(z)
	w := mul(p/q, s) + c
	return mul(2, df+w)
}
func Atan(x float64) float64 {
	high := [4]float64{4.63647609000806093515e-1, pio4hi, 9.82793723247329054082e-1, pio2hi}
	low := [4]float64{2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17, pio2lo}
	a := [11]float64{3.33333333333329318027e-1, -1.99999999998764832476e-1, 1.42857142725034663711e-1, -1.11111104054623557880e-1, 9.09088713343650656196e-2, -7.69187620504482999495e-2, 6.66107313738753120669e-2, -5.83357013379057348645e-2, 4.97687799461593236017e-2, -3.65315727442169155270e-2, 1.62858201153657823623e-2}
	negative := math.Signbit(x)
	ix := hi(x) & 0x7fffffff
	if ix >= 0x44100000 {
		if math.IsNaN(x) {
			return x + x
		}
		if negative {
			return -high[3] - low[3]
		}
		return high[3] + low[3]
	}
	id := -1
	if ix < 0x3fdc0000 {
		if ix < 0x3e200000 {
			return x
		}
	} else {
		x = math.Abs(x)
		switch {
		case ix < 0x3fe60000:
			id = 0
			x = (mul(2, x) - 1) / (2 + x)
		case ix < 0x3ff30000:
			id = 1
			x = (x - 1) / (x + 1)
		case ix < 0x40038000:
			id = 2
			x = (x - 1.5) / (1 + mul(1.5, x))
		default:
			id = 3
			x = -1 / x
		}
	}
	z := mul(x, x)
	w := mul(z, z)
	s1 := mul(z, a[0]+mul(w, a[2]+mul(w, a[4]+mul(w, a[6]+mul(w, a[8]+mul(w, a[10]))))))
	s2 := mul(w, a[1]+mul(w, a[3]+mul(w, a[5]+mul(w, a[7]+mul(w, a[9])))))
	if id < 0 {
		return x - mul(x, s1+s2)
	}
	z = high[id] - ((mul(x, s1+s2) - low[id]) - x)
	if negative {
		return -z
	}
	return z
}
func Atan2(y, x float64) float64 {
	if math.IsNaN(x) || math.IsNaN(y) {
		return x + y
	}
	if x == 1 {
		return Atan(y)
	}
	m := 0
	if math.Signbit(x) {
		m |= 2
	}
	if math.Signbit(y) {
		m |= 1
	}
	if y == 0 {
		if m < 2 {
			return y
		}
		if m == 2 {
			return math.Pi
		}
		return -math.Pi
	}
	if x == 0 {
		if math.Signbit(y) {
			return -pio2hi
		}
		return pio2hi
	}
	if math.IsInf(x, 0) {
		if math.IsInf(y, 0) {
			switch m {
			case 0:
				return pio4hi
			case 1:
				return -pio4hi
			case 2:
				return mul(3, pio4hi)
			default:
				return -mul(3, pio4hi)
			}
		}
		switch m {
		case 0:
			return 0
		case 1:
			return math.Copysign(0, -1)
		case 2:
			return math.Pi
		default:
			return -math.Pi
		}
	}
	if math.IsInf(y, 0) {
		return math.Copysign(pio2hi, y)
	}
	k := (int32(hi(y)&0x7fffffff) - int32(hi(x)&0x7fffffff)) >> 20
	z := 0.
	if k > 60 {
		z = pio2hi + mul(.5, 1.2246467991473531772e-16)
	} else if !(math.Signbit(x) && k < -60) {
		z = Atan(math.Abs(y / x))
	}
	switch m {
	case 0:
		return z
	case 1:
		return -z
	case 2:
		return math.Pi - (z - 1.2246467991473531772e-16)
	default:
		return (z - 1.2246467991473531772e-16) - math.Pi
	}
}

// Sqrt uses IEEE-754 correctly rounded square root, as Java requires.
func Sqrt(x float64) float64 { return math.Sqrt(x) }

// Log returns the natural logarithm with fdlibm's rounding sequence.
func Log(x float64) float64 {
	const ln2hi = 6.93147180369123816490e-1
	const ln2lo = 1.90821492927058770002e-10
	const lg1 = 6.666666666666735130e-1
	const lg2 = 3.999999999940941908e-1
	const lg3 = 2.857142874366239149e-1
	const lg4 = 2.222219843214978396e-1
	const lg5 = 1.818357216161805012e-1
	const lg6 = 1.531383769920937332e-1
	const lg7 = 1.479819860511658591e-1
	if x == 0 {
		return math.Inf(-1)
	}
	if x < 0 {
		return invalid(x)
	}
	if math.IsNaN(x) || math.IsInf(x, 1) {
		return x + x
	}
	hx, k := hi(x), 0
	if hx < 0x100000 {
		k -= 54
		x = mul(x, 1.8014398509481984e16)
		hx = hi(x)
	}
	k += int(hx>>20) - 1023
	hx &= 0xfffff
	i := (hx + 0x95f64) & 0x100000
	x = withHi(x, hx|(i^0x3ff00000))
	k += int(i >> 20)
	f, dk := x-1, float64(k)
	if (0xfffff & (2 + hx)) < 3 {
		if f == 0 {
			if k == 0 {
				return 0
			}
			return mul(dk, ln2hi) + mul(dk, ln2lo)
		}
		r := mul(mul(f, f), .5-mul(.33333333333333333, f))
		if k == 0 {
			return f - r
		}
		return mul(dk, ln2hi) - ((r - mul(dk, ln2lo)) - f)
	}
	s := f / (2 + f)
	z := mul(s, s)
	w := mul(z, z)
	t1 := mul(w, lg2+mul(w, lg4+mul(w, lg6)))
	t2 := mul(z, lg1+mul(w, lg3+mul(w, lg5+mul(w, lg7))))
	r := t2 + t1
	if (int32(hx)-0x6147a)|(0x6b851-int32(hx)) > 0 {
		hfsq := mul(mul(.5, f), f)
		if k == 0 {
			return f - (hfsq - mul(s, hfsq+r))
		}
		return mul(dk, ln2hi) - ((hfsq - (mul(s, hfsq+r) + mul(dk, ln2lo))) - f)
	}
	if k == 0 {
		return f - mul(s, f-r)
	}
	return mul(dk, ln2hi) - ((mul(s, f-r) - mul(dk, ln2lo)) - f)
}
func Log10(x float64) float64 {
	hx := int32(hi(x))
	k := 0
	if hx < 0x00100000 {
		if x == 0 {
			return math.Inf(-1)
		}
		if hx < 0 {
			return invalid(x)
		}
		k -= 54
		x = mul(x, 1.80143985094819840000e16)
		hx = int32(hi(x))
	}
	if hx >= 0x7ff00000 {
		return x + x
	}
	k += int(hx>>20) - 1023
	i := 0
	if k < 0 {
		i = 1
	}
	x = withHi(x, (uint32(hx)&0xfffff)|uint32(0x3ff-i)<<20)
	y := float64(k + i)
	z := mul(y, 3.69423907715893078616e-13) + mul(4.34294481903251816668e-1, Log(x))
	return z + mul(y, 3.01029995663611771306e-1)
}
