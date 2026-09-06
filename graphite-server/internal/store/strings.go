package store

import (
	"bytes"
	"fmt"
	"os"
	"unicode/utf16"
)

// The reader accepts only the passive serialization shapes emitted by
// StringTable.build. No classes are instantiated and no Java code is executed.
type javaField struct {
	kind byte
	name string
}
type javaClass struct {
	name   string
	flags  byte
	fields []javaField
	parent *javaClass
}
type javaObject struct {
	class  string
	fields map[string]any
}
type javaReader struct {
	*decoder
	handles []any
}

func (j *javaReader) handle(v any) { j.handles = append(j.handles, v) }
func (j *javaReader) utf() string  { b := j.bytes(int(j.u16())); return string(b) } // Schema names are ASCII.
func (j *javaReader) class() *javaClass {
	v := j.content(0)
	if v == nil {
		return nil
	}
	c, ok := v.(*javaClass)
	if !ok {
		j.fail("expected serialized class descriptor")
	}
	return c
}
func (j *javaReader) content(depth int) any {
	if j.err != nil {
		return nil
	}
	if depth > 32 {
		j.fail("serialized string table nesting exceeds 32")
		return nil
	}
	switch token := j.u8(); token {
	case 0x70:
		return nil
	case 0x71:
		i := j.i32() - 0x7e0000
		if i < 0 || int(i) >= len(j.handles) {
			j.fail("invalid serialization reference %d", i)
			return nil
		}
		return j.handles[i]
	case 0x74:
		s := j.utf()
		j.handle(s)
		return s
	case 0x72:
		c := &javaClass{name: j.utf()}
		j.i64()
		j.handle(c)
		c.flags = j.u8()
		n := int(j.u16())
		c.fields = make([]javaField, n)
		for i := range c.fields {
			c.fields[i] = javaField{j.u8(), j.utf()}
			if c.fields[i].kind == 'L' || c.fields[i].kind == '[' {
				j.content(depth + 1)
			}
		}
		if j.u8() != 0x78 {
			j.fail("unsupported class descriptor annotations")
		}
		c.parent = j.class()
		return c
	case 0x73:
		c := j.class()
		if c == nil {
			j.fail("missing serialized object class")
			return nil
		}
		o := &javaObject{class: c.name, fields: map[string]any{}}
		j.handle(o)
		var readFields func(*javaClass)
		readFields = func(c *javaClass) {
			if c == nil || j.err != nil {
				return
			}
			readFields(c.parent)
			if c.flags != 2 {
				j.fail("unsupported serialization flags %d for %s", c.flags, c.name)
				return
			}
			for _, f := range c.fields {
				switch f.kind {
				case 'Z':
					o.fields[f.name] = j.u8() != 0
				case 'I':
					o.fields[f.name] = j.i32()
				case 'L', '[':
					o.fields[f.name] = j.content(depth + 1)
				default:
					j.fail("unsupported serialized field type %q", f.kind)
				}
			}
		}
		readFields(c)
		return o
	case 0x75:
		c := j.class()
		if c == nil {
			j.fail("missing serialized array class")
			return nil
		}
		slot := len(j.handles)
		j.handle(nil)
		n := j.count()
		switch c.name {
		case "[C":
			if int64(n)*2 > j.remaining {
				j.fail("truncated serialized char array")
				return nil
			}
			v := make([]uint16, n)
			j.handles[slot] = v
			for i := range v {
				v[i] = j.u16()
			}
			return v
		case "[[C":
			v := make([]any, n)
			j.handles[slot] = v
			for i := range v {
				v[i] = j.content(depth + 1)
			}
			return v
		default:
			j.fail("unsupported serialized array %s", c.name)
			return nil
		}
	default:
		j.fail("unsupported Java serialization token %#x", token)
		return nil
	}
}

// LoadStrings decodes the sorted char-front-coded table written by Kotlin's
// StringTable.build (dsiutils FrontCodedStringList, utf8=false).
func LoadStrings(path string) ([]string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	j := &javaReader{decoder: newDecoder(bytes.NewReader(b), int64(len(b)), nil)}
	if j.i32() != int32(-1393754107) {
		return nil, fmt.Errorf("invalid Java serialization stream header")
	}
	value := j.content(0)
	if j.err != nil {
		return nil, j.err
	}
	if j.remaining != 0 {
		return nil, fmt.Errorf("trailing serialized string table bytes")
	}
	root, ok := value.(*javaObject)
	if !ok || root.class != "it.unimi.dsi.util.FrontCodedStringList" {
		return nil, fmt.Errorf("expected dsiutils FrontCodedStringList")
	}
	if root.fields["utf8"] != false {
		return nil, fmt.Errorf("unsupported utf8 FrontCodedStringList; GraphStore writes char storage")
	}
	list, ok := root.fields["charFrontCodedList"].(*javaObject)
	if !ok || list.class != "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList" {
		return nil, fmt.Errorf("missing CharArrayFrontCodedList")
	}
	count, ok := list.fields["n"].(int32)
	if !ok || count < 0 {
		return nil, fmt.Errorf("invalid front-coded string count")
	}
	ratio, ok := list.fields["ratio"].(int32)
	if !ok || ratio < 1 {
		return nil, fmt.Errorf("invalid front-coding ratio")
	}
	segments, ok := list.fields["array"].([]any)
	if !ok {
		return nil, fmt.Errorf("missing char array segments")
	}
	var chars []uint16
	for _, segment := range segments {
		v, ok := segment.([]uint16)
		if !ok {
			return nil, fmt.Errorf("invalid char array segment")
		}
		chars = append(chars, v...)
	}
	if int64(count) > int64(len(chars)) {
		return nil, fmt.Errorf("string count exceeds compressed data")
	}
	pos := 0
	readLength := func() (int, error) {
		if pos >= len(chars) {
			return 0, fmt.Errorf("truncated front-coded length")
		}
		v := chars[pos]
		pos++
		if v < 0x8000 {
			return int(v), nil
		}
		if pos >= len(chars) {
			return 0, fmt.Errorf("truncated long front-coded length")
		}
		n := int(v&0x7fff)<<16 | int(chars[pos])
		pos++
		return n, nil
	}
	result := make([]string, int(count))
	var previous []uint16
	for i := range result {
		n, err := readLength()
		if err != nil {
			return nil, err
		}
		common := 0
		if i%int(ratio) != 0 {
			common, err = readLength()
			if err != nil {
				return nil, err
			}
		}
		if common > len(previous) || n > len(chars)-pos {
			return nil, fmt.Errorf("invalid front-coded string %d (prefix %d, suffix %d)", i, common, n)
		}
		current := make([]uint16, common+n)
		copy(current, previous[:common])
		copy(current[common:], chars[pos:pos+n])
		pos += n
		result[i] = string(utf16.Decode(current))
		previous = current
	}
	if pos != len(chars) {
		return nil, fmt.Errorf("trailing front-coded characters")
	}
	return result, nil
}
