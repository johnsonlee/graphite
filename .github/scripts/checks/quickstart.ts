/** Run the documented quickstart against the built CLI and assert its query results. */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const graphite = resolve(process.argv[2] ?? "target/release/graphite");
const temporary = mkdtempSync(join(tmpdir(), "graphite-quickstart-check-"));
try {
  const output = execFileSync("bash", ["examples/quickstart/run.sh"], {
    encoding: "utf8",
    env: { ...process.env, GRAPHITE_BIN: graphite, TMPDIR: temporary },
    timeout: 60_000,
  });
  process.stdout.write(output);
  const constantHeading = "Which constant reaches enableFeature?\n";
  const callerHeading = "Who calls enableFeature?\n";
  const exploreHeading = "Explore the saved graph:\n";
  function resultBetween(start: string, end: string): unknown {
    const startAt = output.indexOf(start);
    assert.ok(startAt >= 0, `Missing quickstart output: ${start}`);
    const endAt = output.indexOf(end, startAt + start.length);
    assert.ok(endAt >= 0, `Missing quickstart output: ${end}`);
    return JSON.parse(output.slice(startAt + start.length, endAt).trim());
  }
  assert.deepEqual(resultBetween(constantHeading, callerHeading), {
    columns: ["flag", "method"],
    rows: [{ flag: 42, method: "enableFeature" }],
    rowCount: 1,
  });
  assert.deepEqual(resultBetween(callerHeading, exploreHeading), {
    columns: ["caller"],
    rows: [{ caller: "demo.Checkout.startCheckout()" }],
    rowCount: 1,
  });
  const demoDirectory = /^Demo directory: (.+)$/m.exec(output)?.[1];
  assert.ok(demoDirectory, "Missing demo directory");
  assert.ok(statSync(join(demoDirectory, "checkout-graph.graphite")).isFile());
  assert.ok(!existsSync(join(demoDirectory, "checkout-graph")));
  assert.match(output, /serve --id demo .*checkout-graph\.graphite --port 8080/);
  execFileSync(graphite, ["verify", join(demoDirectory, "checkout-graph.graphite")], {
    stdio: "inherit",
    timeout: 30_000,
  });
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
