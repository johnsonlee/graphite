#include "CGraphiteIndex.h"
#include <dlfcn.h>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

// Stable entry points from the compiler's indexstore/indexstore.h. Xcode ships
// libIndexStore without public headers in some toolchains. Load the exact library
// selected by the frontend, just as IndexStoreDB does.
using Object = void *;
using Visitor = bool (*)(void *, Object);
using UnitVisitor = bool (*)(void *, GIndexString);
#define INDEX_API(F) \
    F(Object, store_create, (const char *, Object *)) \
    F(void, store_dispose, (Object)) \
    F(bool, store_units_apply_f, (Object, unsigned, void *, UnitVisitor)) \
    F(Object, unit_reader_create, (Object, const char *, Object *)) \
    F(void, unit_reader_dispose, (Object)) \
    F(GIndexString, unit_reader_get_working_dir, (Object)) \
    F(void, unit_reader_get_modification_time, (Object, int64_t *, int64_t *)) \
    F(GIndexString, unit_reader_get_module_name, (Object)) \
    F(bool, unit_reader_dependencies_apply_f, (Object, void *, Visitor)) \
    F(unsigned, unit_dependency_get_kind, (Object)) \
    F(bool, unit_dependency_is_system, (Object)) \
    F(GIndexString, unit_dependency_get_filepath, (Object)) \
    F(GIndexString, unit_dependency_get_modulename, (Object)) \
    F(GIndexString, unit_dependency_get_name, (Object)) \
    F(Object, record_reader_create, (Object, const char *, Object *)) \
    F(void, record_reader_dispose, (Object)) \
    F(bool, record_reader_symbols_apply_f, (Object, bool, void *, Visitor)) \
    F(bool, record_reader_occurrences_apply_f, (Object, void *, Visitor)) \
    F(Object, occurrence_get_symbol, (Object)) \
    F(uint64_t, occurrence_get_roles, (Object)) \
    F(void, occurrence_get_line_col, (Object, unsigned *, unsigned *)) \
    F(bool, occurrence_relations_apply_f, (Object, void *, Visitor)) \
    F(Object, symbol_relation_get_symbol, (Object)) \
    F(uint64_t, symbol_relation_get_roles, (Object)) \
    F(GIndexString, symbol_get_usr, (Object)) \
    F(GIndexString, symbol_get_name, (Object)) \
    F(unsigned, symbol_get_kind, (Object)) \
    F(unsigned, symbol_get_language, (Object)) \
    F(uint64_t, symbol_get_properties, (Object)) \
    F(const char *, error_get_description, (Object)) \
    F(void, error_dispose, (Object))

struct GIndex {
    void *library = nullptr;
    Object store = nullptr;
#define DECLARE(type, name, args) type (*name) args = nullptr;
    INDEX_API(DECLARE)
#undef DECLARE

    ~GIndex() {
        if (store) store_dispose(store);
        if (library) dlclose(library);
    }

    GIndexSymbol symbol(Object s) const {
        return {symbol_get_usr(s), symbol_get_name(s), symbol_get_kind(s),
                symbol_get_language(s), symbol_get_properties(s)};
    }

    void report(Object issue, const char *operation, char **error) const {
        std::string message(operation);
        if (issue) {
            message += ": ";
            message += error_get_description(issue);
            error_dispose(issue);
        }
        *error = strdup(message.c_str());
    }
};

