package store

// The UTF-8 table sizing behavior follows dsiutils FrontCodedStringList.
// Copyright (C) 2002-2023 Sebastiano Vigna.
// Adapted under the Apache License, Version 2.0:
// https://www.apache.org/licenses/LICENSE-2.0

import (
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// Keep Java's isolated code units as WTF-8, matching the query string model.
// Valid surrogate pairs become their ordinary four-byte UTF-8 encoding.
func stringFromUTF16(units []uint16) string {
	var out strings.Builder
	for i := 0; i < len(units); i++ {
		u := units[i]
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			out.WriteRune(utf16.DecodeRune(rune(u), rune(units[i+1])))
			i++
		} else if u >= 0xd800 && u <= 0xdfff {
			out.WriteByte(0xe0 | byte(u>>12))
			out.WriteByte(0x80 | byte(u>>6&63))
			out.WriteByte(0x80 | byte(u&63))
		} else {
			out.WriteRune(rune(u))
		}
	}
	return out.String()
}

// FrontCodedStringList.get() uses its own UTF-8 char-array sizing pass. For
// supplementary characters that pass advances five bytes, which can under-
// allocate (for example, "😀A"). Preserve this observable target behavior;
// the eager native table reports the error while opening the table.
func decodeFrontCodedUTF8(data []byte) (string, error) {
	count := 0
	for i := 0; i < len(data); i++ {
		switch head := data[i] >> 4; {
		case head < 8:
			count++
		case head < 14:
			count++
			i++
		case head < 15:
			count++
			i += 2
		default:
			count += 2
			i += 4
		}
	}
	units := make([]uint16, 0, count)
	for i := 0; i < len(data); {
		r, width := utf8.DecodeRune(data[i:])
		if r == utf8.RuneError && width == 1 {
			return "", fmt.Errorf("malformed internal UTF-8 encoding")
		}
		if r > 0xffff {
			a, b := utf16.EncodeRune(r)
			units = append(units, uint16(a), uint16(b))
		} else {
			units = append(units, uint16(r))
		}
		if len(units) > count {
			return "", fmt.Errorf("UTF-8 char array index %d out of bounds for length %d", count, count)
		}
		i += width
	}
	// An oversized array is observable too: Java wraps the whole char array.
	units = units[:count]
	return stringFromUTF16(units), nil
}
