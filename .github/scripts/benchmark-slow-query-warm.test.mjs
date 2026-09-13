import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  compareSlowWarm,
  compareDirectory,
  HEADER,
  SIGNATURE,
  QUERIES,
  ORDER,
  metadata,
  renderSlowWarm,
} from "./benchmark-slow-query-warm.mjs";
const hash = (value) => crypto.createHash("sha256").update(value).digest("hex");
function fixture() {
  const oracle =
    QUERIES.map((name) =>
      SIGNATURE.map(
        (key) =>
          ({
            ...metadata(name),
            rowCount: name.endsWith("Hit") ? "1" : "0",
            responseBytes: "100",
            digest: "d".repeat(64),
          })[key],
      ).join("|"),
    ).join("\n") + "\n";
  const signatures = new Map(
    oracle
      .trim()
      .split("\n")
      .map((line) => [line.split("|")[0], line.split("|")]),
  );
  const provenance = {
    schema: "graphite-slow-warm-provenance-v1",
    fixtureProtocol: "private-copy-no-callsite-index-v2",
    protocol: "slow-query-warm-v1",
    forks: 3,
    thresholdPercent: 5,
    baseSha: "a".repeat(40),
    candidateSha: "b".repeat(40),
    harnessSha256: "c".repeat(64),
    oracleSha256: hash(oracle),
    fixtureBeforeSha256: "d".repeat(64),
    fixtureAfterSha256: "d".repeat(64),
    baseJarSha256: hash("base jar"),
    candidateJarSha256: hash("candidate jar"),
    runId: "1234",
    runAttempt: "1",
    hostname: "same-host",
    runtimeVersion: "17.0.20.1+1",
    activeProcessorCount: 4,
    order: ORDER,
    queries: QUERIES,
    referenceKind: "unmodified-base",
    runs: [],
  };
  const samples = new Map();
  let clock = 1_700_000_000_000;
  for (const name of QUERIES)
    for (const [i, revision] of ORDER.entries()) {
      const pair = Math.floor(i / 2) + 1,
        rawFile = `${name}-${revision}-${pair}.tsv`;
      const rows = [HEADER.join("\t")];
      for (const [phase, count] of [
        ["warmup", 5],
        ["measurement", 40],
      ])
        for (let round = 1; round <= count; round++)
          rows.push(
            [
              phase,
              round,
              Math.floor((round * 10_000_000_000) / count),
              ...signatures.get(`slow-shapes-${name}`),
              1_000_000,
              8 * 1024 ** 3,
            ].join("\t"),
          );
      const raw = rows.join("\n") + "\n";
      samples.set(rawFile, raw);
      provenance.runs.push({
        queryName: name,
        revision,
        pair,
        rawFile,
        rawSha256: hash(raw),
        jarSha256: provenance[`${revision}JarSha256`],
        logFile: rawFile.replace(".tsv", ".log"),
        logSha256: "e".repeat(64),
        startedAt: clock,
        completedAt: clock + 20_000,
        exitCode: 0,
      });
      clock += 20_001;
    }
  const fingerprint = [
    {
      name: "graph.metadata",
      bytes: "20",
      mtimeNs: "123",
      sha256: "f".repeat(64),
    },
  ];
  return {
    samples,
    oracle,
    provenance,
    fixtureBefore: fingerprint,
    fixtureAfter: structuredClone(fingerprint),
  };
}
function mutateRaw(f, file, change) {
  f.samples.set(file, change(f.samples.get(file)));
}
function changeCell(raw, phase, round, field, value) {
  return raw
    .split("\n")
    .map((line) => {
      const cells = line.split("\t");
      if (cells[0] === phase && Number(cells[1]) === round)
        cells[HEADER.indexOf(field)] = String(value);
      return cells.join("\t");
    })
    .join("\n");
}
function setLatency(f, name, revision, pair, latency) {
  const file = `${name}-${revision}-${pair}.tsv`;
  mutateRaw(f, file, (raw) => {
    for (let r = 1; r <= 40; r++)
      raw = changeCell(raw, "measurement", r, "latencyNanos", latency);
    return raw;
  });
}
function prefix(f, count) {
  f.provenance.runs = f.provenance.runs.slice(0, count);
  f.samples = new Map(
    f.provenance.runs.map((r) => [r.rawFile, f.samples.get(r.rawFile)]),
  );
  return f;
}

