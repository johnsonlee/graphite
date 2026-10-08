#!/usr/bin/env python3
"""Record real-source Apple construction, mixed-graph load and server request evidence.

Usage: apple-lifecycle.py {construct,load,query,all} CONFIG.json --out NEW_DIRECTORY
Config (commands are argv arrays, never shell strings):
  sources: [{id, kind:"real-source", repository, commit:<40 hex>}]
  variants: {baseline: {revision, graphs:[{id,path,source,language:"apple"|"jvm"}],
    server_command:["/abs/graphite","serve",...,"--port","{port}"],
    construction:[{name,source,commands:[[frontend,...],["java","-Xmx8g",...]],
      output:"{work}/graph",verify_command:[graphite,"query","{work}/graph",...]}]},
    candidate: {same fields}}
  protocol: {rounds:3, requests_per_case:100, concurrency:[1,4], warmup_requests:5,
    cache_state:"OS cache warm; process cold for load, warmed for query",
    timeout_seconds:60, startup_timeout_seconds:120,
    readiness:{name,query,scope:[all graph ids],row_order:"unordered",limit:10000},
    cases:[{same fields as readiness}]}

Use real persisted graphs with pinned source identities; generated/synthetic graphs
are prohibited. The graph IDs, languages and source identities must match across
variants, with at least one Apple and one JVM graph; every request targets ALL IDs.
Sources are declarative provenance, not an automated authenticity certification.
Saved graph file hashes and the complete protocol are captured before measurement.
Each round uses AB then BA ordering, alternating. Every sample/failure is appended to
samples.jsonl, including warmup and readiness attempts. Never reuse the output folder.
Server --graph mappings are appended from graphs; omit --graph/--data in server_command.
No automatic cache eviction: declare the OS cache state and conditioning externally;
separate runs/configs are needed for cold and warm cache scenarios.

Construction times frontend then importer through saved artifact production. Required
verify_command independently consumes the saved graph outside its measured boundary.
wait4 records stage user/system CPU and peak PROCESS RSS; totals sum CPU and take the
maximum stage peak, never claim simultaneous process-tree RSS. On supported Unix OSes
wait4 CPU can include descendants reaped by that process, per OS accounting semantics.
Load wall time runs from process spawn through HTTP readiness and complete consumption
of readiness + ALL cases, exposing deferred indexes. Load CPU/RSS is process lifetime
through shutdown (reported explicitly). Queries use a separate fresh server, warm each
case, then fixed closed-loop batches of exactly 100 requests at concurrency 1 and 4.
Latencies end after response bytes are fully read, before JSON validation/digest work.
The digest covers columns, rows and row graph provenance, excluding outer timing fields;
unordered cases sort rows without discarding duplicates. p50/p95 use nearest rank
within EACH repeated-request case/concurrency/round, with pooled results and per-round
range retained. Fixed batches are not sustained saturation evidence. Query CPU is the live server user+system delta around each measured batch, using
macOS proc_pid_rusage V2 Mach ticks converted with mach_timebase_info, or Linux
/proc/PID/stat clock ticks.
Query RSS is the conservative wait4 process lifetime high-water including warmup;
a 10 ms current-RSS sampler reports an additional lower bound, never a replacement.
Results give evidence/parity, not automatic regression acceptance or performance claims.
"""
import argparse
import concurrent.futures
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request


