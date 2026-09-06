package javamath

// Derived from Sun fdlibm k_tan.c and s_tan.c.
// Copyright 2004 Sun Microsystems, Inc. All Rights Reserved.
// Permission to use, copy, modify, and distribute this software is freely
// granted, provided that this notice is preserved.

func Tan(x float64) float64 {
	ix := hi(x) & 0x7fffffff
	if ix <= 0x3fe921fb {
		return kernelTan(x, 0, 1)
	}
	if ix >= 0x7ff00000 {
		return x - x
	}
	n, y, tail := remPi2(x)
	return kernelTan(y, tail, 1-((n&1)<<1))
}
func kernelTan(x, y float64, iy int) float64 {
	t := [13]float64{3.33333333333334091986e-1, 1.33333333333201242699e-1, 5.39682539762260521377e-2, 2.18694882948595424599e-2, 8.86323982359930005737e-3, 3.59207910759131235356e-3, 1.45620945432529025516e-3, 5.88041240820264096874e-4, 2.46463134818469906812e-4, 7.81794442939557092300e-5, 7.14072491382608190305e-5, -1.85586374855275456654e-5, 2.59073051863633712884e-5}
	hx := int32(hi(x))
	ix := uint32(hx) & 0x7fffffff
	if ix < 0x3e300000 {
		if x == 0 && iy == -1 {
			return 1 / abs(x)
		}
		if iy == 1 {
			return x
		}
		w := x + y
		z := highPart(w)
		v := y - (z - x)
		a := -1 / w
		tt := highPart(a)
		s := 1 + mul(tt, z)
		return tt + mul(a, s+mul(tt, v))
	}
	if ix >= 0x3fe59428 {
		if hx < 0 {
			x = -x
			y = -y
		}
		z := pio4hi - x
		w := 3.06161699786838301793e-17 - y
		x = z + w
		y = 0
	}
	z := mul(x, x)
	w := mul(z, z)
	r := t[1] + mul(w, t[3]+mul(w, t[5]+mul(w, t[7]+mul(w, t[9]+mul(w, t[11])))))
	v := mul(z, t[2]+mul(w, t[4]+mul(w, t[6]+mul(w, t[8]+mul(w, t[10]+mul(w, t[12]))))))
	s := mul(z, x)
	r = y + mul(z, mul(s, r+v)+y)
	r += mul(t[0], s)
	w = x + r
	if ix >= 0x3fe59428 {
		v = float64(iy)
		return mul(float64(1-((hx>>30)&2)), v-mul(2, x-(mul(w, w)/(w+v)-r)))
	}
	if iy == 1 {
		return w
	}
	z = highPart(w)
	v = r - (z - x)
	a := -1 / w
	tt := highPart(a)
	s = 1 + mul(tt, z)
	return tt + mul(a, s+mul(tt, v))
}
func abs(x float64) float64 {
	if x == 0 {
		return 0
	}
	if x < 0 {
		return -x
	}
	return x
}