GIndex *gindex_open(const char *library, const char *store, char **error) {
    std::unique_ptr<GIndex> index(new GIndex());
    index->library = dlopen(library, RTLD_LAZY | RTLD_LOCAL);
    if (!index->library) { *error = strdup(dlerror()); return nullptr; }
#define LOAD(type, name, args) \
    index->name = reinterpret_cast<decltype(index->name)>(dlsym(index->library, "indexstore_" #name)); \
    if (!index->name) { *error = strdup("libIndexStore lacks indexstore_" #name); return nullptr; }
    INDEX_API(LOAD)
#undef LOAD
    Object issue = nullptr;
    index->store = index->store_create(store, &issue);
    if (!index->store) { index->report(issue, store, error); return nullptr; }
    return index.release();
}

void gindex_close(GIndex *index) { delete index; }

// These C callbacks immediately invoke their capturing C++ visitor, so contexts
// only live on the stack. No compiler-owned string escapes a reader's lifetime.
template<typename T, typename Value> static bool visit(void *context, Value value) {
    return (*static_cast<T *>(context))(value);
}

bool gindex_swift_records(GIndex *index, void *context, GIndexRecordReceiver receive, char **error) {
    auto unit = [&](GIndexString name) {
        std::string unitName(name.data, name.length);
        Object issue = nullptr;
        Object reader = index->unit_reader_create(index->store, unitName.c_str(), &issue);
        if (!reader) { index->report(issue, unitName.c_str(), error); return false; }
        int64_t seconds, nanos;
        index->unit_reader_get_modification_time(reader, &seconds, &nanos);
        auto dependency = [&](Object dep) {
            // INDEXSTORE_UNIT_DEPENDENCY_RECORD = 2. Unit and file dependencies
            // do not carry the compiler's occurrence records.
            if (index->unit_dependency_get_kind(dep) != 2) return true;
            auto path = index->unit_dependency_get_filepath(dep);
            if (path.length < 6 || memcmp(path.data + path.length - 6, ".swift", 6)) return true;
            auto module = index->unit_dependency_get_modulename(dep);
            if (!module.length) module = index->unit_reader_get_module_name(reader);
            receive(context, path, index->unit_reader_get_working_dir(reader),
                    module, index->unit_dependency_get_name(dep), index->unit_dependency_is_system(dep), seconds, nanos);
            return true;
        };
        bool ok = index->unit_reader_dependencies_apply_f(reader, &dependency, visit<decltype(dependency), Object>);
        index->unit_reader_dispose(reader);
        return ok;
    };
    return index->store_units_apply_f(index->store, 0, &unit, visit<decltype(unit), GIndexString>);
}

bool gindex_occurrences(GIndex *index, const char *record, void *context,
                        GIndexOccurrenceReceiver receive, char **error) {
    Object issue = nullptr;
    Object reader = index->record_reader_create(index->store, record, &issue);
    if (!reader) { index->report(issue, record, error); return false; }
    bool sawOccurrence = false;
    auto occurrence = [&](Object occ) {
        sawOccurrence = true;
        std::vector<GIndexRelation> relations;
        auto relation = [&](Object rel) {
            relations.push_back({index->symbol(index->symbol_relation_get_symbol(rel)),
                                 index->symbol_relation_get_roles(rel)});
            return true;
        };
        if (!index->occurrence_relations_apply_f(occ, &relation, visit<decltype(relation), Object>)) return false;
        unsigned line, column;
        index->occurrence_get_line_col(occ, &line, &column);
        receive(context, index->symbol(index->occurrence_get_symbol(occ)), index->occurrence_get_roles(occ),
                line, column, relations.data(), relations.size());
        return true;
    };
    bool ok = index->record_reader_occurrences_apply_f(reader, &occurrence, visit<decltype(occurrence), Object>);
    if (!ok && !sawOccurrence) {
        // IndexRecordWriter::endRecord omits the occurrence block when there
        // are no symbols. Traversing that absent block also returns false.
        // https://github.com/swiftlang/llvm-project/blob/swift-6.1.3-RELEASE/clang/lib/Index/IndexRecordWriter.cpp#L299-L301
        // The symbol iterator completes only if it never calls this receiver.
        auto stopAtFirstSymbol = [](void *, Object) { return false; };
        ok = index->record_reader_symbols_apply_f(reader, true, nullptr, stopAtFirstSymbol);
    }
    index->record_reader_dispose(reader);
    return ok;
}
