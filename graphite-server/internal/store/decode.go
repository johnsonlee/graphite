// Package store reads Graphite's persisted GraphStore files without a JVM.
package store

import (
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"strings"
)

type MethodDescriptor struct {
	DeclaringClass string
	Name           string
	ParameterTypes []string
	ReturnType     string
}

func (m MethodDescriptor) Signature() string {
	return m.DeclaringClass + "." + m.Name + "(" + strings.Join(m.ParameterTypes, ",") + ")"
}

type EnumReference struct{ EnumClass, EnumName string }
type Comparison struct {
	Operator        int32
	ComparandNodeID int32
}
type BranchScope struct {
	ConditionNodeID                       int32
	Method                                MethodDescriptor
	Comparison                            Comparison
	TrueBranchNodeIDs, FalseBranchNodeIDs []int32
}
type Node struct {
	ID                         int32
	Kind                       string
	Value                      any
	Name, Type, DeclaringClass string
	Method, Caller, Callee     MethodDescriptor
	IsStatic                   bool
	Index                      int32
	ActualType                 *string
	LineNumber, Receiver       *int32
	Arguments                  []int32
	EnumType, EnumName         string
	EnumArguments              []any
	ClassName, MemberName      string
	Values                     map[string]any
	ValueOrder                 []string
	Path, Source, Format, Key  string
	Profile                    *string
}
type Metadata struct {
	MemberAnnotationOrder         map[string][]string
	MethodList                    []MethodDescriptor
	Methods                       map[string]MethodDescriptor
	Supertypes, Subtypes          map[string][]string
	EnumValues                    map[string][]any
	ClassOrigins                  map[string]string
	ArtifactDependencies          map[string]map[string]int32
	ArtifactDependencyOrder       []string
	ArtifactDependencyTargetOrder map[string][]string
	MemberAnnotations             map[string]map[string]map[string]any
	BranchScopes                  []BranchScope
}

var nodeKinds = [...]string{"IntConstant", "StringConstant", "LongConstant", "FloatConstant", "DoubleConstant", "BooleanConstant", "NullConstant", "EnumConstant", "LocalVariable", "FieldNode", "ParameterNode", "ReturnNode", "CallSiteNode", "AnnotationNode", "ResourceValueNode", "ResourceFileNode"}

type decoder struct {
	r         io.Reader
	strings   []string
	version   int
	err       error
	remaining int64
}

