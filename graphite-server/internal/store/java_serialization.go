package store

// The reader accepts only the passive serialization shapes emitted by
// supported FrontCodedStringList variants. No classes are instantiated and no Java code is executed.
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
		uid := j.i64()
		switch c.name {
		case "it.unimi.dsi.util.FrontCodedStringList", "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList", "it.unimi.dsi.fastutil.bytes.ByteArrayFrontCodedList":
			if uid != 1 {
				j.fail("%s; local class incompatible: stream classdesc serialVersionUID = %d, local class serialVersionUID = 1", c.name, uid)
			}
		}
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
		case "[B":
			v := j.bytes(n)
			j.handles[slot] = v
			return v
		case "[[C", "[[B":
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
