"""Independent libIndexStore C ABI reader for the SwiftPM shared-source correctness test."""
import ctypes as c
from collections import defaultdict
import hashlib
import json
from pathlib import Path

# ABI definitions from the compiler's indexstore/indexstore.h, independently of
# the candidate's CGraphiteIndex bridge, IndexStoreDB and its per-file callbacks.
Object = c.c_void_p


class Text(c.Structure):
    _fields_ = [("data", Object), ("length", c.c_size_t)]

    def value(self):
        return c.string_at(self.data, self.length).decode("utf-8")


UnitCallback = c.CFUNCTYPE(c.c_bool, Object, Text)
ObjectCallback = c.CFUNCTYPE(c.c_bool, Object, Object)


class IncompleteTraversal(RuntimeError):
    pass


class Store:
    def __init__(self, library, store):
        self.lib = c.CDLL(str(library))
        def bind(name, result, *parameters):
            entry = getattr(self.lib, "indexstore_" + name)
            entry.argtypes = parameters
            entry.restype = result
            setattr(self, name, entry)
        for name in ("store", "unit_reader", "record_reader"):
            args = (c.c_char_p,) if name == "store" else (Object, c.c_char_p)
            bind(name + "_create", Object, *args, c.POINTER(Object))
            bind(name + "_dispose", None, Object)
        bind("error_get_description", c.c_char_p, Object)
        bind("error_dispose", None, Object)
        bind("store_units_apply_f", c.c_bool, Object, c.c_uint, Object, UnitCallback)
        bind("record_reader_symbols_apply_f", c.c_bool, Object, c.c_bool, Object, ObjectCallback)
        for name in ("unit_reader_dependencies_apply_f", "record_reader_occurrences_apply_f", "occurrence_relations_apply_f"):
            bind(name, c.c_bool, Object, Object, ObjectCallback)
        for name in ("unit_reader_get_working_dir", "unit_reader_get_module_name", "unit_dependency_get_filepath",
                     "unit_dependency_get_modulename", "unit_dependency_get_name", "symbol_get_usr", "symbol_get_name"):
            bind(name, Text, Object)
        for name in ("unit_dependency_get_kind", "symbol_get_kind", "symbol_get_language"):
            bind(name, c.c_uint, Object)
        for name in ("symbol_get_properties", "occurrence_get_roles", "symbol_relation_get_roles"):
            bind(name, c.c_uint64, Object)
        for name in ("occurrence_get_symbol", "symbol_relation_get_symbol"):
            bind(name, Object, Object)
        bind("occurrence_get_line_col", None, Object, c.POINTER(c.c_uint), c.POINTER(c.c_uint))
        self.store = self.open(self.store_create, str(store))

    def open(self, create, name, *parents):
        error = Object()
        result = create(*parents, name.encode(), c.byref(error))
        if result:
            return result
        reason = "no diagnostic"
        if error:
            reason = self.error_get_description(error).decode(errors="replace")
            self.error_dispose(error)
        raise RuntimeError(f"cannot read {name}: {reason}")

    @staticmethod
    def visit(apply, visitor, callback_type, *args):
        # ctypes would otherwise log a Python callback exception and continue
        # into native code, which could silently produce a partial oracle.
        failures = []
        @callback_type
        def callback(_, value):
            try:
                visitor(value)
                return True
            except Exception as error:
                failures.append(error)
                return False
        finished = apply(*args, None, callback)
        if failures:
            raise failures[0]
        if not finished:
            raise IncompleteTraversal(f"index traversal did not finish: {apply.__name__}")

    def records(self, selected):
        result = set()
        canonical = {}
        def unit(name):
            reader = self.open(self.unit_reader_create, name.value(), self.store)
            try:
                module = self.unit_reader_get_module_name(reader).value()
                directory = self.unit_reader_get_working_dir(reader).value()
                def dependency(dep):
                    if self.unit_dependency_get_kind(dep) != 2:  # INDEXSTORE_UNIT_DEPENDENCY_RECORD
                        return
                    path = Path(directory) / self.unit_dependency_get_filepath(dep).value()
                    if path not in canonical:
                        canonical[path] = path.resolve()
                    physical = canonical[path]
                    if physical in selected:
                        owner = self.unit_dependency_get_modulename(dep).value()
                        if not owner and physical.suffix == ".swift":
                            owner = module
                        result.add((selected[physical], owner, self.unit_dependency_get_name(dep).value()))
                self.visit(self.unit_reader_dependencies_apply_f, dependency, ObjectCallback, reader)
            finally:
                self.unit_reader_dispose(reader)
        self.visit(self.store_units_apply_f, unit, UnitCallback, self.store, 1)
        return sorted(result)

    def symbol(self, pointer):
        return {"usr": self.symbol_get_usr(pointer).value(), "name": self.symbol_get_name(pointer).value(),
                "kind": self.symbol_get_kind(pointer), "language": self.symbol_get_language(pointer),
                "properties": self.symbol_get_properties(pointer)}

    def facts(self, name):
        reader = self.open(self.record_reader_create, name, self.store)
        rows = []
        def occurrence(occ):
            line, column = c.c_uint(), c.c_uint()
            self.occurrence_get_line_col(occ, c.byref(line), c.byref(column))
            relations = []
            def relation(rel):
                relations.append({"roles": self.symbol_relation_get_roles(rel),
                                  "symbol": self.symbol(self.symbol_relation_get_symbol(rel))})
            self.visit(self.occurrence_relations_apply_f, relation, ObjectCallback, occ)
            rows.append({"line": line.value, "column": column.value, "roles": self.occurrence_get_roles(occ),
                         "symbol": self.symbol(self.occurrence_get_symbol(occ)),
                         "relations": sorted(relations, key=lambda r: json.dumps(r, sort_keys=True))})
        try:
            try:
                self.visit(self.record_reader_occurrences_apply_f, occurrence, ObjectCallback, reader)
            except IncompleteTraversal:
                # A compiler record containing no symbols has no occurrence
                # block. Check its symbol table before accepting an empty result.
                stop_at_first = ObjectCallback(lambda _ctx, _symbol: False)
                if rows or not self.record_reader_symbols_apply_f(reader, True, None, stop_at_first):
                    raise
            return rows
        finally:
            self.record_reader_dispose(reader)