class Invalid(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def command_check(command):
    require(isinstance(command, list) and command and all(isinstance(x, str) for x in command), "commands must be nonempty argv arrays")
    require(Path(command[0]).name not in {"sh", "bash", "zsh", "env"}, "use direct processes, not shell/env launchers")
    options = command + [os.environ.get(key, "") for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS")]
    heaps = re.findall(r"-Xmx(\d+)([kKmMgG]?)", " ".join(options))
    sizes = [int(n) * {"": 1, "k": 1024, "m": 1024**2, "g": 1024**3}[unit.lower()] for n, unit in heaps]
    require(all(size <= 8 * 1024**3 for size in sizes), "JVM heap exceeds 8 GiB")
    if Path(command[0]).name == "java":
        require(any(x.startswith("-Xmx") for x in command), "java commands require an explicit <=8 GiB -Xmx")


def heap_settings(command):
    options = command + [os.environ.get(key, "") for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS")]
    return tuple(int(n) * {"": 1, "k": 1024, "m": 1024**2, "g": 1024**3}[unit.lower()]
                 for n, unit in re.findall(r"-Xmx(\d+)([kKmMgG]?)", " ".join(options)))


def server_settings(command):
    # Revision binaries/JARs may live at different paths; feature/runtime flags may not.
    settings = command[1:]
    if "-jar" in settings:
        settings = list(settings)
        at = settings.index("-jar")
        require(at + 1 < len(settings), "-jar needs an artifact path")
        settings[at + 1] = "<revision-jar>"
    return settings


def validate(config):
    sources = config.get("sources", [])
    require(sources and len({s["id"] for s in sources}) == len(sources), "unique pinned sources required")
    for source in sources:
        require(source.get("kind") == "real-source" and source.get("repository") and re.fullmatch(r"[0-9a-fA-F]{40}", source.get("commit", "")), "real-source repository and full commit required")
    variants = config.get("variants", {})
    require(set(variants) == {"baseline", "candidate"}, "baseline and candidate required")
    identity = None
    matched_server = None
    matched_construction = None
    for variant in variants.values():
        require(variant.get("revision"), "variant revision required")
        graphs = variant.get("graphs", [])
        require(len(graphs) >= 2 and len({g["id"] for g in graphs}) == len(graphs), "at least two distinct graph IDs required")
        require(len({str(Path(g["path"]).resolve()) for g in graphs}) == len(graphs), "graph paths must be distinct")
        require({g["language"] for g in graphs} >= {"apple", "jvm"}, "mixed Apple/JVM graphs required")
        require(all(g["source"] in {s["id"] for s in sources} for g in graphs), "each graph needs a pinned real source")
        current = sorted((g["id"], g["source"], g["language"]) for g in graphs)
        require(identity is None or current == identity, "baseline/candidate graph identities must match")
        identity = current
        command_check(variant["server_command"])
        settings = server_settings(variant["server_command"])
        require(matched_server is None or settings == matched_server, "baseline/candidate server settings must match")
        matched_server = settings
        construction_identity = sorted((work.get("name", ""), work.get("source", ""),
                                        tuple(heap_settings(command) for command in work.get("commands", [])))
                                       for work in variant.get("construction", []))
        require(len({work[0] for work in construction_identity}) == len(construction_identity), "construction workload names must be unique")
        require(matched_construction is None or construction_identity == matched_construction,
                "baseline/candidate construction workloads and heap settings must match")
        matched_construction = construction_identity
        require(not any(x == "--graph" or x.startswith("--graph=") or x == "--data" or x.startswith("--data=") for x in variant["server_command"]), "server graph mappings are supplied by the harness")
        require(any("{port}" in x for x in variant["server_command"]), "server command needs {port}")
        for construction in variant.get("construction", []):
            require(construction.get("source") in {s["id"] for s in sources}, "construction must name a pinned source")
            require(re.fullmatch(r"[A-Za-z0-9_-]+", construction.get("name", "")), "safe construction workload name required")
            require(construction.get("name") and construction.get("output") and len(construction.get("commands", [])) >= 2, "construction needs frontend/importer commands and saved output")
            require("{work}" in construction["output"], "construction output must be isolated under {work}")
            for command in construction["commands"] + [construction["verify_command"]]:
                command_check(command)
    protocol = config["protocol"]
    require(protocol.get("rounds", 0) >= 2, "at least two paired rounds required")
    require(protocol.get("requests_per_case") == 100, "declare exactly 100 measured requests per case")
    require(protocol.get("concurrency") == [1, 4], "declare concurrency [1,4]")
    require(protocol.get("warmup_requests", 0) >= 1 and protocol.get("cache_state"), "warmup and cache state required")
    require(protocol.get("timeout_seconds", 0) > 0 and protocol.get("startup_timeout_seconds", 0) > 0, "positive timeouts required")
    cases = protocol.get("cases", [])
    require(cases and len({c["name"] for c in cases}) == len(cases), "unique query cases required")
    ids = {item[0] for item in identity}
    for case in cases:
        if case.get("name") == protocol["readiness"].get("name"):
            keys = ("query", "scope", "row_order", "limit")
            require(all(case.get(key) == protocol["readiness"].get(key) for key in keys),
                    "readiness and case sharing a name must describe the same request")
    for case in [protocol["readiness"]] + cases:
        require(case.get("name") and case.get("query"), "named query required")
        require(set(case.get("scope", [])) == ids and len(case["scope"]) == len(ids), "every query must explicitly target all graphs")
        require(case.get("row_order") in {"ordered", "unordered"}, "declare row_order")
        require(isinstance(case.get("limit"), int) and 0 < case["limit"] <= 10000, "declare response row limit (1..10000)")
    return config


def nearest_rank(values, fraction):
    require(bool(values), "no percentile without samples")
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def digest_result(payload, case):
    result = json.loads(payload)
    require(not result.get("error") and not result.get("truncated"), "query failed or response truncated")
    require(result.get("mode") == "cross-graph" and set(result.get("graphs", [])) == set(case["scope"]), "server did not execute declared cross-graph scope")
    rows = result.get("rows")
    require(isinstance(rows, list) and isinstance(result.get("columns"), list), "query response lacks rows/columns")
    # An unbounded query hitting the transport cap is ambiguous: refuse rather than
    # silently call a capped response complete. Choose an aggregate or larger cap.
    require(len(rows) < case["limit"], "response reached row cap; complete consumption unproven")
    encoded = [json.dumps(row, sort_keys=True, separators=(",", ":"), ensure_ascii=False) for row in rows]
    if case["row_order"] == "unordered":
        encoded.sort()
    canonical = json.dumps([result["columns"], encoded], separators=(",", ":"), ensure_ascii=False).encode()
    return {"digest": hashlib.sha256(canonical).hexdigest(), "row_count": len(rows)}


class Journal:
    def __init__(self, path):
        self.file = open(path, "x")
        self.lock = threading.Lock()
        self.rows = []

    def write(self, **row):
        row = {"recorded_ns": time.time_ns(), **row}
        with self.lock:
            self.file.write(json.dumps(row, sort_keys=True) + "\n")
            self.file.flush()
            self.rows.append(row)
        return row


class Child:
    def __init__(self, command, log):
        self.started = time.perf_counter()
        self.log = open(log, "wb")
        try:
            self.process = subprocess.Popen(command, stdout=self.log, stderr=subprocess.STDOUT, start_new_session=True)
        except BaseException:
            self.log.close()
            raise
        self.result = None

    def poll(self):
        if self.result is None:
            pid, status, usage = os.wait4(self.process.pid, os.WNOHANG)
            if pid:
                self.process.returncode = os.waitstatus_to_exitcode(status)
                self.result = {"exit_code": self.process.returncode, "wall_ms": (time.perf_counter() - self.started) * 1000,
                               "user_seconds": usage.ru_utime, "system_seconds": usage.ru_stime,
                               "cpu_seconds": usage.ru_utime + usage.ru_stime,
                               "peak_process_rss_bytes": usage.ru_maxrss * (1 if sys.platform == "darwin" else 1024)}
                self.log.close()
        return self.result

    def wait(self, timeout):
        deadline = time.monotonic() + timeout
        while self.poll() is None:
            if time.monotonic() >= deadline:
                result = self.stop()
                return {**result, "timeout": True}
            time.sleep(0.01)
        return self.result

    def stop(self):
        if self.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            deadline = time.monotonic() + 5
            while self.poll() is None:
                if time.monotonic() >= deadline:
                    os.killpg(self.process.pid, signal.SIGKILL)
                time.sleep(0.01)
        return self.result


def run_command(command, log, timeout, journal, **context):
    try:
        stats = Child(command, log).wait(timeout)
        row = journal.write(kind="process", command=command, log=str(log), **context, **stats,
                            ok=stats["exit_code"] == 0 and not stats.get("timeout"))
    except Exception as error:
        journal.write(kind="process", command=command, **context, ok=False, error=str(error))
        raise
    require(row["ok"], f"process failed: {command}; see {log}")
    return row


def request(base, case, timeout, journal, **context):
    started = time.perf_counter()
    payload = b""
    row = {"kind": "request", "case": case["name"], "scope": case["scope"], **context, "started_ns": time.time_ns()}
    try:
        body = json.dumps({"query": case["query"], "graphs": case["scope"], "mode": "cross-graph", "limit": case["limit"]}).encode()
        req = urllib.request.Request(base + "/api/cypher/graphs", body, {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as response:
            payload = response.read()
            row["status"] = response.status
        row["wall_ms"] = (time.perf_counter() - started) * 1000
        row.update(digest_result(payload, case))
        row["ok"] = True
    except Exception as error:
        row.setdefault("wall_ms", (time.perf_counter() - started) * 1000)
        if isinstance(error, urllib.error.HTTPError):
            payload = error.read()
            row["status"] = error.code
            error.close()
        row.update(ok=False, error=str(error))
    row["response_bytes"] = len(payload)
    row["response_sha256"] = hashlib.sha256(payload).hexdigest()
    if not row["ok"]:
        row["response_error_body"] = payload.decode(errors="replace")
    return journal.write(**row)


class MacUsageV2(ctypes.Structure):
    # Xcode SDK sys/resource.h: rusage_info_v2. CPU fields are Mach absolute
    # ticks (XNU fill_task_rusage -> task_power_info_locked), not nanoseconds.
    _fields_ = [("ri_uuid", ctypes.c_uint8 * 16)] + [(name, ctypes.c_uint64) for name in [
        "ri_user_time", "ri_system_time", "ri_pkg_idle_wkups", "ri_interrupt_wkups",
        "ri_pageins", "ri_wired_size", "ri_resident_size", "ri_phys_footprint",
        "ri_proc_start_abstime", "ri_proc_exit_abstime", "ri_child_user_time",
        "ri_child_system_time", "ri_child_pkg_idle_wkups", "ri_child_interrupt_wkups",
        "ri_child_pageins", "ri_child_elapsed_abstime", "ri_diskio_bytesread", "ri_diskio_byteswritten"]]


def linux_usage(stat, ticks, page_size):
    # comm may contain spaces and parentheses; fields after its final ')' start at 3.
    fields = stat[stat.rfind(")") + 2:].split()
    return {"user_seconds": int(fields[11]) / ticks, "system_seconds": int(fields[12]) / ticks,
            "resident_bytes": int(fields[21]) * page_size, "identity": fields[19], "cpu_counter": "proc-stat-clock-ticks"}


_MAC_PROC = None
_MAC_TIMEBASE = None


class MachTimebaseInfo(ctypes.Structure):
    _fields_ = [("numer", ctypes.c_uint32), ("denom", ctypes.c_uint32)]


def mac_timebase():
    global _MAC_TIMEBASE
    if _MAC_TIMEBASE is None:
        get_timebase = ctypes.CDLL("/usr/lib/libSystem.B.dylib").mach_timebase_info
        get_timebase.argtypes = [ctypes.POINTER(MachTimebaseInfo)]
        get_timebase.restype = ctypes.c_int
        info = MachTimebaseInfo()
        require(get_timebase(ctypes.byref(info)) == 0 and info.numer > 0 and info.denom > 0,
                "cannot obtain Mach timebase for CPU accounting")
        _MAC_TIMEBASE = (info.numer, info.denom)
    return _MAC_TIMEBASE


def mac_usage(usage, timebase):
    numer, denom = timebase
    require(numer > 0 and denom > 0, "invalid Mach timebase")
    return {"user_seconds": usage.ri_user_time * numer / denom / 1e9,
            "system_seconds": usage.ri_system_time * numer / denom / 1e9,
            "raw_cpu_ticks": {"user": usage.ri_user_time, "system": usage.ri_system_time},
            "mach_timebase": {"numer": numer, "denom": denom},
            "resident_bytes": usage.ri_resident_size, "identity": str(usage.ri_proc_start_abstime),
            "cpu_counter": "proc_pid_rusage-v2-mach-ticks-converted-to-seconds"}


def process_usage(pid):
    global _MAC_PROC
    if sys.platform == "darwin":
        if _MAC_PROC is None:
            _MAC_PROC = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True).proc_pid_rusage
            _MAC_PROC.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
            _MAC_PROC.restype = ctypes.c_int
        usage = MacUsageV2()
        if _MAC_PROC(pid, 2, ctypes.byref(usage)) != 0:
            raise OSError(ctypes.get_errno(), "proc_pid_rusage failed")
        return mac_usage(usage, mac_timebase())
    if sys.platform.startswith("linux"):
        return linux_usage(Path(f"/proc/{pid}/stat").read_text(), os.sysconf("SC_CLK_TCK"), os.sysconf("SC_PAGE_SIZE"))
    raise Invalid("live CPU accounting requires macOS or Linux")


class BatchUsage:
    def __init__(self, pid):
        self.pid = pid
        self.before = process_usage(pid)
        self.samples = [self.before["resident_bytes"]]
        self.errors = []
        self.stop_event = threading.Event()
        def sample():
            while not self.stop_event.wait(.01):
                try:
                    self.samples.append(process_usage(pid)["resident_bytes"])
                except Exception as error:
                    self.errors.append(str(error))
                    break
        self.thread = threading.Thread(target=sample, daemon=True)
        self.thread.start()

    def finish(self):
        self.stop_event.set()
        self.thread.join()
        after = process_usage(self.pid)
        require(after["identity"] == self.before["identity"], "CPU process identity changed")
        user = after["user_seconds"] - self.before["user_seconds"]
        system = after["system_seconds"] - self.before["system_seconds"]
        require(user >= 0 and system >= 0, "CPU counters moved backwards")
        self.samples.append(after["resident_bytes"])
        return {"user_seconds": user, "system_seconds": system, "cpu_seconds": user + system,
                "cpu_before": self.before, "cpu_after": after,
                "sampled_rss_lower_bound_bytes": max(self.samples), "rss_samples": len(self.samples),
                "rss_sample_errors": self.errors, "rss_sample_period_ms": 10}


def port_number():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def replacements(command, **values):
    for key, value in values.items():
        command = [part.replace("{" + key + "}", str(value)) for part in command]
    return command


def start_server(variant_config, protocol, folder, journal, **context):
    port = port_number()
    base = f"http://127.0.0.1:{port}"
    command = replacements(variant_config["server_command"], port=port)
    for graph in variant_config["graphs"]:
        command += ["--graph", f'{graph["id"]}:{Path(graph["path"]).resolve()}']
    try:
        child = Child(command, folder / "server.log")
    except Exception as error:
        journal.write(kind="server_start", **context, command=command, ok=False, error=str(error))
        raise
    deadline = time.monotonic() + protocol["startup_timeout_seconds"]
    try:
        while time.monotonic() < deadline:
            require(child.poll() is None, "server exited before readiness")
            row = request(base, protocol["readiness"], protocol["timeout_seconds"], journal, **context, phase="readiness")
            if row["ok"]:
                return child, base
            time.sleep(0.05)
        raise Invalid("server readiness deadline exceeded")
    except BaseException:
        stats = child.stop()
        journal.write(kind="server_resources", **context, ok=False, **stats)
        raise


def fingerprint(path):
    path = Path(path)
    require(path.exists(), f"graph absent: {path}")
    files = [path] if path.is_file() else sorted(item for item in path.rglob("*") if item.is_file())
    require(bool(files), f"empty saved graph: {path}")
    records = []
    for file in files:
        digest = hashlib.sha256()
        with file.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        records.append({"path": str(file.relative_to(path)) if path.is_dir() else path.name, "sha256": digest.hexdigest(), "bytes": file.stat().st_size})
    return records


def construct(variant_config, protocol, folder, journal, **context):
    require(variant_config.get("construction"), "construction workloads required")
    for work in variant_config["construction"]:
        directory = folder / work["name"]
        directory.mkdir()
        stages = []
        started = time.perf_counter()
        for index, command in enumerate(work["commands"]):
            stages.append(run_command(replacements(command, work=directory), directory / f"stage-{index}.log",
                                      protocol["startup_timeout_seconds"], journal, **context, workload=work["name"], stage=index))
        wall = (time.perf_counter() - started) * 1000
        output = replacements([work["output"]], work=directory)[0]
        try:
            artifacts = fingerprint(output)
            run_command(replacements(work["verify_command"], work=directory), directory / "verify.log",
                        protocol["timeout_seconds"], journal, **context, workload=work["name"], stage="verification-excluded")
            journal.write(kind="construction", **context, workload=work["name"], ok=True, wall_ms=wall,
                          cpu_seconds=sum(stage["cpu_seconds"] for stage in stages),
                          max_stage_peak_process_rss_bytes=max(stage["peak_process_rss_bytes"] for stage in stages), artifacts=artifacts)
        except Exception as error:
            journal.write(kind="construction", **context, workload=work["name"], ok=False, wall_ms=wall, error=str(error))
            raise


def server_run(variant_config, protocol, folder, journal, operation, **context):
    context = {**context, "operation": operation}
    child, base = start_server(variant_config, protocol, folder, journal, **context)
    ok = False
    try:
        if operation == "load":
            for case in protocol["cases"]:
                require(request(base, case, protocol["timeout_seconds"], journal, **context, phase="load-consumption")["ok"], "load query failed")
            usage = process_usage(child.process.pid)
            journal.write(kind="load", **context, ok=True, wall_ms=(time.perf_counter() - child.started) * 1000,
                          user_seconds=usage["user_seconds"], system_seconds=usage["system_seconds"],
                          cpu_seconds=usage["user_seconds"] + usage["system_seconds"], cpu_counter=usage["cpu_counter"],
                          cpu_usage=usage)
        else:
            for case in protocol["cases"]:
                for index in range(protocol["warmup_requests"]):
                    require(request(base, case, protocol["timeout_seconds"], journal, **context, phase="warmup", sample=index)["ok"], "warmup query failed")
            for concurrency in protocol["concurrency"]:
                for case in protocol["cases"]:
                    def run(index):
                        return request(base, case, protocol["timeout_seconds"], journal, **context,
                                       phase="measured", concurrency=concurrency, sample=index)
                    usage = BatchUsage(child.process.pid)
                    started = time.perf_counter()
                    rows = []
                    try:
                        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
                            rows = list(pool.map(run, range(protocol["requests_per_case"])))
                    finally:
                        wall = (time.perf_counter() - started) * 1000
                        stats = usage.finish()
                        journal.write(kind="query_batch", **context, case=case["name"], scope=case["scope"],
                                      concurrency=concurrency, samples=len(rows), failures=sum(not row["ok"] for row in rows),
                                      ok=len(rows) == protocol["requests_per_case"] and all(row["ok"] for row in rows),
                                      wall_ms=wall, **stats)
                    require(all(row["ok"] for row in rows), "measured requests failed; all batch outcomes retained")
        require(child.poll() is None, "server exited unexpectedly")
        ok = True
    finally:
        stats = child.stop()
        journal.write(kind="server_resources", **context, ok=ok, **stats,
                      resource_boundary="whole server lifetime including startup, requests, warmup if any, shutdown")


def summarize(rows):
    groups, parity = {}, {}
    for row in rows:
        if row["kind"] != "request" or not row["ok"]:
            continue
        parity.setdefault(row["case"], {}).setdefault(row["variant"], set()).add((row["digest"], row["row_count"]))
        if row.get("phase") == "measured":
            key = (row["variant"], row["case"], row["concurrency"])
            groups.setdefault(key, {}).setdefault(row["round"], []).append(row["wall_ms"])
    result = {"quantile_method": "nearest-rank", "queries": [], "parity": [],
              "query_cpu_boundary": "live process CPU delta around each measured request batch",
              "query_rss_boundary": "wait4 lifetime process peak includes startup/warmup; sampled RSS is supplementary lower bound",
              "query_resources": [row for row in rows if row["kind"] == "query_batch"]}
    for (variant, case, concurrency), rounds in sorted(groups.items()):
        values = [value for group in rounds.values() for value in group]
        per_round = [{"round": number, "samples": len(samples), "p50_ms": nearest_rank(samples, .5), "p95_ms": nearest_rank(samples, .95)} for number, samples in sorted(rounds.items())]
        result["queries"].append({"variant": variant, "case": case, "concurrency": concurrency, "samples": len(values),
                                  "p50_ms": nearest_rank(values, .5), "p95_ms": nearest_rank(values, .95), "rounds": per_round,
                                  "round_p95_range_ms": [min(row["p95_ms"] for row in per_round), max(row["p95_ms"] for row in per_round)]})
    for case, variants in sorted(parity.items()):
        result["parity"].append({"case": case, "ok": set(variants) == {"baseline", "candidate"} and len(variants["baseline"]) == 1 and variants["baseline"] == variants["candidate"],
                                 "values": {name: sorted(values) for name, values in variants.items()}})
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["construct", "load", "query", "all"])
    parser.add_argument("config", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    config = validate(json.loads(args.config.read_text()))
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "protocol.json").write_text(json.dumps(config, indent=2) + "\n")
    journal = Journal(args.out / "samples.jsonl")
    journal.write(kind="environment", platform=platform.platform(), machine=platform.machine(), python=sys.version, cpu_count=os.cpu_count(),
                  accounting="wait4 process peak RSS; live native process CPU counters")
    protocol = config["protocol"]
    failed = False
    operations = ["construct", "load", "query"] if args.operation == "all" else [args.operation]
    try:
        if any(operation != "construct" for operation in operations):
            for name, variant in config["variants"].items():
                for graph in variant["graphs"]:
                    journal.write(kind="graph_identity", variant=name, graph=graph, files=fingerprint(graph["path"]))
        for operation in operations:
            for number in range(protocol["rounds"]):
                order = ["baseline", "candidate"] if number % 2 == 0 else ["candidate", "baseline"]
                for name in order:
                    folder = args.out / f"{operation}-{number}-{name}"
                    folder.mkdir()
                    context = {"variant": name, "round": number, "cache_state": protocol["cache_state"]}
                    try:
                        if operation == "construct":
                            construct(config["variants"][name], protocol, folder, journal, operation=operation, **context)
                        else:
                            server_run(config["variants"][name], protocol, folder, journal, operation, **context)
                    except Exception as error:
                        failed = True
                        journal.write(kind="failure", operation=operation, **context, ok=False, error=str(error))
    except Exception as error:
        failed = True
        journal.write(kind="failure", ok=False, error=str(error))
    finally:
        summary = summarize(journal.rows)
        summary["ok"] = not failed and all(row["ok"] for row in summary["parity"])
        (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        journal.file.close()
    return 0 if summary["ok"] else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Invalid, KeyError, ValueError, OSError) as error:
        print(f"Refused: {error}", file=sys.stderr)
        sys.exit(2)
