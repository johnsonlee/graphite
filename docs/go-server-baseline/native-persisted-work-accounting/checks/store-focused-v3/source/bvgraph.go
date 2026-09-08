package store

import (
	"bufio"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
)

type bitReader struct {
	data []byte
	pos  int64
	err  error
}

func (b *bitReader) fail(f string, a ...any) {
	if b.err == nil {
		b.err = fmt.Errorf(f, a...)
	}
}
func (b *bitReader) bit() int64 {
	if b.err != nil {
		return 0
	}
	if b.pos >= int64(len(b.data))*8 {
		b.fail("truncated BVGraph bitstream at bit %d", b.pos)
		return 0
	}
	v := (b.data[b.pos/8] >> uint(7-b.pos%8)) & 1
	b.pos++
	return int64(v)
}
func (b *bitReader) bits(n int) int64 {
	if n < 0 || n > 62 {
		b.fail("invalid BVGraph bit width %d", n)
		return 0
	}
	var v int64
	for i := 0; i < n && b.err == nil; i++ {
		v = v<<1 | b.bit()
	}
	return v
}
func (b *bitReader) unary() int {
	n := 0
	for b.err == nil && b.bit() == 0 {
		n++
	}
	return n
}
func (b *bitReader) gamma() int64 {
	n := b.unary()
	if n > 62 {
		b.fail("BVGraph gamma overflow")
		return 0
	}
	return (int64(1)<<n | b.bits(n)) - 1
}
func (b *bitReader) zeta(k int) int64 {
	h := b.unary()
	if h*k+k-1 > 62 {
		b.fail("BVGraph zeta overflow")
		return 0
	}
	left := int64(1) << (h * k)
	m := b.bits(h*k + k - 1)
	if m < left {
		return m + left - 1
	}
	return m*2 + b.bit() - 1
}
func naturalToSigned(n int64) int64 {
	if n&1 == 0 {
		return n / 2
	}
	return -(n + 1) / 2
}

type BVGraph struct {
	NodeSpan   int32
	ArcCount   int64
	successors map[int32][]int32
}

// Successors returns a detached, sorted list. Isolated node IDs within NodeSpan
// legitimately have no node record in GraphStore and no outgoing arcs.
func (g *BVGraph) Successors(id int32) []int32 { return append([]int32(nil), g.successors[id]...) }