def audit(root, selected_paths, library, output, shared):
    selected = {Path(path).resolve(): str(Path(path).resolve().relative_to(root)) for path in selected_paths}
    catalog = Store(library, root / ".build/debug/index/store")
    all_usrs = set()
    per_shared = {name: set() for name in shared}
    shared_types = defaultdict(set)
    shared_calls = defaultdict(set)
    files = {name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "contexts": {}}
             for path, name in selected.items()}
    try:
        records = catalog.records(selected)
        (output / "oracle-records.json").write_text(json.dumps(records, indent=2) + "\n")
        with (output / "oracle-occurrences.jsonl").open("w") as raw:
            for file, module, record in records:
                digests = []
                for fact in catalog.facts(record):
                    row = json.dumps(fact, sort_keys=True, separators=(",", ":"))
                    print(json.dumps({"file": file, "module": module, "record": record, "occurrence": fact},
                                     separators=(",", ":")), file=raw)
                    digests.append(hashlib.sha256(row.encode()).digest())
                    symbols = [fact["symbol"]["usr"], *(r["symbol"]["usr"] for r in fact["relations"])]
                    all_usrs.update(symbols)
                    if file in per_shared:
                        per_shared[file].update(symbols)
                        roles, symbol = fact["roles"], fact["symbol"]
                        # The raw compiler API marks definition, call and implicit
                        # with bits 1, 5 and 8. Class/struct/enum/protocol/extension
                        # use symbol kinds 5 through 9 in indexstore.h.
                        if roles & (1 << 8):
                            continue
                        if roles & (1 << 1) and symbol["kind"] in (5, 6, 7, 8, 9):
                            shared_types[file].add((module, symbol["usr"]))
                        if roles & (1 << 5):
                            relations = fact["relations"]
                            callers = [r["symbol"]["usr"] for r in relations if r["roles"] & (1 << 13)]
                            if not callers:
                                callers = [r["symbol"]["usr"] for r in relations if r["roles"] & (1 << 16)]
                            for caller in callers or [""]:
                                shared_calls[file].add((module, caller, symbol["usr"], file, str(fact["line"]), str(fact["column"])))
                record_summary = [len(digests), hashlib.sha256(b"".join(sorted(digests))).hexdigest()]
                files[file]["contexts"].setdefault(module, []).append(record_summary)
    finally:
        catalog.store_dispose(catalog.store)
    # Record identifiers can encode the build root. Compare semantic contents
    # by module, preserving multiplicity, and retain original IDs in records.json.
    for file in files.values():
        for values in file["contexts"].values():
            values.sort()
    return {"files": files, "usrs": sorted(all_usrs), "sharedUSRs": {p: sorted(s) for p, s in per_shared.items()},
            "sharedTypes": {p: sorted(shared_types[p]) for p in shared},
            "sharedCalls": {p: sorted(shared_calls[p]) for p in shared}}
