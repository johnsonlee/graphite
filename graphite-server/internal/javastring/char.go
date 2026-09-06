package javastring

// IsLowerChar and IsUpperChar use Java 17 Character's UTF-16 char overloads.
// A supplementary code point's first surrogate is therefore never cased.
func IsLowerChar(c uint16) bool { return charInRanges(c, charLower) }
func IsUpperChar(c uint16) bool { return charInRanges(c, charUpper) }
func charInRanges(c uint16, ranges [][2]uint16) bool {
	lo, hi := 0, len(ranges)
	for lo < hi {
		mid := (lo + hi) / 2
		if ranges[mid][1] < c {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	return lo < len(ranges) && ranges[lo][0] <= c
}

// TitleChar matches Kotlin Char.titlecase(), including expansions such as
// sharp s to Ss, rather than Java Character.toTitleCase's single char result.
func TitleChar(c uint16) string {
	if s, ok := charTitleSpecial[c]; ok {
		return s
	}
	lo, hi := 0, len(charTitleRanges)
	for lo < hi {
		mid := (lo + hi) / 2
		if charTitleRanges[mid][1] < int32(c) {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	if lo < len(charTitleRanges) && charTitleRanges[lo][0] <= int32(c) {
		return FromUTF16([]uint16{uint16(int32(c) + charTitleRanges[lo][2])})
	}
	return FromUTF16([]uint16{c})
}

// Blank and Trim use Kotlin's Char.isWhitespace (Java whitespace or space
// character), which includes U+001C and excludes U+0085 unlike Go TrimSpace.
func Blank(s string) bool {
	for _, c := range UTF16(s) {
		if !Whitespace(rune(c)) {
			return false
		}
	}
	return true
}
func Trim(s string) string {
	units := UTF16(s)
	start, end := 0, len(units)
	for start < end && Whitespace(rune(units[start])) {
		start++
	}
	for end > start && Whitespace(rune(units[end-1])) {
		end--
	}
	return FromUTF16(units[start:end])
}

// TitleFirst changes only the first UTF-16 char, and only when it is lowercase,
// as in Kotlin replaceFirstChar { if (it.isLowerCase()) it.titlecase() ... }.
func TitleFirst(s string) string {
	units := UTF16(s)
	if len(units) == 0 || !IsLowerChar(units[0]) {
		return s
	}
	return TitleChar(units[0]) + FromUTF16(units[1:])
}
func UpperFirst(s string) string {
	units := UTF16(s)
	if len(units) == 0 {
		return s
	}
	return FromUTF16(append(UTF16(Upper(FromUTF16(units[:1]))), units[1:]...))
}