test("full twelve queries/three pairs pass, includes every raw sample and phase durations", () => {
  const result = compareSlowWarm(fixture());
  assert.equal(result.passed, true);
  assert.equal(result.queries.length, 12);
  assert.equal(result.queries[0].runs[0].base.measurementCalls, 40);
  assert.equal(
    result.queries[0].runs[0].base.warmupElapsedNanos,
    10_000_000_000,
  );
});
test("supports synchronous line iterables", () => {
  const f = fixture();
  for (const [key, raw] of f.samples)
    f.samples.set(
      key,
      (function* () {
        yield* raw.trimEnd().split("\n");
      })(),
    );
  assert.equal(compareSlowWarm(f).passed, true);
});
test("5% exact paired boundary fails while 4.9999% passes", () => {
  const f = fixture();
  setLatency(f, "valueHit", "candidate", 1, 1_049_999);
  assert.equal(compareSlowWarm(f).passed, true);
  setLatency(f, "valueHit", "candidate", 1, 1_050_000);
  const result = compareSlowWarm(f);
  assert.deepEqual(result.latencyErrors, [
    "valueHit/pair-1: P50 regression must be <5%",
    "valueHit/pair-1: P95 regression must be <5%",
  ]);
});
test("large matched cross-fork variation stays diagnostic", () => {
  const f = fixture();
  for (const side of ["base", "candidate"])
    setLatency(f, "valueHit", side, 2, 5_000_000);
  const result = compareSlowWarm(f);
  assert.equal(result.passed, true);
  assert.equal(result.queries[0].stability.baseP50SpreadPercent, 400);
  assert.match(renderSlowWarm(result), /spread is diagnostic only/);
});
test("p95 uses all samples including slow tail", () => {
  const f = fixture();
  const file = "valueHit-candidate-1.tsv";
  mutateRaw(f, file, (raw) => {
    for (const r of [38, 39, 40])
      raw = changeCell(raw, "measurement", r, "latencyNanos", 2_000_000);
    return raw;
  });
  const result = compareSlowWarm(f);
  assert.equal(result.queries[0].runs[0].candidate.p50, 1_000_000);
  assert.equal(result.queries[0].runs[0].candidate.p95, 2_000_000);
  assert.equal(result.latencyErrors.length, 1);
});
for (const [label, change, pattern] of [
  [
    "warmup digest mismatch",
    (r) => changeCell(r, "warmup", 1, "digest", "0".repeat(64)),
    /digest differs/,
  ],
  [
    "measurement rows mismatch",
    (r) => changeCell(r, "measurement", 1, "rowCount", 0),
    /rowCount differs/,
  ],
  [
    "timeout row",
    (r) => changeCell(r, "measurement", 1, "outcome", "timeout"),
    /outcome differs/,
  ],
  [
    "short phase duration",
    (r) => changeCell(r, "measurement", 40, "phaseElapsedNanos", 9_999_999_999),
    /incomplete measurement/,
  ],
  [
    "noncontiguous round",
    (r) => changeCell(r, "measurement", 2, "round", 3),
    /noncontiguous/,
  ],
  [
    "zero latency",
    (r) => changeCell(r, "measurement", 2, "latencyNanos", 0),
    /invalid timing/,
  ],
  [
    "backwards elapsed",
    (r) => changeCell(r, "measurement", 2, "phaseElapsedNanos", 1),
    /invalid timing/,
  ],
  [
    "oversized heap",
    (r) => changeCell(r, "measurement", 2, "maxHeapBytes", 9 * 1024 ** 3),
    /maximum heap/,
  ],
  [
    "missing warmup",
    (r) =>
      r
        .split("\n")
        .filter((l) => !l.startsWith("warmup\t"))
        .join("\n"),
    /missing complete warmup/,
  ],
  [
    "warmup after measurement",
    (r) => {
      const rows = r.trimEnd().split("\n");
      rows.push(rows[1]);
      return rows.join("\n") + "\n";
    },
    /warmup after measurement/,
  ],
  [
    "extra window after first eligibility",
    (r) => r + r.trimEnd().split("\n").at(-1) + "\n",
    /first eligible/,
  ],
])
  test(label + " fails completeness", () => {
    const f = fixture();
    mutateRaw(f, "valueHit-base-1.tsv", change);
    const result = compareSlowWarm(f);
    assert.equal(result.passed, false);
    assert.equal(result.complete, false);
    assert.match(result.integrityErrors.join("\n"), pattern);
  });
