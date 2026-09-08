package store

import (
	"context"
	"encoding/binary"
	"fmt"
	"math/bits"
)

// AggregateProjectionRanges mirrors PostingRanges.aggregate. It deliberately
// reads only posting IDs and, for DISTINCT, the selected string property. The
// generic candidate certificate protects a different full-node consumer.
func (i *DistinctStringIndex) AggregateProjectionRanges(ctx context.Context, ranges [][]int32, distinctProperty *CallSiteStringProperty) (int64, []string, error) {
	if len(ranges) == 0 {
		return 0, nil, nil
	}
	s := i.owner
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return 0, nil, err
	}
	s.callSiteIndex.mu.RLock()
	capacity := 0
	if data := s.distinctProjection.offsets; data != nil {
		capacity = int(int32(binary.BigEndian.Uint32(data[4:8])))
	} else {
		for id := range s.locations {
			capacity = max(capacity, int(id)+1)
		}
	}
	s.callSiteIndex.mu.RUnlock()
	words := make([]uint64, (int64(capacity)+63)>>6)
	wordIndex := func(id int32) (int, error) {
		word := int(uint32(id) >> 6)
		if word >= len(words) {
			message := fmt.Sprintf("Index %d out of bounds for length %d", word, len(words))
			return 0, &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
		}
		return word, nil
	}
	for _, ids := range ranges {
		for at, id := range ids {
			if at&1023 == 0 {
				if err := ctx.Err(); err != nil {
					return 0, nil, err
				}
			}
			word, err := wordIndex(id)
			if err != nil {
				return 0, nil, err
			}
			words[word] |= uint64(1) << (uint32(id) & 63)
		}
	}
	var count int64
	for _, word := range words {
		count += int64(bits.OnesCount64(word))
	}
	if distinctProperty == nil {
		return count, nil, nil
	}
	// Main obtains the counted CSR's postingCount without visiting its rows.
	var postingCount int64
	if i.view != nil {
		postingCount = int64(i.view.info.CallSiteCount)
	} else {
		for _, ids := range i.entries[*distinctProperty] {
			postingCount += int64(len(ids))
		}
	}
	values := []string{}
	seen := map[string]bool{}
	add := func(sid int32) error {
		value, err := s.ProjectionString(ctx, sid)
		if err != nil {
			return err
		}
		if !seen[value] {
			seen[value] = true
			values = append(values, value)
		}
		return nil
	}
	if count*4 <= postingCount {
		for wordIndex, word := range words {
			for word != 0 {
				if err := ctx.Err(); err != nil {
					return 0, nil, err
				}
				id := int32(wordIndex*64 + bits.TrailingZeros64(word))
				sid, err := s.ProjectionStringID(ctx, id, *distinctProperty)
				if err != nil {
					return 0, nil, err
				}
				if err := add(sid); err != nil {
					return 0, nil, err
				}
				word &= word - 1
			}
		}
	} else {
		directory, err := i.Directory(ctx, *distinctProperty)
		if err != nil {
			return 0, nil, err
		}
		for _, entry := range directory {
			ids, err := i.Postings(ctx, *distinctProperty, entry.StringID)
			if err != nil {
				return 0, nil, err
			}
			for at, id := range ids {
				if at&1023 == 0 {
					if err := ctx.Err(); err != nil {
						return 0, nil, err
					}
				}
				word, err := wordIndex(id)
				if err != nil {
					return 0, nil, err
				}
				if words[word]&(uint64(1)<<(uint32(id)&63)) != 0 {
					if err := add(entry.StringID); err != nil {
						return 0, nil, err
					}
					break
				}
			}
		}
	}
	return count, values, nil
}
