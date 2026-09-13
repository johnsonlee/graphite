#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { QUERIES } from "./benchmark-slow-query-shapes.mjs";
export { QUERIES };
export const SCHEMA = "graphite-slow-query-warm-v1";
export const ACCEPTANCE = "paired-regression-lt5-spread-diagnostic-v1";
export const PROTOCOL = Object.freeze({
  warmupMinNanos: 10_000_000_000,
  warmupMinCalls: 5,
  measurementMinNanos: 10_000_000_000,
  measurementMinCalls: 40,
  forks: 3,
});
export const SIGNATURE = [
  "id",
  "family",
  "shape",
  "selectivity",
  "operator",
  "boundary",
  "projection",
  "targetGraphId",
  "workloadIdentity",
  "limit",
  "outcome",
  "rowCount",
  "responseBytes",
  "digest",
];
export const HEADER = [
  "phase",
  "round",
  "phaseElapsedNanos",
  ...SIGNATURE,
  "latencyNanos",
  "maxHeapBytes",
];
export const ORDER = [
  "candidate",
  "base",
  "base",
  "candidate",
  "candidate",
  "base",
];
const sha = (value) => crypto.createHash("sha256").update(value).digest("hex");
const positive = (value) =>
  /^\d+$/.test(String(value)) &&
  Number.isSafeInteger(Number(value)) &&
  Number(value) > 0;
const hex = (value) =>
  typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export function queryText(name) {
  const hit = name.endsWith("Hit"),
    value = hit
      ? "android.permission.INTERNET"
      : "GraphiteSlowShapeAbsent293746X",
    caller = hit ? "android.app.Activity" : "GraphiteSlowShapeAbsent293746X";
  const nodeReturn =
    " RETURN id(n) AS id, labels(n) AS labels, n.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50";
  const edgeReturn =
    " RETURN id(c) AS source, id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50";
  switch (name.replace(/Hit$|Miss$/, "")) {
    case "value":
      return `MATCH (n) WHERE n.value CONTAINS '${value}'${nodeReturn}`;
    case "qualifiedId":
      return `MATCH (n) WHERE n.qualifiedId CONTAINS '${hit ? "938826" : "93882699"}' RETURN id(n) AS id, labels(n) AS labels, n.qualifiedId AS qualifiedId, n.graphId AS graphId LIMIT 50`;
    case "dynamic":
      return `MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS '${value}')${nodeReturn}`;
    case "wrappedCaller":
      return `MATCH (n) WHERE toString(n.caller_class) CONTAINS '${caller}'${nodeReturn}`;
    case "dataflowSource":
      return `MATCH (c)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS '${value}'${edgeReturn}`;
    case "dataflowTarget":
      return `MATCH (c)-[r:DATAFLOW]->(n) WHERE n.caller_class CONTAINS '${caller}'${edgeReturn}`;
    default:
      throw new Error(`Unknown query ${name}`);
  }
}
export function metadata(name) {
  return {
    id: `slow-shapes-${name}`,
    family: "slow-query-shapes",
    shape: name.replace(/Hit$|Miss$/, ""),
    selectivity: name.endsWith("Hit") ? "targeted" : "zero",
    operator: "cypher",
    boundary: "single-graph",
    projection: "ordered-full-result",
    targetGraphId: "android",
    workloadIdentity: sha(queryText(name)),
    limit: "50",
    outcome: "success",
  };
}
export function* lines(content) {
  if (typeof content === "string") {
    let start = 0;
    while (start < content.length) {
      let end = content.indexOf("\n", start);
      if (end < 0) end = content.length;
      yield content.slice(start, end).replace(/\r$/, "");
      start = end + 1;
    }
  } else yield* content;
}
export function* readLines(file) {
  const fd = fs.openSync(file, "r"),
    buffer = Buffer.alloc(65536);
  let pending = "";
  try {
    let size;
    while ((size = fs.readSync(fd, buffer, 0, buffer.length, null))) {
      pending += buffer.toString("utf8", 0, size);
      let end;
      while ((end = pending.indexOf("\n")) >= 0) {
        yield pending.slice(0, end).replace(/\r$/, "");
        pending = pending.slice(end + 1);
      }
    }
    if (pending) yield pending;
  } finally {
    fs.closeSync(fd);
  }
}
const quantile = (values, fraction) =>
  [...values].sort((a, b) => a - b)[Math.ceil(values.length * fraction) - 1];