test("minimum measurement calls independent of duration", () => {
  const f = fixture();
  mutateRaw(f, "valueHit-base-1.tsv", (raw) =>
    raw
      .split("\n")
      .filter((line) => !line.startsWith("measurement\t40\t"))
      .map((line) =>
        line.startsWith("measurement\t39\t")
          ? changeCell(
              line,
              "measurement",
              39,
              "phaseElapsedNanos",
              10_000_000_000,
            )
          : line,
      )
      .join("\n"),
  );
  assert.match(
    compareSlowWarm(f).integrityErrors.join("\n"),
    /incomplete measurement/,
  );
});
test("missing and duplicate oracle identities fail", () => {
  const f = fixture();
  f.oracle = f.oracle.split("\n").slice(1).join("\n");
  assert.equal(compareSlowWarm(f).passed, false);
  const g = fixture();
  g.oracle += g.oracle.split("\n")[0] + "\n";
  assert.equal(compareSlowWarm(g).passed, false);
});
test("unknown workload identity in oracle cannot redefine query", () => {
  const f = fixture();
  f.oracle = f.oracle.replace(
    metadata("valueHit").workloadIdentity,
    "0".repeat(64),
  );
  assert.match(
    compareSlowWarm(f).integrityErrors.join("\n"),
    /invalid workloadIdentity/,
  );
});
test("missing query, changed fixture, wrong source and order fail", () => {
  for (const mutate of [
    (f) => f.samples.delete("valueHit-base-1.tsv"),
    (f) => (f.fixtureAfter[0].sha256 = "0".repeat(64)),
    (f) => (f.provenance.baseSha = "bad"),
    (f) => f.provenance.runs.reverse(),
    (f) => (f.provenance.runs[0].jarSha256 = "0".repeat(64)),
    (f) => (f.provenance.runs[1].startedAt = 1),
  ]) {
    const f = fixture();
    mutate(f);
    assert.equal(compareSlowWarm(f).passed, false);
  }
});
test("partial checkpoints never pass; first-pair numeric suspects continue until reverse pair", () => {
  const f = fixture();
  setLatency(f, "valueHit", "candidate", 1, 2_000_000);
  const one = compareSlowWarm({
    ...prefix(structuredClone(f), 2),
    partial: true,
  });
  assert.equal(one.passed, false);
  assert.equal(one.canContinue, true);
  assert.equal(one.latencyErrors.length, 2);
  const two = compareSlowWarm({ ...prefix(f, 4), partial: true });
  assert.equal(two.passed, false);
  assert.equal(two.canContinue, false);
  assert.equal(two.latencyErrors.length, 2);
});
test("partial first-pair integrity fails immediately and final cannot accept prefix", () => {
  const f = prefix(fixture(), 2);
  mutateRaw(f, "valueHit-base-1.tsv", (raw) =>
    changeCell(raw, "warmup", 1, "digest", "0".repeat(64)),
  );
  assert.equal(compareSlowWarm({ ...f, partial: true }).canContinue, false);
  assert.equal(compareSlowWarm(prefix(fixture(), 4)).passed, false);
});
async function onDisk() {
  const f = fixture(),
    directory = fs.mkdtempSync(path.join(os.tmpdir(), "slow-warm-test-"));
  const files = {
    directory,
    jarsDirectory: directory,
    oracle: path.join(directory, "oracle"),
    provenance: path.join(directory, "provenance.json"),
    fixtureBefore: path.join(directory, "before.json"),
    fixtureAfter: path.join(directory, "after.json"),
  };
  f.provenance.fixture = path.join(directory, "shared-fixture");
  fs.writeFileSync(files.oracle, f.oracle);
  for (const [key, value] of [
    ["fixtureBefore", f.fixtureBefore],
    ["fixtureAfter", f.fixtureAfter],
  ]) {
    fs.writeFileSync(files[key], JSON.stringify(value));
    f.provenance[`${key}Sha256`] = hash(fs.readFileSync(files[key]));
  }
  for (const variant of ["base", "candidate"])
    fs.writeFileSync(
      path.join(directory, `${variant}-slow-shapes.jar`),
      `${variant} jar`,
    );
  for (const run of f.provenance.runs) {
    fs.writeFileSync(
      path.join(directory, run.rawFile),
      f.samples.get(run.rawFile),
    );
    const log =
      "SLOW_QUERY_WARM_RUNTIME\tvmVersion=17.0.20.1+1\tmaxHeapBytes=8589934592\tactiveProcessorCount=4\n" +
      `SLOW_QUERY_SHAPE_FIXTURE\tprotocol=private-copy-no-callsite-index-v2\tcorpus=android\tsource=${f.provenance.fixture}\tsnapshot=${directory}/graphite-slow-query-shapes-${run.rawFile}/android\tindexAbsent=true\n`;
    fs.writeFileSync(path.join(directory, run.logFile), log);
    run.logSha256 = hash(log);
  }
  fs.writeFileSync(files.provenance, JSON.stringify(f.provenance));
  return files;
}
test("actual file SHA/runtime/JAR binding enforced through executable CLI", async () => {
  const files = await onDisk();
  try {
    assert.equal((await compareDirectory(files)).passed, true);
    const cli = new URL("./benchmark-slow-query-warm.mjs", import.meta.url);
    const args = [
      "compare",
      "--directory",
      files.directory,
      "--oracle",
      files.oracle,
      "--provenance",
      files.provenance,
      "--fixture-before",
      files.fixtureBefore,
      "--fixture-after",
      files.fixtureAfter,
      "--status",
      path.join(files.directory, "status.json"),
      "--report",
      path.join(files.directory, "report.md"),
    ];
    assert.equal(
      spawnSync(process.execPath, [cli.pathname, ...args]).status,
      0,
    );
    fs.appendFileSync(
      path.join(files.directory, "valueHit-base-1.tsv"),
      "corrupt\n",
    );
    assert.equal(
      spawnSync(process.execPath, [cli.pathname, ...args]).status,
      1,
    );
    assert.equal(
      JSON.parse(fs.readFileSync(path.join(files.directory, "status.json")))
        .passed,
      false,
    );
  } finally {
    fs.rmSync(files.directory, { recursive: true, force: true });
  }
});
test("tampered actual JAR and mismatching runtime fail even valid raw", async () => {
  for (const target of ["jar", "runtime"]) {
    const files = await onDisk();
    try {
      if (target === "jar")
        fs.writeFileSync(
          path.join(files.directory, "base-slow-shapes.jar"),
          "tampered",
        );
      else {
        const p = JSON.parse(fs.readFileSync(files.provenance));
        const run = p.runs[0];
        const log =
          "SLOW_QUERY_WARM_RUNTIME\tvmVersion=17.0.19+7\tmaxHeapBytes=8589934592\tactiveProcessorCount=4\n" +
          fs
            .readFileSync(path.join(files.directory, run.logFile), "utf8")
            .split("\n")
            .find((line) => line.startsWith("SLOW_QUERY_SHAPE_FIXTURE")) +
          "\n";
        fs.writeFileSync(path.join(files.directory, run.logFile), log);
        run.logSha256 = hash(log);
        fs.writeFileSync(files.provenance, JSON.stringify(p));
      }
      assert.equal((await compareDirectory(files)).passed, false);
    } finally {
      fs.rmSync(files.directory, { recursive: true, force: true });
    }
  }
});

