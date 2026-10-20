"""Check that one jar's answers contain another's for the A/B cases whose response bodies differ.

    python3 backend/bench/jvm_query_ab_answers.py /path/to/answers

`answers/` holds `<label>-<case>.json`, the full `/api/cypher/graphs` response of each case from each
server, as `jvm_query_ab.py --dump-answers answers/` writes them. The feature-presence aggregates must
keep the same member totals per graph (and per node kind) with the base reporting every presence flag
false; the full projections must keep every base property with an equal value on every row, with only
the declared-type properties added. Labels default to `main` and `head` (`--base`/`--head`).
"""
import json, sys, pathlib
from collections import defaultdict
A = pathlib.Path(sys.argv[1])
BASE = sys.argv[sys.argv.index("--base") + 1] if "--base" in sys.argv else "main"
HEAD = sys.argv[sys.argv.index("--head") + 1] if "--head" in sys.argv else "head"
NEW = {"generic_type", "type_info", "generic_return_type", "return_type_info", "generic_parameter_types",
       "parameter_type_info", "type_parameters"}
def rows(label, case):
    return json.loads((A / f"{label}-{case}.json").read_bytes())["rows"]
ok = True
def report(case, good, detail):
    global ok
    ok &= good
    print(f"{'PASS' if good else 'FAIL'} {case}: {detail}")

m, h = rows(BASE, "feature-nodes"), rows(HEAD, "feature-nodes")
sums = lambda rs: {k: sum(r["members"] for r in rs if (r["graphId"], r["kind"]) == k) for k in {(r["graphId"], r["kind"]) for r in rs}}
flags_main = all(not r["rendered"] and not r["structured"] for r in m)
report("feature-nodes", sums(m) == sums(h) and flags_main,
       f"member totals per (graph, kind) equal over {len(sums(m))} groups={sums(m) == sums(h)}; main presence all false={flags_main}; "
       f"head rendered members={sum(r['members'] for r in h if r['rendered'])}/{sum(r['members'] for r in h)}")

m, h = rows(BASE, "feature-methods"), rows(HEAD, "feature-methods")
gs = lambda rs: {g: sum(r["members"] for r in rs if r["graphId"] == g) for g in {r["graphId"] for r in rs}}
flag_cols = ["returnRendered", "returnStructured", "parametersRendered", "parametersStructured", "formalParameters"]
flags_main = all(not r[c] for r in m for c in flag_cols)
report("feature-methods", gs(m) == gs(h) and flags_main,
       f"method totals per graph equal over {len(gs(m))} graphs={gs(m) == gs(h)}; main presence all false={flags_main}; "
       f"head rows with any presence={sum(r['members'] for r in h if any(r[c] for c in flag_cols))}/{sum(r['members'] for r in h)}")

for case in ["field-full", "field-properties", "parameter-full", "parameter-properties", "return-full", "return-properties",
             "method-full", "method-properties"]:
    m, h = rows(BASE, case), rows(HEAD, case)
    good = len(m) == len(h)
    extra_keys, added_rows, mismatches = set(), 0, 0
    for a, b in zip(m, h):
        va, vb = a["value"], b["value"]
        if a.get("$metadata") != b.get("$metadata"):
            mismatches += 1
        for k, v in va.items():
            if k not in vb or vb[k] != v:
                mismatches += 1
                break
        extra = set(vb) - set(va)
        extra_keys |= extra
        if extra:
            added_rows += 1
    good = good and mismatches == 0 and extra_keys <= NEW
    report(case, good, f"rows {len(m)}/{len(h)}; rows where main's properties are not all in head with equal values: {mismatches}; "
                       f"head-only keys {sorted(extra_keys)} on {added_rows} rows")
print("ALL PASS" if ok else "SOME FAIL")
sys.exit(0 if ok else 1)