// LoadBVGraph decodes BVGraph version 0 with its declared compression flags.
// Adjacency is read sequentially; persisted offsets are not required.
func LoadBVGraph(base string) (*BVGraph, error) {
	properties, err := os.Open(base + ".properties")
	if err != nil {
		return nil, err
	}
	defer properties.Close()
	p := map[string]string{}
	scanner := bufio.NewScanner(properties)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "!") {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf("unsupported BVGraph property syntax %q", line)
		}
		p[strings.TrimSpace(k)] = strings.TrimSpace(v)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if p["graphclass"] != "it.unimi.dsi.webgraph.BVGraph" || p["version"] != "0" {
		return nil, fmt.Errorf("unsupported graph class/version %q/%q", p["graphclass"], p["version"])
	}
	coding, err := parseCompressionFlags(p["compressionflags"])
	if err != nil {
		return nil, err
	}
	readNumber := func(key string) (int64, error) {
		n, err := strconv.ParseInt(p[key], 10, 64)
		if err != nil || n < 0 {
			return 0, fmt.Errorf("invalid BVGraph %s %q", key, p[key])
		}
		return n, nil
	}
	span, err := readNumber("nodes")
	if err != nil {
		return nil, err
	}
	arcs, err := readNumber("arcs")
	if err != nil {
		return nil, err
	}
	window, err := readNumber("windowsize")
	if err != nil {
		return nil, err
	}
	minInterval, err := readNumber("minintervallength")
	if err != nil {
		return nil, err
	}
	if _, exists := p["zetak"]; !exists {
		p["zetak"] = "3"
	}
	zeta, err := readNumber("zetak")
	if err != nil {
		return nil, err
	}
	if span > 1<<31-1 || window > 1024 || (coding.residual == codeZeta && (zeta < 1 || zeta > 31)) || (coding.residual == codeGolomb && zeta > 1<<31-1) {
		return nil, fmt.Errorf("unsupported BVGraph bounds: nodes=%d window=%d zeta=%d", span, window, zeta)
	}
	data, err := os.ReadFile(base + ".graph")
	if err != nil {
		return nil, err
	}
	if span > int64(len(data))*8 {
		return nil, fmt.Errorf("BVGraph node count exceeds bitstream")
	}
	b := &bitReader{data: data}
	g := &BVGraph{NodeSpan: int32(span), ArcCount: arcs, successors: map[int32][]int32{}}
	ring := make([][]int32, int(window)+1)
	var decoded int64
	for id := int64(0); id < span && b.err == nil; id++ {
		degree := b.natural(coding.degree, int(zeta))
		slot := int(id % int64(len(ring)))
		if degree == 0 {
			ring[slot] = nil
			continue
		}
		if degree < 0 || degree > span || degree > arcs-decoded {
			b.fail("invalid outdegree %d for node %d", degree, id)
			break
		}
		targets := make([]int32, 0, int(degree))
		ref := 0
		if window > 0 {
			ref = int(b.natural(coding.reference, int(zeta)))
		}
		if int64(ref) > window || int64(ref) > id {
			b.fail("invalid reference %d for node %d", ref, id)
			break
		}
		if ref > 0 {
			source := ring[(int(id)-ref)%len(ring)]
			blocks := b.natural(coding.blockCount, int(zeta))
			if blocks > int64(len(source))+1 {
				b.fail("invalid copy block count %d", blocks)
				break
			}
			position := 0
			for i := int64(0); i < blocks && b.err == nil; i++ {
				length := b.natural(coding.block, int(zeta))
				if i != 0 {
					length++
				}
				if length < 0 || length > int64(len(source)-position) {
					b.fail("invalid copy block length %d", length)
					break
				}
				end := position + int(length)
				if i%2 == 0 {
					targets = append(targets, source[position:end]...)
				}
				position = end
			}
			if blocks%2 == 0 {
				targets = append(targets, source[position:]...)
			}
		}
		extra := degree - int64(len(targets))
		if extra < 0 {
			b.fail("copied more arcs than degree")
			break
		}
		if extra > 0 && minInterval != 0 {
			intervals := b.gamma()
			if intervals > extra {
				b.fail("invalid interval count %d", intervals)
				break
			}
			var previous int64
			for i := int64(0); i < intervals && b.err == nil; i++ {
				var left int64
				if i == 0 {
					left = id + naturalToSigned(b.gamma())
				} else {
					left = previous + 1 + b.gamma()
				}
				length := b.gamma() + minInterval
				if left < 0 || length < 0 || length > extra || left+length > span {
					b.fail("invalid interval [%d,%d) at node %d", left, left+length, id)
					break
				}
				for v := left; v < left+length; v++ {
					targets = append(targets, int32(v))
				}
				previous = left + length
				extra -= length
			}
		}
		var previous int64
		for i := int64(0); i < extra && b.err == nil; i++ {
			var target int64
			if i == 0 {
				target = id + naturalToSigned(b.natural(coding.residual, int(zeta)))
			} else {
				target = previous + 1 + b.natural(coding.residual, int(zeta))
			}
			if target < 0 || target >= span {
				b.fail("invalid residual target %d for node %d", target, id)
				break
			}
			targets = append(targets, int32(target))
			previous = target
		}
		if int64(len(targets)) != degree {
			b.fail("decoded %d targets, expected %d at node %d", len(targets), degree, id)
			break
		}
		sort.Slice(targets, func(i, j int) bool { return targets[i] < targets[j] })
		for i := 1; i < len(targets); i++ {
			if targets[i] == targets[i-1] {
				b.fail("duplicate BVGraph target %d at node %d", targets[i], id)
			}
		}
		g.successors[int32(id)] = targets
		ring[slot] = targets
		decoded += degree
	}
	if b.err != nil {
		return nil, b.err
	}
	if decoded != arcs {
		return nil, fmt.Errorf("BVGraph decoded arc count %d, properties declare %d", decoded, arcs)
	}
	if int64(len(data))*8-b.pos > 7 {
		return nil, fmt.Errorf("trailing BVGraph data at bit %d", b.pos)
	}
	for b.pos < int64(len(data))*8 {
		if b.bit() != 0 {
			return nil, fmt.Errorf("nonzero BVGraph padding")
		}
	}
	return g, nil
}