test("fast queries retain more than forty measured calls until ten seconds", () => {
  const f = fixture(),
    file = "valueHit-base-1.tsv";
  mutateRaw(f, file, (raw) => {
    const rows = raw.trimEnd().split("\n");
    const signature = rows
      .find((line) => line.startsWith("measurement\t"))
      .split("\t");
    const output = rows.filter((line) => !line.startsWith("measurement\t"));
    for (let i = 1; i <= 80; i++) {
      const cells = [...signature];
      cells[1] = String(i);
      cells[2] = String(i * 125_000_000);
      output.push(cells.join("\t"));
    }
    return output.join("\n") + "\n";
  });
  const result = compareSlowWarm(f);
  assert.equal(result.passed, true);
  assert.equal(result.queries[0].runs[0].base.measurementCalls, 80);
});
test("later query first pair cannot hide an earlier completed query regression", () => {
  const f = fixture();
  setLatency(f, "valueHit", "candidate", 1, 2_000_000);
  const result = compareSlowWarm({ ...prefix(f, 8), partial: true });
  assert.equal(result.canContinue, false);
  assert.equal(result.passed, false);
});
test("partial requires a complete pair and valid query-order prefix", () => {
  for (const count of [0, 1, 3])
    assert.equal(
      compareSlowWarm({ ...prefix(fixture(), count), partial: true })
        .canContinue,
      false,
    );
  const f = prefix(fixture(), 2);
  f.provenance.runs[0].queryName = "valueMiss";
  assert.equal(compareSlowWarm({ ...f, partial: true }).canContinue, false);
});
test("heap marker must equal per-call heap observations", async () => {
  const files = await onDisk();
  try {
    const p = JSON.parse(fs.readFileSync(files.provenance));
    const run = p.runs[0];
    const log =
      "SLOW_QUERY_WARM_RUNTIME\tvmVersion=17.0.20.1+1\tmaxHeapBytes=8053063680\tactiveProcessorCount=4\n" +
      fs
        .readFileSync(path.join(files.directory, run.logFile), "utf8")
        .split("\n")
        .find((line) => line.startsWith("SLOW_QUERY_SHAPE_FIXTURE")) +
      "\n";
    fs.writeFileSync(path.join(files.directory, run.logFile), log);
    run.logSha256 = hash(log);
    fs.writeFileSync(files.provenance, JSON.stringify(p));
    assert.match(
      (await compareDirectory(files)).integrityErrors.join("\n"),
      /heap differs/,
    );
  } finally {
    fs.rmSync(files.directory, { recursive: true, force: true });
  }
});

