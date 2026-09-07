package store

import (
	"context"
	"encoding/binary"
)

// ProjectionPropertyStringID is rawStringPropertyIndex for the non-CallSite
// concrete types admitted by main's direct-string compiler. It deliberately
// does not decode a node or validate its tag or unrelated fields.
func (s *Store) ProjectionPropertyStringID(ctx context.Context, id int32, kind, property string) (int32, bool, error) {
	field := int32(-1)
	switch kind {
	case "EnumConstant":
		if property == "name" {
			field = 4
		}
	case "LocalVariable":
		if property == "name" {
			field = 0
		}
	case "FieldNode":
		if property == "class" {
			field = 0
		}
		if property == "name" {
			field = 4
		}
	}
	if field < 0 {
		return 0, false, nil
	}
	if err := s.prepareProjectionOffsets(ctx); err != nil {
		return 0, false, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return 0, false, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return 0, false, err
	}
	offset, err := s.projectionOffsetLocked(id)
	if err != nil {
		return 0, false, err
	}
	if offset < 0 {
		return 0, false, nil
	}
	at := int32(offset) + 5 + field
	if at < 0 || int64(at) > int64(len(s.mappedData))-4 {
		return 0, false, &ProjectionReadError{}
	}
	return int32(binary.BigEndian.Uint32(s.mappedData[int(at) : int(at)+4])), true, nil
}
