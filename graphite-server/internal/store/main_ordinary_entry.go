package store

import (
	"context"
	"os"
	"path/filepath"
)

// The ordinary main and load-only DISTINCT Split entries use this policy. Their metadata/cache accessors
// are lifetime checked, while the actual reader retains its worker-interruption
// checkpoints and request work callbacks. Other Store entry APIs keep their
// existing context checks.
type mainOrdinaryEntryKey struct{}

func isMainOrdinaryEntry(ctx context.Context) bool {
	marked, _ := ctx.Value(mainOrdinaryEntryKey{}).(bool)
	return marked
}

func mainEntryMetadataContext(ctx context.Context) context.Context {
	if isMainReaderEntry(ctx) {
		return context.WithoutCancel(ctx)
	}
	return ctx
}

func isMainReaderEntry(ctx context.Context) bool {
	distinct, _ := ctx.Value(mainDistinctEntryKey{}).(bool)
	return isMainOrdinaryEntry(ctx) || distinct
}

func (s *Store) PrepareMainOrdinaryStringIndex(ctx context.Context, options DistinctProjectionOptions) (*DistinctStringIndex, bool, error) {
	options.MainSource = true
	return s.PrepareDistinctStringIndex(context.WithValue(ctx, mainOrdinaryEntryKey{}, true), options)
}

func (s *Store) MainRetainedProjectionIndex() (*DistinctStringIndex, bool, error) {
	return s.retainedProjectionIndex(nil)
}

func (s *Store) MainPreparedProjectionFile() (bool, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return false, ErrStoreClosed
	}
	info, err := os.Stat(filepath.Join(s.dir, callSiteIndexFile))
	// Main Files.isRegularFile returns false on inaccessible/missing paths.
	return err == nil && info.Mode().IsRegular(), nil
}

func (i *DistinctStringIndex) MainProjectionCachedIDs(kind ProjectionCacheKind, key string) ([]int32, bool, error) {
	return i.projectionCachedIDs(nil, kind, key)
}

func (i *DistinctStringIndex) MainCacheProjectionIDs(kind ProjectionCacheKind, key string, ids []int32, bytes int64) error {
	return i.cacheProjectionIDs(nil, kind, key, ids, bytes)
}

func (s *Store) MainProjectionCandidateNode(id int32) (Node, bool, error) {
	return s.projectionCandidateNode(nil, id)
}

func (s *Store) MainProjectionNodeOrder(id int32) (int64, error) {
	return s.projectionNodeOrder(nil, id)
}
