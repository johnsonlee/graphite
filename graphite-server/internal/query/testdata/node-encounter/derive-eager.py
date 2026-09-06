#!/usr/bin/env python3
"""Select full deterministic EAGER results for the ordering regression tests.
The separate known ResourceValueNode Constant-membership gap is excluded only
from the all-kinds fixture; its complete differing rows remain in the audit.
"""
import json
import pathlib
root = pathlib.Path(__file__).resolve().parent
for fixture in ["mixed-sparse", "all-kinds"]:
    source = json.loads((root / (fixture + "-eager-none.json")).read_text())
    cases = [{"name": fixture + "-" + str(i), **case}
             for i, case in enumerate(source["queries"])
             if not (fixture == "all-kinds" and "(n:Constant)" in case["query"])]
    (root / (fixture + "-eager-query-oracle.json")).write_text(json.dumps({
        "mainCommit": "4e328b0109e13c896b74004823fb049fcb19251a", "cases": cases}, indent=2) + "\n")
