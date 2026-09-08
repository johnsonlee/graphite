package store

import (
	"os"
	"path/filepath"
)

// MainPreparedStringScan implements the late prepared-wide capability check
// for the four CallSite predicates supported by the ordinary query compiler.
// Generic Node queries also include Annotation, which has no prepared lookup.
// This observes metadata/file presence only; even a malformed regular sidecar
// qualifies. Main's corresponding capability getters do not poll interruption.
func (s *Store) MainPreparedStringScan(includeAnnotations bool) (bool, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return false, ErrStoreClosed
	}
	if s.Mode != "MAPPED" {
		return false, nil
	}
	if len(s.byKind["CallSiteNode"]) != 0 {
		index := s.distinctProjection.index
		serial := index != nil && index.projectionPlannerBytesLocked() <= 1024*1024
		if !serial {
			info, err := os.Stat(filepath.Join(s.dir, callSiteIndexFile))
			// Files.isRegularFile returns false when attributes cannot be read.
			if err != nil || !info.Mode().IsRegular() {
				return false, nil
			}
		}
	}
	if includeAnnotations && len(s.byKind["AnnotationNode"]) != 0 {
		return false, nil
	}
	return true, nil
}
