package javamath

// Derived from Sun fdlibm s_sin.c, s_cos.c, k_sin.c and k_cos.c.
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this software is freely
// granted, provided that this notice is preserved.
import "math"

// Sin returns the sine using the Java 17 HotSpot ARM64 rounding target.
func Sin(x float64) float64 { return sincos(x, false) }

// Cos returns the cosine using the Java 17 HotSpot ARM64 rounding target.
func Cos(x float64) float64 { return sincos(x, true) }

func sincos(x float64, cosine bool) float64 {
	ix := hi(x) & 0x7fffffff
	if ix < 0x3e400000 {
		if cosine {
			return 1
		}
		return x
	}
	if ix >= 0x7ff00000 {
		return x - x
	}
	if ix <= 0x3fe921fb {
		if cosine {
			return kernelCos(x, 0, ix)
		}
		return kernelSin(x, 0, false)
	}
	n, a, b := remPi2Mode(x, true)
	if cosine {
		n++
	}
	switch n & 3 {
	case 0:
		return kernelSin(a, b, true)
	case 1:
		return kernelCos(a, b, ix)
	case 2:
		return -kernelSin(a, b, true)
	default:
		return -kernelCos(a, b, ix)
	}
}

// The fdlibm polynomials are evaluated with explicit fused operations to
// reproduce this target's rounding rather than the StrictMath evaluation.
func kernelSin(x, y float64, tail bool) float64 {
	const s1 = -1.66666666666666324348e-1
	const s2 = 8.33333333332248946124e-3
	const s3 = -1.98412698298579493134e-4
	const s4 = 2.75573137070700676789e-6
	const s5 = -2.50507602534068634195e-8
	const s6 = 1.58969099521155010221e-10
	z := mul(x, x)
	v := mul(z, x)
	r := math.FMA(z, s6, s5)
	r = math.FMA(z, r, s4)
	r = math.FMA(z, r, s3)
	r = math.FMA(z, r, s2)
	if !tail {
		return math.FMA(v, math.FMA(z, r, s1), x)
	}
	a := math.FMA(-v, r, mul(.5, y))
	b := math.FMA(-z, a, y)
	return x + math.FMA(v, s1, b)
}

func kernelCos(x, y float64, ix uint32) float64 {
	// ix deliberately describes the original argument. The target chooses its
	// compensated subtraction interval before argument reduction, so reduced
	// arguments retain the original interval. Recomputing hi(x) changes bits.
	const c1 = 4.16666666666666019037e-2
	const c2 = -1.38888888888741095749e-3
	const c3 = 2.48015872894767294178e-5
	const c4 = -2.75573143513906633035e-7
	const c5 = 2.08757232129817482790e-9
	const c6 = -1.13596475577881948265e-11
	z := mul(x, x)
	r := math.FMA(z, c6, c5)
	r = math.FMA(z, r, c4)
	r = math.FMA(z, r, c3)
	r = math.FMA(z, r, c2)
	r = math.FMA(z, r, c1)
	z2, xy := mul(z, z), mul(x, y)
	if ix <= 0x3fd33333 {
		return 1 - math.FMA(.5, z, math.FMA(-z2, r, xy))
	}
	qx := .28125
	if ix <= 0x3fe90000 {
		qx = math.Float64frombits(uint64(ix-0x200000) << 32)
	}
	return (1 - qx) - (math.FMA(.5, z, -qx) - math.FMA(z2, r, -xy))
}