export function compareSlowWarm({
  samples,
  oracle,
  provenance,
  fixtureBefore,
  fixtureAfter,
  partial = false,
}) {
  const integrityErrors = [],
    latencyErrors = [];
  const check = (condition, message) => {
    if (!condition && !integrityErrors.includes(message))
      integrityErrors.push(message);
  };
  const signatures = new Map();
  for (const line of lines(oracle)) {
    if (!line) continue;
    const fields = line.split("|"),
      row = Object.fromEntries(SIGNATURE.map((key, i) => [key, fields[i]]));
    const name = row.id?.replace(/^slow-shapes-/, "");
    check(
      fields.length === 14 && QUERIES.includes(name) && !signatures.has(name),
      "oracle: exact twelve unique query identities required",
    );
    if (!QUERIES.includes(name)) continue;
    for (const [key, value] of Object.entries(metadata(name)))
      check(row[key] === value, `oracle/${name}: invalid ${key}`);
    check(
      /^\d+$/.test(row.rowCount) &&
        Number(row.rowCount) <= 50 &&
        (name.endsWith("Hit")
          ? Number(row.rowCount) > 0
          : row.rowCount === "0"),
      `oracle/${name}: hit/miss row count`,
    );
    check(
      positive(row.responseBytes) && hex(row.digest),
      `oracle/${name}: invalid ordered result digest/bytes`,
    );
    signatures.set(name, row);
  }
  check(signatures.size === 12, "oracle: missing query");
  check(
    Array.isArray(fixtureBefore) &&
      fixtureBefore.length > 0 &&
      equal(fixtureBefore, fixtureAfter),
    "fixture: before/after fingerprints differ or missing",
  );
  if (Array.isArray(fixtureBefore)) {
    const names = new Set();
    for (const item of fixtureBefore) {
      check(
        typeof item.name === "string" &&
          !names.has(item.name) &&
          hex(item.sha256) &&
          /^\d+$/.test(item.bytes),
        "fixture: invalid fingerprint entry",
      );
      names.add(item.name);
    }
  }
  const p = provenance ?? {};
  check(
    p.fixtureProtocol === "private-copy-no-callsite-index-v2" &&
      p.protocol === "slow-query-warm-v1" &&
      p.forks === 3 &&
      p.thresholdPercent === 5,
    "provenance: fixture/measurement policy",
  );
  check(p.schema === "graphite-slow-warm-provenance-v1", "provenance: schema");
  check(
    /^[a-f0-9]{40}$/.test(p.baseSha ?? "") &&
      /^[a-f0-9]{40}$/.test(p.candidateSha ?? ""),
    "provenance: revision identity",
  );
  for (const key of [
    "harnessSha256",
    "oracleSha256",
    "fixtureBeforeSha256",
    "fixtureAfterSha256",
    "baseJarSha256",
    "candidateJarSha256",
  ])
    check(hex(p[key]), `provenance: ${key}`);
  check(
    positive(p.runId) &&
      positive(p.runAttempt) &&
      typeof p.hostname === "string" &&
      p.hostname.length > 0,
    "provenance: runner identity",
  );
  check(
    typeof p.runtimeVersion === "string" &&
      /^17\.[0-9A-Za-z.+-]+$/.test(p.runtimeVersion) &&
      p.activeProcessorCount === 4,
    "provenance: runtime/processor controls",
  );
  check(
    equal(p.order, ORDER) && equal(p.queries, QUERIES),
    "provenance: query/pair order",
  );
  check(
    Array.isArray(p.runs) &&
      (partial
        ? p.runs.length >= 2 && p.runs.length <= 72 && p.runs.length % 2 === 0
        : p.runs.length === 72),
    "provenance: complete paired JVM runs required",
  );
  check(
    ["unmodified-base", "base-plus-subscript-correctness-repair"].includes(
      p.referenceKind,
    ),
    "provenance: reference kind",
  );
  if (p.referenceKind === "base-plus-subscript-correctness-repair")
    check(hex(p.referenceJarSha256), "provenance: reference JAR hash");
  const expected = QUERIES.flatMap((queryName) =>
      ORDER.map((revision, i) => ({
        queryName,
        revision,
        pair: Math.floor(i / 2) + 1,
        rawFile: `${queryName}-${revision}-${Math.floor(i / 2) + 1}.tsv`,
      })),
    ),
    stats = new Map();
  if (partial) expected.splice(p.runs?.length ?? 0);
  let previousEnd = -Infinity,
    sharedHeap;
  for (const [index, item] of expected.entries()) {
    const run = p.runs?.[index],
      label = item.rawFile;
    check(
      run && Object.entries(item).every(([key, value]) => run[key] === value),
      `${label}: run identity/order`,
    );
    if (run) {
      check(
        run.exitCode === 0 &&
          hex(run.rawSha256) &&
          run.jarSha256 ===
            p[
              item.revision === "base" &&
              item.queryName.startsWith("dynamic") &&
              p.referenceKind === "base-plus-subscript-correctness-repair"
                ? "referenceJarSha256"
                : `${item.revision}JarSha256`
            ],
        `${label}: incomplete run or JAR binding`,
      );
      const start = run.startedAt,
        end = run.completedAt;
      check(
        Number.isSafeInteger(start) &&
          Number.isSafeInteger(end) &&
          start > 0 &&
          start >= previousEnd &&
          end >= start,
        `${label}: serial JVM timestamps`,
      );
      previousEnd = end;
    }
    const content =
      samples instanceof Map ? samples.get(label) : samples?.[label];
    check(content !== undefined, `${label}: missing raw`);
    if (content === undefined) continue;
    const iterator = lines(content)[Symbol.iterator]();
    check(iterator.next().value === HEADER.join("\t"), `${label}: raw header`);
    const phases = {
        warmup: { count: 0, elapsed: 0, sum: 0 },
        measurement: { count: 0, elapsed: 0, sum: 0 },
      },
      values = [];
    for (let next = iterator.next(); !next.done; next = iterator.next()) {
      const fields = next.value.split("\t");
      check(fields.length === HEADER.length, `${label}: raw field count`);
      const row = Object.fromEntries(HEADER.map((key, i) => [key, fields[i]]));
      for (const key of SIGNATURE)
        check(
          row[key] === signatures.get(item.queryName)?.[key],
          `${label}: ${key} differs from full oracle`,
        );
      const phase = phases[row.phase];
      check(!!phase, `${label}: invalid phase`);
      if (!phase) continue;
      check(
        !(
          phase.count >= PROTOCOL[`${row.phase}MinCalls`] &&
          phase.elapsed >= PROTOCOL[`${row.phase}MinNanos`]
        ),
        `${label}: samples after first eligible phase end`,
      );
      check(
        row.phase !== "warmup" || phases.measurement.count === 0,
        `${label}: warmup after measurement`,
      );
      check(
        row.phase !== "measurement" ||
          (phases.warmup.count >= 5 && phases.warmup.elapsed >= 10_000_000_000),
        `${label}: missing complete warmup`,
      );
      check(
        positive(row.round) && Number(row.round) === phase.count + 1,
        `${label}: noncontiguous round`,
      );
      const elapsed = Number(row.phaseElapsedNanos),
        latency = Number(row.latencyNanos),
        heap = Number(row.maxHeapBytes);
      check(
        positive(row.latencyNanos) &&
          positive(row.phaseElapsedNanos) &&
          elapsed > phase.elapsed &&
          latency <= elapsed - phase.elapsed,
        `${label}: invalid timing`,
      );
      check(
        positive(row.maxHeapBytes) &&
          heap <= 8 * 1024 ** 3 &&
          heap >= 7 * 1024 ** 3,
        `${label}: maximum heap must be 8 GiB`,
      );
      if (sharedHeap === undefined) sharedHeap = heap;
      else check(heap === sharedHeap, `${label}: heap changed`);
      phase.count++;
      phase.elapsed = elapsed;
      phase.sum += latency;
      check(phase.sum <= elapsed, `${label}: latency sum exceeds elapsed`);
      if (row.phase === "measurement") values.push(latency);
    }
    for (const [name, phase] of Object.entries(phases))
      check(
        phase.count >= PROTOCOL[`${name}MinCalls`] &&
          phase.elapsed >= PROTOCOL[`${name}MinNanos`],
        `${label}: incomplete ${name}`,
      );
    stats.set(label, {
      p50: quantile(values, 0.5),
      p95: quantile(values, 0.95),
      warmupCalls: phases.warmup.count,
      warmupElapsedNanos: phases.warmup.elapsed,
      measurementCalls: phases.measurement.count,
      measurementElapsedNanos: phases.measurement.elapsed,
    });
  }
  check(
    (samples instanceof Map
      ? samples.size
      : Object.keys(samples ?? {}).length) === expected.length,
    "raw: exact completed file set required",
  );
  const queries = QUERIES.filter((queryName) =>
    expected.some((item) => item.queryName === queryName),
  ).map((queryName) => {
    const runs = [],
      errors = [];
    for (let pair = 1; pair <= 3; pair++) {
      const base = stats.get(`${queryName}-base-${pair}.tsv`),
        candidate = stats.get(`${queryName}-candidate-${pair}.tsv`);
      if (!base || !candidate) continue;
      const run = { pair, base, candidate };
      runs.push(run);
      for (const q of ["p50", "p95"])
        if (
          Number.isSafeInteger(base[q]) &&
          Number.isSafeInteger(candidate[q]) &&
          BigInt(candidate[q]) * 100n >= BigInt(base[q]) * 105n
        )
          errors.push(
            `${queryName}/pair-${pair}: ${q.toUpperCase()} regression must be <5%`,
          );
    }
    const stability = {};
    for (const revision of ["base", "candidate"])
      for (const q of ["p50", "p95"]) {
        const values = runs.map((r) => r[revision][q]);
        if (
          values.length >= 2 &&
          values.every((v) => Number.isFinite(v) && v > 0)
        )
          stability[`${revision}${q.toUpperCase()}SpreadPercent`] =
            (Math.max(...values) / Math.min(...values) - 1) * 100;
      }
    latencyErrors.push(...errors);
    return {
      id: `slow-shapes-${queryName}`,
      queryName,
      passed:
        !partial &&
        integrityErrors.length === 0 &&
        errors.length === 0 &&
        runs.length === 3,
      errors,
      runs,
      stability,
    };
  });
  return {
    schema: SCHEMA,
    acceptance: ACCEPTANCE,
    protocol: { ...PROTOCOL },
    passed:
      !partial && integrityErrors.length === 0 && latencyErrors.length === 0,
    complete: !partial && integrityErrors.length === 0,
    ...(partial
      ? {
          partial: true,
          canContinue:
            integrityErrors.length === 0 &&
            queries.every(
              (query) => query.runs.length === 1 || query.errors.length === 0,
            ),
        }
      : {}),
    maxHeapBytes: sharedHeap,
    queryCount: 12,
    forkCount: 3,
    baseSha: p.baseSha,
    candidateSha: p.candidateSha,
    provenance: p,
    integrityErrors,
    errors: integrityErrors,
    latencyErrors,
    queries,
  };
}
export function renderSlowWarm(result) {
  return [
    "### Slow query shapes — steady state",
    `Status: ${result.passed ? "PASS" : "FAIL"}; 12 Android queries, three paired forks, 8 GiB.`,
    `Each query has ≥10s/5 warmup calls and ≥10s/40 measured calls. P50/P95 paired regression must be strictly below 5%; cross-fork spread is diagnostic only.`,
    "",
    ...result.integrityErrors.map((e) => `- Integrity: ${e}`),
    ...result.latencyErrors.map((e) => `- ${e}`),
    "",
    "| Query | Pair | Base P50 ns | Candidate P50 ns | Base P95 ns | Candidate P95 ns |",
    "|---|---:|---:|---:|---:|---:|",
    ...result.queries.flatMap((q) =>
      q.runs.map(
        (r) =>
          `| ${q.queryName} | ${r.pair} | ${r.base.p50} | ${r.candidate.p50} | ${r.base.p95} | ${r.candidate.p95} |`,
      ),
    ),
    "",
    "Cross-fork spreads (diagnostic):",
    ...result.queries.map(
      (q) => `- ${q.queryName}: ${JSON.stringify(q.stability)}`,
    ),
    "",
  ].join("\n");
}
async function fileHash(file) {
  const hash = crypto.createHash("sha256");
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest("hex");
}
export async function compareDirectory({
  directory,
  oracle,
  provenance,
  fixtureBefore,
  fixtureAfter,
  jarsDirectory,
  partial = false,
}) {
  const p = JSON.parse(fs.readFileSync(provenance, "utf8")),
    samples = new Map(),
    bindingErrors = [],
    runtimeHeaps = [],
    snapshots = new Set();
  for (const [file, key] of [
    [oracle, "oracleSha256"],
    [fixtureBefore, "fixtureBeforeSha256"],
    [fixtureAfter, "fixtureAfterSha256"],
  ])
    if ((await fileHash(file)) !== p[key])
      bindingErrors.push(`provenance: actual ${key} mismatch`);
  for (const variant of [
    "base",
    "candidate",
    ...(p.referenceKind === "base-plus-subscript-correctness-repair"
      ? ["reference"]
      : []),
  ]) {
    const file = path.join(
      jarsDirectory ?? directory,
      `${variant}-slow-shapes.jar`,
    );
    if (
      !fs.existsSync(file) ||
      (await fileHash(file)) !== p[`${variant}JarSha256`]
    )
      bindingErrors.push(`${variant}: actual JAR hash mismatch`);
  }
  for (const run of p.runs ?? []) {
    if (
      typeof run.rawFile !== "string" ||
      path.basename(run.rawFile) !== run.rawFile
    ) {
      bindingErrors.push("unsafe raw file path");
      continue;
    }
    const file = path.join(directory, run.rawFile);
    if (!fs.existsSync(file)) {
      bindingErrors.push(`${run.rawFile}: missing file`);
      continue;
    }
    if ((await fileHash(file)) !== run.rawSha256)
      bindingErrors.push(`${run.rawFile}: raw SHA mismatch`);
    samples.set(run.rawFile, readLines(file));
    const log =
      typeof run.logFile === "string" &&
      path.basename(run.logFile) === run.logFile
        ? path.join(directory, run.logFile)
        : "";
    if (!log || !fs.existsSync(log) || (await fileHash(log)) !== run.logSha256)
      bindingErrors.push(`${run.rawFile}: log SHA mismatch`);
    else {
      const logLines = fs.readFileSync(log, "utf8").split(/\r?\n/);
      const fixtureMarkers = logLines.filter((line) =>
        line.startsWith("SLOW_QUERY_SHAPE_FIXTURE\t"),
      );
      if (fixtureMarkers.length !== 1)
        bindingErrors.push(
          `${run.rawFile}: private fixture marker missing/duplicate`,
        );
      else {
        const parts = fixtureMarkers[0]
          .split("\t")
          .slice(1)
          .map((field) => {
            const separator = field.indexOf("=");
            return [field.slice(0, separator), field.slice(separator + 1)];
          });
        const fields = Object.fromEntries(parts);
        const snapshot = fields.snapshot;
        if (
          parts.length !== 5 ||
          new Set(parts.map(([key]) => key)).size !== 5 ||
          fields.protocol !== "private-copy-no-callsite-index-v2" ||
          fields.corpus !== "android" ||
          typeof p.fixture !== "string" ||
          !path.isAbsolute(p.fixture) ||
          fields.source !== p.fixture ||
          fields.indexAbsent !== "true" ||
          typeof snapshot !== "string" ||
          !path.isAbsolute(snapshot) ||
          path.basename(snapshot) !== "android" ||
          !path
            .basename(path.dirname(snapshot))
            .startsWith("graphite-slow-query-shapes-") ||
          path.normalize(snapshot) !== snapshot
        ) {
          bindingErrors.push(
            `${run.rawFile}: invalid private fixture identity`,
          );
        } else {
          if (snapshots.has(snapshot))
            bindingErrors.push(`${run.rawFile}: private snapshot reused`);
          snapshots.add(snapshot);
          if (fs.existsSync(path.dirname(snapshot)))
            bindingErrors.push(
              `${run.rawFile}: private snapshot not cleaned up`,
            );
        }
      }
      const markers = logLines.filter((line) =>
        line.startsWith("SLOW_QUERY_WARM_RUNTIME\t"),
      );
      if (markers.length !== 1)
        bindingErrors.push(`${run.rawFile}: runtime marker missing/duplicate`);
      else {
        const fields = Object.fromEntries(
          markers[0]
            .split("\t")
            .slice(1)
            .map((x) => x.split("=")),
        );
        runtimeHeaps.push(Number(fields.maxHeapBytes));
        if (
          fields.vmVersion !== p.runtimeVersion ||
          fields.activeProcessorCount !== "4" ||
          !positive(fields.maxHeapBytes) ||
          Number(fields.maxHeapBytes) > 8 * 1024 ** 3 ||
          Number(fields.maxHeapBytes) < 7 * 1024 ** 3
        )
          bindingErrors.push(
            `${run.rawFile}: actual runtime controls mismatch`,
          );
      }
    }
  }
  const result = compareSlowWarm({
    samples,
    oracle: fs.readFileSync(oracle, "utf8"),
    provenance: p,
    fixtureBefore: JSON.parse(fs.readFileSync(fixtureBefore, "utf8")),
    fixtureAfter: JSON.parse(fs.readFileSync(fixtureAfter, "utf8")),
    partial,
  });
  if (runtimeHeaps.some((heap) => heap !== result.maxHeapBytes))
    bindingErrors.push("runtime marker heap differs from raw observations");
  result.integrityErrors.push(...bindingErrors);
  result.passed &&= bindingErrors.length === 0;
  result.complete &&= bindingErrors.length === 0;
  if (partial) result.canContinue &&= bindingErrors.length === 0;
  return result;
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const args = process.argv.slice(2);
  const options = {};
  let result;
  try {
    const command = args.shift();
    if (!["compare", "checkpoint"].includes(command))
      throw new Error("Expected compare/checkpoint");
    options.partial = command === "checkpoint";
    while (args.length) {
      const key = args.shift(),
        value = args.shift();
      if (!key?.startsWith("--") || !value)
        throw new Error("Invalid arguments");
      options[key.slice(2)] = value;
    }
    result = await compareDirectory({
      directory: options.directory,
      oracle: options.oracle,
      provenance: options.provenance,
      fixtureBefore: options["fixture-before"],
      fixtureAfter: options["fixture-after"],
      jarsDirectory: options["jars-directory"],
      partial: options.partial,
    });
  } catch (error) {
    result = {
      schema: SCHEMA,
      acceptance: ACCEPTANCE,
      passed: false,
      complete: false,
      integrityErrors: [error.message],
      errors: [error.message],
      latencyErrors: [],
      queries: [],
    };
  }
  if (options.status)
    fs.writeFileSync(options.status, JSON.stringify(result, null, 2) + "\n");
  if (options.report) fs.writeFileSync(options.report, renderSlowWarm(result));
  console.log(
    JSON.stringify({
      passed: result.passed,
      integrityErrors: result.integrityErrors.length,
      latencyErrors: result.latencyErrors.length,
    }),
  );
  if (!(options.partial ? result.canContinue : result.passed))
    process.exitCode = 1;
}
