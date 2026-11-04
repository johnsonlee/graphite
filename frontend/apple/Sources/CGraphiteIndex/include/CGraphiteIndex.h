#ifndef GRAPHITE_INDEX_H
#define GRAPHITE_INDEX_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct GIndex GIndex;
typedef struct { const char *data; size_t length; } GIndexString;
typedef struct {
    GIndexString usr, name;
    unsigned kind, language;
    uint64_t properties;
} GIndexSymbol;
typedef struct { GIndexSymbol symbol; uint64_t roles; } GIndexRelation;

typedef void (*GIndexRecordReceiver)(void *context, GIndexString file, GIndexString directory,
                                     GIndexString module, GIndexString record, bool system,
                                     int64_t seconds, int64_t nanoseconds);
typedef void (*GIndexOccurrenceReceiver)(void *context, GIndexSymbol symbol, uint64_t roles,
                                         unsigned line, unsigned column,
                                         const GIndexRelation *relations, size_t relation_count);

// Callback strings and arrays are borrowed until the callback returns. On failure
// the caller frees the diagnostic with free(). All traversal is synchronous.
GIndex *gindex_open(const char *library, const char *store, char **error);
void gindex_close(GIndex *index);
bool gindex_swift_records(GIndex *index, void *context, GIndexRecordReceiver receiver, char **error);
bool gindex_occurrences(GIndex *index, const char *record, void *context,
                        GIndexOccurrenceReceiver receiver, char **error);

#ifdef __cplusplus
}
#endif
#endif
