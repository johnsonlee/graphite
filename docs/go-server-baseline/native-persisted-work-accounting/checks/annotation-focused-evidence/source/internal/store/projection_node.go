package store

import (
	"bytes"
	"context"
	"encoding/binary"
)

// ProjectionCandidateNode consumes a storage candidate with main's mapped
// NodeSerializer semantics. Unlike Node's validation API, negative collection
// counts are empty and large counts consume elements until the first read error.
// Raw projection never invokes this decoder; ordinary node candidates do.
func (s *Store) ProjectionCandidateNode(ctx context.Context, id int32) (Node, bool, error) {
	return s.projectionCandidateNode(ctx, id)
}

// A nil worker context selects main's decoder: identical field reads and
// lifetime lock, without an interruption check between serialized fields.
func (s *Store) projectionCandidateNode(ctx context.Context, id int32) (Node, bool, error) {
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return Node{}, false, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return Node{}, false, ErrStoreClosed
	}
	if ctx != nil {
		select {
		case <-ctx.Done():
			if err := ctx.Err(); err != nil {
				return Node{}, false, err
			}
		default:
		}
	}
	offset, err := s.projectionOffsetLocked(id)
	if err != nil {
		return Node{}, false, err
	}
	if offset == -1 {
		return Node{}, false, nil
	}
	position := int32(offset)
	var failure error
	read := func(width int32) []byte {
		if failure != nil {
			return nil
		}
		if ctx != nil {
			select {
			case <-ctx.Done():
				if failure = ctx.Err(); failure != nil {
					return nil
				}
			default:
			}
		}
		if position < 0 || int64(position) > int64(len(s.mappedData))-int64(width) {
			failure = &ProjectionReadError{Class: "EOFException"}
			return nil
		}
		value := s.mappedData[int(position):int(position+width)]
		position += width
		return value
	}
	i32 := func() int32 {
		data := read(4)
		if data == nil {
			return 0
		}
		return int32(binary.BigEndian.Uint32(data))
	}
	str := func() string {
		sid := i32()
		if failure != nil {
			return ""
		}
		if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
			failure = &StringTableReferenceError{Index: sid, Size: len(s.Strings)}
			return ""
		}
		return s.Strings[sid]
	}
	n := Node{ID: i32()}
	tagBytes := read(1)
	if failure != nil {
		return Node{}, false, failure
	}
	tag := tagBytes[0]
	if int(tag) >= len(nodeKinds) {
		return Node{}, false, &UnknownNodeTagError{Tag: tag}
	}
	if tag != 12 {
		// Runtime class acceptance depends on the storage branch, so return the
		// fully consumed node to the query boundary.
		d := newDecoder(bytes.NewReader(s.mappedData[int32(offset):]), int64(len(s.mappedData))-int64(int32(offset)), s.Strings)
		d.version = s.FormatVersion
		var node Node
		if tag == 7 {
			// NodeSerializer.readNode maps (0 until argCount), without the
			// strict reader's count-versus-remaining check or count-sized
			// allocation. Consume real values in order, stopping at the first
			// read error; a negative top-level Enum count denotes no arguments.
			// Nested List values still use their separate decoder contract.
			node = Node{ID: d.i32(), Kind: "EnumConstant"}
			d.u8()
			node.EnumType, node.EnumName = d.str(), d.str()
			count := d.i32()
			node.EnumArguments = []any{}
			for at := int32(0); at < count && d.err == nil; at++ {
				node.EnumArguments = append(node.EnumArguments, d.value(0))
			}
		} else if tag == 13 {
			// NodeSerializer repeats the signed top-level pair count, reading
			// each key before its value. Do not prevalidate the count against
			// the remaining bytes or reserve storage for unread entries.
			node = Node{ID: d.i32(), Kind: "AnnotationNode"}
			d.u8()
			node.Name, node.ClassName, node.MemberName = d.str(), d.str(), d.str()
			count := d.i32()
			node.Values = map[string]any{}
			node.ValueOrder = []string{}
			for at := int32(0); at < count && d.err == nil; at++ {
				key := d.str()
				value := d.annotationValue()
				if d.err != nil {
					break
				}
				if _, exists := node.Values[key]; !exists {
					node.ValueOrder = append(node.ValueOrder, key)
				}
				node.Values[key] = value
			}
		} else {
			node = d.node()
		}
		return node, d.err == nil, d.err
	}
	n.Kind = "CallSiteNode"
	method := func() MethodDescriptor {
		m := MethodDescriptor{DeclaringClass: str(), Name: str(), ParameterTypes: []string{}}
		count := i32()
		for at := int32(0); at < count && failure == nil; at++ {
			m.ParameterTypes = append(m.ParameterTypes, str())
		}
		m.ReturnType = str()
		return m
	}
	n.Caller = method()
	n.Callee = method()
	line := i32()
	if line != -1 {
		n.LineNumber = &line
	}
	receiver := i32()
	if receiver != -1 {
		n.Receiver = &receiver
	}
	count := i32()
	n.Arguments = []int32{}
	for at := int32(0); at < count && failure == nil; at++ {
		n.Arguments = append(n.Arguments, i32())
	}
	if failure != nil {
		return Node{}, false, failure
	}
	return n, true, nil
}