for (const mode of [
  "missing",
  "duplicate",
  "wrong-source",
  "index-present",
  "reused",
  "not-cleaned",
])
  test(`CLI private fixture ${mode} fails even with resealed log hash`, async () => {
    const files = await onDisk();
    try {
      const p = JSON.parse(fs.readFileSync(files.provenance)),
        run = p.runs[1];
      let log = fs.readFileSync(
        path.join(files.directory, run.logFile),
        "utf8",
      );
      const marker = log
        .split("\n")
        .find((line) => line.startsWith("SLOW_QUERY_SHAPE_FIXTURE"));
      if (mode === "missing") log = log.replace(marker + "\n", "");
      if (mode === "duplicate") log += marker + "\n";
      if (mode === "wrong-source")
        log = log.replace(`source=${p.fixture}`, `source=${p.fixture}-other`);
      if (mode === "index-present")
        log = log.replace("indexAbsent=true", "indexAbsent=false");
      if (mode === "reused")
        log = log.replace(
          `graphite-slow-query-shapes-${run.rawFile}`,
          `graphite-slow-query-shapes-${p.runs[0].rawFile}`,
        );
      if (mode === "not-cleaned")
        fs.mkdirSync(
          path.join(
            files.directory,
            `graphite-slow-query-shapes-${run.rawFile}`,
          ),
        );
      fs.writeFileSync(path.join(files.directory, run.logFile), log);
      run.logSha256 = hash(log);
      fs.writeFileSync(files.provenance, JSON.stringify(p));
      const status = path.join(files.directory, "status.json");
      const result = spawnSync(process.execPath, [
        new URL("./benchmark-slow-query-warm.mjs", import.meta.url).pathname,
        "compare",
        "--directory",
        files.directory,
        "--oracle",
        files.oracle,
        "--provenance",
        files.provenance,
        "--fixture-before",
        files.fixtureBefore,
        "--fixture-after",
        files.fixtureAfter,
        "--status",
        status,
      ]);
      assert.equal(result.status, 1, result.stderr?.toString());
      const evidence = JSON.parse(fs.readFileSync(status));
      assert.equal(evidence.passed, false);
      assert.match(
        evidence.integrityErrors.join("\n"),
        /private fixture|private snapshot/,
      );
    } finally {
      fs.rmSync(files.directory, { recursive: true, force: true });
    }
  });