func newDecoder(r io.Reader, size int64, table []string) *decoder {
	return &decoder{r: r, remaining: size, strings: table}
}
func (d *decoder) fail(f string, a ...any) {
	if d.err == nil {
		d.err = fmt.Errorf(f, a...)
	}
}
func (d *decoder) bytes(n int) []byte {
	if d.err != nil {
		return nil
	}
	if n < 0 || int64(n) > d.remaining {
		d.fail("truncated data: need %d bytes, have %d", n, d.remaining)
		return nil
	}
	b := make([]byte, n)
	_, d.err = io.ReadFull(d.r, b)
	d.remaining -= int64(n)
	return b
}
func (d *decoder) u8() byte {
	b := d.bytes(1)
	if b == nil {
		return 0
	}
	return b[0]
}
func (d *decoder) u16() uint16 {
	b := d.bytes(2)
	if b == nil {
		return 0
	}
	return binary.BigEndian.Uint16(b)
}
func (d *decoder) i32() int32 {
	b := d.bytes(4)
	if b == nil {
		return 0
	}
	return int32(binary.BigEndian.Uint32(b))
}
func (d *decoder) i64() int64 {
	b := d.bytes(8)
	if b == nil {
		return 0
	}
	return int64(binary.BigEndian.Uint64(b))
}
func (d *decoder) count() int {
	n := d.i32()
	if n < 0 || int64(n) > d.remaining {
		d.fail("invalid collection count %d (%d bytes remain)", n, d.remaining)
		return 0
	}
	return int(n)
}
func (d *decoder) str() string {
	i := d.i32()
	if d.err != nil {
		return ""
	}
	if i < 0 || int(i) >= len(d.strings) {
		d.fail("string index %d outside table of %d", i, len(d.strings))
		return ""
	}
	return d.strings[i]
}
func (d *decoder) optionalString() *string {
	if d.u8() == 0 {
		return nil
	}
	s := d.str()
	return &s
}
func (d *decoder) ids() []int32 {
	n := d.count()
	v := make([]int32, n)
	for i := range v {
		v[i] = d.i32()
	}
	return v
}
func (d *decoder) header(magic int32) {
	h := d.i32()
	if h&-256 != magic {
		d.fail("invalid GraphStore magic %#x, expected %#x", h&-256, magic)
	}
	d.version = int(h & 255)
	if d.version < 1 || d.version > 3 {
		d.fail("unsupported GraphStore version %d", d.version)
	}
}
func (d *decoder) method() MethodDescriptor {
	m := MethodDescriptor{DeclaringClass: d.str(), Name: d.str()}
	n := d.count()
	m.ParameterTypes = make([]string, n)
	for i := range m.ParameterTypes {
		m.ParameterTypes[i] = d.str()
	}
	m.ReturnType = d.str()
	return m
}
func (d *decoder) value(depth int) any {
	if d.err != nil {
		return nil
	}
	if depth > 256 {
		d.fail("value nesting exceeds 256")
		return nil
	}
	switch d.u8() {
	case 0:
		return d.i32()
	case 1:
		return d.i64()
	case 2:
		return d.str()
	case 3:
		return math.Float32frombits(uint32(d.i32()))
	case 4:
		return math.Float64frombits(uint64(d.i64()))
	case 5:
		return d.u8() != 0
	case 6:
		return nil
	case 7:
		return EnumReference{d.str(), d.str()}
	case 8:
		if d.version < 2 {
			d.fail("list value unsupported in GraphStore version %d", d.version)
			return nil
		}
		n := d.count()
		v := make([]any, n)
		for i := range v {
			v[i] = d.value(depth + 1)
		}
		return v
	default:
		return d.str() // NodeSerializer's forward-compatible scalar fallback.
	}
}
func (d *decoder) annotationValue() any {
	if d.version != 1 {
		return d.value(0)
	}
	s := d.str()
	if s == "" {
		return nil
	}
	return s
}
func (d *decoder) annotation() map[string]any {
	m, _ := d.orderedAnnotation()
	return m
}
func (d *decoder) orderedAnnotation() (map[string]any, []string) {
	n := d.count()
	m := make(map[string]any, n)
	order := make([]string, 0, n)
	for i := 0; i < n; i++ {
		k := d.str()
		if _, exists := m[k]; !exists {
			order = append(order, k)
		}
		m[k] = d.annotationValue()
	}
	return m, order
}
func (d *decoder) comparison() Comparison {
	c := Comparison{d.i32(), d.i32()}
	if c.Operator < 0 || c.Operator > 5 {
		d.fail("invalid comparison operator %d", c.Operator)
	}
	return c
}
func (d *decoder) node() Node {
	n := Node{ID: d.i32()}
	tag := d.u8()
	if int(tag) >= len(nodeKinds) {
		if d.err == nil {
			d.err = &UnknownNodeTagError{Tag: tag}
		}
		return n
	}
	n.Kind = nodeKinds[tag]
	switch tag {
	case 0:
		n.Value = d.i32()
	case 1:
		n.Value = d.str()
	case 2:
		n.Value = d.i64()
	case 3:
		n.Value = math.Float32frombits(uint32(d.i32()))
	case 4:
		n.Value = math.Float64frombits(uint64(d.i64()))
	case 5:
		n.Value = d.u8() != 0
	case 6:
	case 7:
		n.EnumType = d.str()
		n.EnumName = d.str()
		count := d.count()
		n.EnumArguments = make([]any, count)
		for i := range n.EnumArguments {
			n.EnumArguments[i] = d.value(0)
		}
	case 8:
		n.Name = d.str()
		n.Type = d.str()
		n.Method = d.method()
	case 9:
		n.DeclaringClass = d.str()
		n.Name = d.str()
		n.Type = d.str()
		n.IsStatic = d.u8() != 0
	case 10:
		n.Index = d.i32()
		n.Type = d.str()
		n.Method = d.method()
	case 11:
		n.Method = d.method()
		n.ActualType = d.optionalString()
	case 12:
		n.Caller = d.method()
		n.Callee = d.method()
		line := d.i32()
		if line != -1 {
			n.LineNumber = &line
		}
		receiver := d.i32()
		if receiver != -1 {
			n.Receiver = &receiver
		}
		n.Arguments = d.ids()
	case 13:
		n.Name = d.str()
		n.ClassName = d.str()
		n.MemberName = d.str()
		n.Values, n.ValueOrder = d.orderedAnnotation()
	case 14:
		n.Path = d.str()
		n.Key = d.str()
		n.Value = d.value(0)
		n.Format = d.str()
		n.Profile = d.optionalString()
	case 15:
		n.Path = d.str()
		n.Source = d.str()
		n.Format = d.str()
		n.Profile = d.optionalString()
	}
	return n
}
func (d *decoder) hierarchy() map[string][]string {
	n := d.count()
	m := make(map[string][]string, n)
	for i := 0; i < n; i++ {
		k := d.str()
		count := d.count()
		v := make([]string, 0, count)
		seen := make(map[string]bool, count)
		for j := 0; j < count; j++ {
			s := d.str()
			if !seen[s] {
				v = append(v, s)
				seen[s] = true
			}
		}
		m[k] = v
	}
	return m
}
func (d *decoder) metadata() Metadata {
	d.header(0x47524d00)
	m := Metadata{MemberAnnotationOrder: map[string][]string{}, Methods: map[string]MethodDescriptor{}, EnumValues: map[string][]any{}, ClassOrigins: map[string]string{}, ArtifactDependencies: map[string]map[string]int32{}, MemberAnnotations: map[string]map[string]map[string]any{}}
	count := d.count()
	positions := make(map[string]int, count)
	m.MethodList = make([]MethodDescriptor, 0, count)
	for i := 0; i < count; i++ {
		method := d.method()
		signature := method.Signature()
		if position, exists := positions[signature]; exists {
			m.MethodList[position] = method
		} else {
			positions[signature] = len(m.MethodList)
			m.MethodList = append(m.MethodList, method)
		}
		m.Methods[signature] = method
	}
	m.Supertypes = d.hierarchy()
	m.Subtypes = d.hierarchy()
	count = d.count()
	for i := 0; i < count; i++ {
		key := d.str()
		n := d.count()
		v := make([]any, n)
		for j := range v {
			v[j] = d.value(0)
		}
		m.EnumValues[key] = v
	}
	if d.version >= 3 {
		m.ArtifactDependencyTargetOrder = make(map[string][]string)
		count = d.count()
		for i := 0; i < count; i++ {
			key := d.str()
			m.ClassOrigins[key] = d.str()
		}
		count = d.count()
		for i := 0; i < count; i++ {
			key := d.str()
			n := d.count()
			v := make(map[string]int32, n)
			order := make([]string, 0, n)
			for j := 0; j < n; j++ {
				k := d.str()
				if _, exists := v[k]; !exists {
					order = append(order, k)
				}
				v[k] = d.i32()
			}
			if _, exists := m.ArtifactDependencies[key]; !exists {
				m.ArtifactDependencyOrder = append(m.ArtifactDependencyOrder, key)
			}
			m.ArtifactDependencyTargetOrder[key] = order
			m.ArtifactDependencies[key] = v
		}
	}
	count = d.count()
	for i := 0; i < count; i++ {
		key := d.str()
		n := d.count()
		v := make(map[string]map[string]any, n)
		order := make([]string, 0, n)
		for j := 0; j < n; j++ {
			k := d.str()
			if _, exists := v[k]; !exists {
				order = append(order, k)
			}
			v[k] = d.annotation()
		}
		m.MemberAnnotationOrder[key] = order
		m.MemberAnnotations[key] = v
	}
	count = d.count()
	m.BranchScopes = make([]BranchScope, count)
	for i := range m.BranchScopes {
		m.BranchScopes[i] = BranchScope{ConditionNodeID: d.i32(), Method: d.method(), Comparison: d.comparison(), TrueBranchNodeIDs: d.ids(), FalseBranchNodeIDs: d.ids()}
	}
	return m
}
