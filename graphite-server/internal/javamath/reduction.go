package javamath

// Derived from Sun fdlibm e_rem_pio2.c and k_rem_pio2.c.
// Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
// Developed at SunSoft, a Sun Microsystems, Inc. business.
// Permission to use, copy, modify, and distribute this software is freely
// granted, provided that this notice is preserved.
import "math"

var twoOverPi = [...]int{0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62,
	0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A,
	0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
	0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41,
	0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8,
	0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
	0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5,
	0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
	0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
	0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880,
	0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
}
var nearPi2 = [...]uint32{0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
	0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
	0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
	0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
	0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
	0x404858EB, 0x404921FB,
}

const pi2one = 1.57079632673412561417
const pi2oneTail = 6.07710050650619224932e-11
const pi2two = 6.07710050630396597660e-11
const pi2twoTail = 2.02226624879595063154e-21
const pi2three = 2.02226624871116645580e-21
const pi2threeTail = 8.47842766036889956997e-32

func remPi2(x float64) (int, float64, float64) { return remPi2Mode(x, false) }

func remPi2Mode(x float64, fused bool) (int, float64, float64) {
	ix := hi(x) & 0x7fffffff
	if ix <= 0x3fe921fb {
		return 0, x, 0
	}
	if ix < 0x4002d97c {
		if x > 0 {
			z := x - pi2one
			if ix != 0x3ff921fb {
				y := z - pi2oneTail
				return 1, y, (z - y) - pi2oneTail
			}
			z -= pi2two
			y := z - pi2twoTail
			return 1, y, (z - y) - pi2twoTail
		}
		z := x + pi2one
		if ix != 0x3ff921fb {
			y := z + pi2oneTail
			return -1, y, (z - y) + pi2oneTail
		}
		z += pi2two
		y := z + pi2twoTail
		return -1, y, (z - y) + pi2twoTail
	}
	if ix <= 0x413921fb {
		t := math.Abs(x)
		n := int(madd(t, 6.36619772367581382433e-1, .5, fused))
		fn := float64(n)
		r := madd(-fn, pi2one, t, fused)
		w := mul(fn, pi2oneTail)
		y := r - w
		if n >= 32 || ix == nearPi2[n-1] {
			j := int(ix >> 20)
			i := j - int((hi(y)>>20)&0x7ff)
			if i > 16 {
				t = r
				w = mul(fn, pi2two)
				r = t - w
				w = madd(fn, pi2twoTail, -((t - r) - w), fused)
				y = r - w
				i = j - int((hi(y)>>20)&0x7ff)
				if i > 49 {
					t = r
					w = mul(fn, pi2three)
					r = t - w
					w = madd(fn, pi2threeTail, -((t - r) - w), fused)
					y = r - w
				}
			}
		}
		tail := (r - y) - w
		if math.Signbit(x) {
			return -n, -y, -tail
		}
		return n, y, tail
	}
	if ix >= 0x7ff00000 {
		return 0, x - x, x - x
	}
	e0 := int(ix>>20) - 1046
	z := withHi(x, uint32(int64(ix)-int64(e0)*1048576))
	tx := [3]float64{}
	for i := 0; i < 2; i++ {
		tx[i] = float64(int(z))
		z = mul(z-tx[i], 16777216)
	}
	tx[2] = z
	nx := 3
	for tx[nx-1] == 0 {
		nx--
	}
	n, y, tail := kernelRemPi2(tx[:nx], e0, fused)
	if math.Signbit(x) {
		return -n, -y, -tail
	}
	return n, y, tail
}
func kernelRemPi2(x []float64, e0 int, fused bool) (int, float64, float64) {
	piPieces := [8]float64{1.57079625129699707031, 7.54978941586159635335e-8, 5.39030252995776476554e-15, 3.28200341580791294123e-22, 1.27065575308067607349e-29, 1.22933308981111328932e-36, 2.73370053816464559624e-44, 2.16741683877804819444e-51}
	const two24 = 16777216.
	const twon24 = 5.9604644775390625e-8
	const jk = 4
	jx := len(x) - 1
	jv := max(0, (e0-3)/24)
	q0 := e0 - 24*(jv+1)
	var f, fq, q [20]float64
	var iq [20]int
	j := jv - jx
	for i := 0; i <= jx+jk; i++ {
		if j >= 0 {
			f[i] = float64(twoOverPi[j])
		}
		j++
	}
	for i := 0; i <= jk; i++ {
		fw := 0.
		for j := 0; j <= jx; j++ {
			fw += mul(x[j], f[jx+i-j])
		}
		q[i] = fw
	}
	jz := jk
	n, ih := 0, 0
	z := 0.
	for {
		z = q[jz]
		i := 0
		for j := jz; j > 0; j-- {
			fw := float64(int(mul(twon24, z)))
			iq[i] = int(z - mul(two24, fw))
			z = q[j-1] + fw
			i++
		}
		z = math.Ldexp(z, q0)
		z -= mul(8, math.Floor(mul(z, .125)))
		n = int(z)
		z -= float64(n)
		ih = 0
		if q0 > 0 {
			i := iq[jz-1] >> (24 - q0)
			n += i
			iq[jz-1] -= i << (24 - q0)
			ih = iq[jz-1] >> (23 - q0)
		} else if q0 == 0 {
			ih = iq[jz-1] >> 23
		} else if z >= .5 {
			ih = 2
		}
		if ih > 0 {
			n++
			carry := 0
			for i := 0; i < jz; i++ {
				j := iq[i]
				if carry == 0 {
					if j != 0 {
						carry = 1
						iq[i] = 0x1000000 - j
					}
				} else {
					iq[i] = 0xffffff - j
				}
			}
			if q0 == 1 {
				iq[jz-1] &= 0x7fffff
			} else if q0 == 2 {
				iq[jz-1] &= 0x3fffff
			}
			if ih == 2 {
				z = 1 - z
				if carry != 0 {
					z -= math.Ldexp(1, q0)
				}
			}
		}
		if z == 0 {
			j := 0
			for i := jz - 1; i >= jk; i-- {
				j |= iq[i]
			}
			if j == 0 {
				k := 1
				for iq[jk-k] == 0 {
					k++
				}
				for i := jz + 1; i <= jz+k; i++ {
					f[jx+i] = float64(twoOverPi[jv+i])
					fw := 0.
					for j := 0; j <= jx; j++ {
						fw += mul(x[j], f[jx+i-j])
					}
					q[i] = fw
				}
				jz += k
				continue
			}
		}
		break
	}
	if z == 0 {
		jz--
		q0 -= 24
		for iq[jz] == 0 {
			jz--
			q0 -= 24
		}
	} else {
		z = math.Ldexp(z, -q0)
		if z >= two24 {
			fw := float64(int(mul(twon24, z)))
			iq[jz] = int(z - mul(two24, fw))
			jz++
			q0 += 24
			iq[jz] = int(fw)
		} else {
			iq[jz] = int(z)
		}
	}
	fw := math.Ldexp(1, q0)
	for i := jz; i >= 0; i-- {
		q[i] = mul(fw, float64(iq[i]))
		fw = mul(fw, twon24)
	}
	for i := jz; i >= 0; i-- {
		fw = 0
		for k := 0; k <= jk && k <= jz-i; k++ {
			fw = madd(piPieces[k], q[i+k], fw, fused)
		}
		fq[jz-i] = fw
	}
	fw = 0
	for i := jz; i >= 0; i-- {
		fw += fq[i]
	}
	y := fw
	fw = fq[0] - fw
	for i := 1; i <= jz; i++ {
		fw += fq[i]
	}
	if ih != 0 {
		return n & 7, -y, -fw
	}
	return n & 7, y, fw
}

// madd chooses the explicitly specified rounding point. Tan uses fdlibm
// separate operations; the ARM64 Math sin/cos target uses fused operations.
func madd(a, b, c float64, fused bool) float64 {
	if fused {
		return math.FMA(a, b, c)
	}
	return mul(a, b) + c
}
