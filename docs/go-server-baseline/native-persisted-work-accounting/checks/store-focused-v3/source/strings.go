package store

// Front-coding length layouts follow fastutil Char/ByteArrayFrontCodedList.
// Copyright (C) 2002-2024 Sebastiano Vigna.
// Adapted under the Apache License, Version 2.0:
// https://www.apache.org/licenses/LICENSE-2.0

import (
	"bytes"
	"fmt"
	"os"
)

// LoadStrings reads the UTF-16- or UTF-8-backed FrontCodedStringList accepted by
// GraphStore. Isolated UTF-16 surrogate units are retained internally as WTF-8.
// Like BinIO.loadObject, it reads one root object and ignores trailing bytes.
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
	root, ok := value.(*javaObject)
	if !ok || root.class != "it.unimi.dsi.util.FrontCodedStringList" {
		return nil, fmt.Errorf("expected dsiutils FrontCodedStringList")
	}
	utf8, ok := root.fields["utf8"].(bool)
	if !ok {
		return nil, fmt.Errorf("missing FrontCodedStringList utf8 field")
	}
	field, class := "charFrontCodedList", "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList"
	if utf8 {
		field, class = "byteFrontCodedList", "it.unimi.dsi.fastutil.bytes.ByteArrayFrontCodedList"
	}
	list, ok := root.fields[field].(*javaObject)
	if !ok || list.class != class {
		return nil, fmt.Errorf("missing %s", class)
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
		return nil, fmt.Errorf("missing front-coded array segments")
	}
	if utf8 {
		data, err := joinStringSegments[byte](segments)
		if err != nil {
			return nil, err
		}
		return decodeStringRecords(data, int(count), int(ratio), byteStringLength, decodeFrontCodedUTF8)
	}
	data, err := joinStringSegments[uint16](segments)
	if err != nil {
		return nil, err
	}
	return decodeStringRecords(data, int(count), int(ratio), charStringLength, func(units []uint16) (string, error) { return stringFromUTF16(units), nil })
}
func joinStringSegments[T byte | uint16](segments []any) ([]T, error) {
	var result []T
	for _, segment := range segments {
		v, ok := segment.([]T)
		if !ok {
			return nil, fmt.Errorf("invalid front-coded array segment")
		}
		result = append(result, v...)
	}
	return result, nil
}
func decodeStringRecords[T byte | uint16](data []T, count, ratio int, readLength func([]T, *int) (int, error), decode func([]T) (string, error)) ([]string, error) {
	if count > len(data) {
		return nil, fmt.Errorf("string count exceeds compressed data")
	}
	result := make([]string, count)
	pos := 0
	var previous []T
	for i := range result {
		n, err := readLength(data, &pos)
		if err != nil {
			return nil, err
		}
		common := 0
		if i%ratio != 0 {
			common, err = readLength(data, &pos)
			if err != nil {
				return nil, err
			}
		}
		if common > len(previous) || n > len(data)-pos {
			return nil, fmt.Errorf("invalid front-coded string %d (prefix %d, suffix %d)", i, common, n)
		}
		current := make([]T, common+n)
		copy(current, previous[:common])
		copy(current[common:], data[pos:pos+n])
		pos += n
		result[i], err = decode(current)
		if err != nil {
			return nil, fmt.Errorf("front-coded string %d: %w", i, err)
		}
		previous = current
	}
	if pos != len(data) {
		return nil, fmt.Errorf("trailing front-coded characters")
	}
	return result, nil
}
func charStringLength(data []uint16, pos *int) (int, error) {
	if *pos >= len(data) {
		return 0, fmt.Errorf("truncated front-coded length")
	}
	v := data[*pos]
	*pos++
	if v < 0x8000 {
		return int(v), nil
	}
	if *pos >= len(data) {
		return 0, fmt.Errorf("truncated long front-coded length")
	}
	n := int(v&0x7fff)<<16 | int(data[*pos])
	*pos++
	return n, nil
}

// ByteArrayFrontCodedList uses complemented seven-bit continuation groups,
// most significant group first, rather than the usual unsigned varint form.
func byteStringLength(data []byte, pos *int) (int, error) {
	n := 0
	for group := 0; group < 5; group++ {
		if *pos >= len(data) {
			return 0, fmt.Errorf("truncated byte front-coded length")
		}
		v := data[*pos]
		*pos++
		if v < 128 {
			n = n<<7 | int(v)
			if n > 1<<31-1 {
				return 0, fmt.Errorf("byte front-coded length overflow")
			}
			return n, nil
		}
		n = n<<7 | int(^v)
	}
	return 0, fmt.Errorf("invalid byte front-coded length")
}
