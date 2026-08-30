#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { BENCHMARK_COMPONENTS, BENCHMARK_COVERAGE_DOMAINS } from "./benchmark-gate.mjs";

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function safeUrl(value) {
    try {
        const url = new URL(value);
        return ["https:", "http:"].includes(url.protocol) ? url.href : "#";
    } catch {
        return "#";
    }
}

function renderInline(value) {
    let rendered = escapeHtml(value);
    rendered = rendered.replace(/`([^`]+)`/g, "<code>$1</code>");
    rendered = rendered.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    rendered = rendered.replace(/\[([^\]]+)]\(([^)]+)\)/g, (_, label, url) =>
        `<a href="${escapeHtml(safeUrl(url))}" rel="noreferrer">${label}</a>`
    );
    return rendered;
}

function tableCells(line) {
    return line.trim().replace(/^\||\|$/g, "").split("|").map((cell) => cell.trim());
}

export function renderMarkdown(markdown) {
    const lines = String(markdown ?? "")
        .replace(/<!--[^]*?-->/g, "")
        .split(/\r?\n/);
    const output = [];
    let index = 0;
    while (index < lines.length) {
        const line = lines[index];
        if (line.trim() === "") {
            index++;
            continue;
        }
        if (line.startsWith("```")) {
            const code = [];
            index++;
            while (index < lines.length && !lines[index].startsWith("```")) code.push(lines[index++]);
            index++;
            output.push(`<pre><code>${escapeHtml(code.join("\n"))}</code></pre>`);
            continue;
        }
        const heading = line.match(/^(#{1,6})\s+(.+)$/);
        if (heading !== null) {
            const level = Math.min(6, heading[1].length + 1);
            const id = heading[2].toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
            output.push(`<h${level} id="${escapeHtml(id)}">${renderInline(heading[2])}</h${level}>`);
            index++;
            continue;
        }
        if (/^\|.*\|$/.test(line) && /^\|?[\s:|-]+\|?$/.test(lines[index + 1] ?? "")) {
            const headers = tableCells(line);
            index += 2;
            const rows = [];
            while (index < lines.length && /^\|.*\|$/.test(lines[index])) {
                rows.push(tableCells(lines[index++]));
            }
            output.push("<div class=\"table-scroll\"><table><thead><tr>");
            output.push(...headers.map((cell) => `<th>${renderInline(cell)}</th>`));
            output.push("</tr></thead><tbody>");
            for (const cells of rows) {
                const text = cells.join(" ").toLowerCase();
                const attention = /\b(fail|noise|info|missing)\b/.test(text) ? "true" : "false";
                output.push(`<tr data-search="${escapeHtml(text)}" data-attention="${attention}">`);
                output.push(...cells.map((cell) => `<td>${renderInline(cell)}</td>`));
                output.push("</tr>");
            }
            output.push("</tbody></table></div>");
            continue;
        }
        if (/^- /.test(line)) {
            const items = [];
            while (index < lines.length && /^- /.test(lines[index])) {
                items.push(lines[index++].slice(2));
            }
            output.push(`<ul>${items.map((item) => `<li>${renderInline(item)}</li>`).join("")}</ul>`);
            continue;
        }
        const paragraph = [line.trim()];
        index++;
        while (index < lines.length && lines[index].trim() !== "" &&
            !/^(#{1,6})\s+/.test(lines[index]) && !/^\|.*\|$/.test(lines[index]) &&
            !/^- /.test(lines[index]) && !lines[index].startsWith("```")
        ) {
            paragraph.push(lines[index++].trim());
        }
        output.push(`<p>${renderInline(paragraph.join(" "))}</p>`);
    }
    return output.join("\n");
}

function shortBenchmarkName(name) {
    return String(name).replace(/^io\.johnsonlee\.graphite\./, "");
}

export function snapshotKey(result) {
    const parameters = Object.entries(result.params ?? {})
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([name, value]) => `${name}=${value}`)
        .join(",");
    const metric = result.primaryMetric ?? {};
    return [result.benchmark, parameters, result.mode, metric.scoreUnit].join("|");
}

export function updateBenchmarkHistory(history, current, maximumEntries = 90, maximumAgeDays = 180) {
    if (!Array.isArray(history)) throw new Error("Benchmark history must be an array");
    const now = Date.parse(current.generatedAt);
    if (!Number.isFinite(now)) throw new Error("Current benchmark timestamp is invalid");
    const accepted = [];
    for (const entry of history) {
        if (entry?.schemaVersion !== 1 || entry.repository !== current.repository ||
            typeof entry.sha !== "string" || !Array.isArray(entry.snapshot) ||
            !Number.isFinite(Date.parse(entry.generatedAt))
        ) {
            throw new Error("Published benchmark history is incompatible or malformed");
        }
        if (entry.sha !== current.sha) accepted.push(entry);
    }
    accepted.push(current);
    const cutoff = now - maximumAgeDays * 24 * 60 * 60 * 1_000;
    return accepted
        .filter((entry) => Date.parse(entry.generatedAt) >= cutoff)
        .sort((left, right) => Date.parse(left.generatedAt) - Date.parse(right.generatedAt))
        .slice(-maximumEntries);
}

function jsonForHtml(value) {
    return JSON.stringify(value).replace(/[<>&]/g, (character) => ({
        "<": "\\u003c",
        ">": "\\u003e",
        "&": "\\u0026"
    })[character]);
}

function renderSnapshot(snapshot, previousSnapshot = []) {
    if (!Array.isArray(snapshot) || snapshot.length === 0) {
        return `<div class="empty"><strong>Fresh snapshot unavailable</strong><span>No JMH rows were produced.</span></div>`;
    }
    const valuesByUnit = new Map();
    for (const result of snapshot) {
        const score = Number(result.primaryMetric?.score);
        const unit = String(result.primaryMetric?.scoreUnit ?? "unknown");
        if (Number.isFinite(score)) valuesByUnit.set(unit, Math.max(valuesByUnit.get(unit) ?? 0, score));
    }
    const previousByKey = new Map(previousSnapshot.map((result) => [snapshotKey(result), result]));
    const rows = snapshot.map((result) => {
        const score = Number(result.primaryMetric?.score);
        const unit = String(result.primaryMetric?.scoreUnit ?? "unknown");
        const maximum = valuesByUnit.get(unit) ?? 1;
        const width = Number.isFinite(score) ? Math.max(2, Math.min(100, score / maximum * 100)) : 0;
        const confidence = result.primaryMetric?.scoreConfidence;
        const interval = Array.isArray(confidence) && confidence.length === 2
            ? `${Number(confidence[0]).toPrecision(4)} – ${Number(confidence[1]).toPrecision(4)}`
            : "n/a";
        const name = shortBenchmarkName(result.benchmark);
        const previousScore = Number(previousByKey.get(snapshotKey(result))?.primaryMetric?.score);
        const delta = Number.isFinite(score) && Number.isFinite(previousScore) && previousScore > 0
            ? (score / previousScore - 1) * 100
            : null;
        const deltaMarkup = delta === null
            ? "<span class=\"muted\">new series</span>"
            : `<span class="${delta > 0 ? "delta-up" : "delta-down"}">${delta >= 0 ? "+" : ""}${delta.toFixed(1)}%</span>`;
        return `<tr data-search="${escapeHtml(name.toLowerCase())}" data-attention="false">
            <td><code>${escapeHtml(name)}</code></td>
            <td>${escapeHtml(result.mode ?? "unknown")}</td>
            <td class="number">${Number.isFinite(score) ? escapeHtml(score.toPrecision(5)) : "n/a"} ${escapeHtml(unit)}</td>
            <td class="number">${escapeHtml(interval)}</td>
            <td class="number">${deltaMarkup}</td>
            <td><span class="bar"><i style="width:${width.toFixed(1)}%"></i></span></td>
        </tr>`;
    }).join("\n");
    return `<div class="table-scroll"><table><thead><tr><th>Benchmark</th><th>Mode</th><th>Score</th><th>99.9% confidence</th><th>vs previous main snapshot</th><th>Relative scale by unit</th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

function renderCoverage() {
    const componentByName = new Map(BENCHMARK_COMPONENTS.map((component) => [component.name, component]));
    return BENCHMARK_COVERAGE_DOMAINS.map((domain) => {
        const gates = domain.components.map((name) => {
            const component = componentByName.get(name);
            const icon = component.coverage === "complete" ? "✅" : "⚠️";
            return `<li><span>${icon} <code>${escapeHtml(name)}</code></span><small>${escapeHtml(component.gap)}</small></li>`;
        }).join("");
        const implemented = domain.components.length === 0
            ? "<p class=\"muted\">No implemented component gate.</p>"
            : `<ul class="gate-list">${gates}</ul>`;
        return `<article class="coverage-card"><h3>${escapeHtml(domain.name)}</h3>${implemented}<details><summary>Uncovered scope</summary><p>${domain.missing.map((name) => `<code>${escapeHtml(name)}</code>`).join(" · ")}</p></details></article>`;
    }).join("\n");
}

export function buildBenchmarkPage({
    reportMarkdown = "",
    status = null,
    snapshot = [],
    commitSha,
    branch = "main",
    runUrl,
    sourceRunUrl = "",
    sourcePr = "",
    repository = "johnsonlee/graphite",
    history = [],
    generatedAt = new Date().toISOString()
}) {
    const currentHistoryEntry = {
        schemaVersion: 1,
        repository,
        sha: commitSha,
        generatedAt,
        runUrl,
        snapshot
    };
    const updatedHistory = updateBenchmarkHistory(history, currentHistoryEntry);
    const previousSnapshot = updatedHistory.length > 1
        ? updatedHistory[updatedHistory.length - 2].snapshot
        : [];
    const verdict = status?.passed === true ? "PASS" : status?.passed === false ? "FAIL" : "UNAVAILABLE";
    const verdictClass = verdict === "PASS" ? "good" : verdict === "FAIL" ? "bad" : "unavailable";
    const passedCount = verdict === "PASS" ? BENCHMARK_COMPONENTS.length : "—";
    const report = reportMarkdown.trim().length > 0
        ? renderMarkdown(reportMarkdown)
        : `<div class="empty"><strong>Paired PR evidence unavailable</strong><span>This main commit has no downloadable successful PR benchmark artifact. The fresh snapshot above still belongs to this exact commit.</span></div>`;
    const sourceLink = sourceRunUrl
        ? `<a class="button secondary" href="${escapeHtml(safeUrl(sourceRunUrl))}">Open paired benchmark run</a>`
        : "";
    const sourceLabel = sourcePr ? `PR #${escapeHtml(sourcePr)}` : "No associated PR artifact";
    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark light"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'"><title>Graphite Benchmark Observatory</title>
<style>
:root{--bg:#07111f;--panel:#0e1b2c;--panel2:#14243a;--text:#eef6ff;--muted:#98abc2;--line:#29405d;--accent:#66e3c4;--accent2:#8bb8ff;--good:#53d18b;--bad:#ff6b7d;--warn:#f4c95d;--shadow:0 22px 60px #0006;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:radial-gradient(circle at 15% 0,#16345c 0,transparent 34rem),radial-gradient(circle at 95% 10%,#123f3d 0,transparent 30rem),var(--bg);color:var(--text);line-height:1.55}a{color:var(--accent2)}code{font-family:"SFMono-Regular",Consolas,monospace;background:#ffffff0d;border:1px solid #ffffff12;border-radius:.4rem;padding:.08rem .32rem}p code,li code,td code,details code{overflow-wrap:anywhere;white-space:normal}main{width:min(1480px,calc(100% - 2rem));margin:auto;padding:2rem 0 5rem}.hero{position:relative;overflow:hidden;padding:clamp(1.5rem,4vw,4rem);border:1px solid #ffffff1c;border-radius:1.8rem;background:linear-gradient(135deg,#142945e8,#0b1a2be8);box-shadow:var(--shadow)}.hero:after{content:"";position:absolute;width:18rem;height:18rem;border-radius:50%;right:-5rem;top:-8rem;background:linear-gradient(135deg,var(--accent2),var(--accent));filter:blur(8px);opacity:.24}.eyebrow{margin:0;color:var(--accent);font-size:.78rem;font-weight:800;letter-spacing:.16em;text-transform:uppercase}.hero h1{max-width:850px;margin:.35rem 0 1rem;font-size:clamp(2.3rem,6vw,5.4rem);line-height:.98;letter-spacing:-.055em}.hero-copy{max-width:820px;color:var(--muted);font-size:1.05rem}.meta,.actions,.toolbar{display:flex;gap:.7rem;align-items:center;flex-wrap:wrap}.meta{margin-top:1.4rem}.pill{display:inline-flex;padding:.42rem .72rem;border:1px solid var(--line);border-radius:999px;background:#ffffff09;color:var(--muted);font-size:.84rem}.pill.good{color:var(--good);border-color:#53d18b66}.pill.bad{color:var(--bad);border-color:#ff6b7d66}.pill.unavailable{color:var(--warn);border-color:#f4c95d66}.button{display:inline-flex;align-items:center;text-decoration:none;padding:.68rem .9rem;border-radius:.75rem;background:var(--accent);color:#052019;font-weight:800}.button.secondary{background:#ffffff0d;color:var(--text);border:1px solid var(--line)}.actions{margin-top:1.4rem}.kpis{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:1rem;margin:1rem 0}.kpi,.coverage-card,.section{min-width:0;background:linear-gradient(145deg,#13233ae8,#0b1828e8);border:1px solid #ffffff17;border-radius:1.2rem;box-shadow:0 12px 35px #0003}.kpi{padding:1.15rem}.kpi strong{display:block;font-size:1.65rem;letter-spacing:-.04em}.kpi span{color:var(--muted);font-size:.82rem}.section{margin-top:1rem;padding:clamp(1rem,2.5vw,2rem)}.section-head{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin-bottom:1rem}.section h2{margin:0;font-size:clamp(1.35rem,3vw,2.2rem)}.section h3,.section h4,.section h5,.section h6{scroll-margin-top:1rem}.muted,.section-head p{color:var(--muted);margin:.25rem 0}.coverage-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem}.coverage-card{padding:1.1rem}.coverage-card h3{margin:0 0 .7rem}.gate-list{list-style:none;padding:0;margin:0}.gate-list li{display:grid;gap:.22rem;padding:.6rem 0;border-top:1px solid var(--line)}.gate-list small{color:var(--muted)}details{margin-top:.65rem}summary{cursor:pointer;color:var(--accent2);font-weight:700}.toolbar{position:sticky;top:.6rem;z-index:5;padding:.65rem;margin:.5rem 0 1rem;border:1px solid var(--line);border-radius:.9rem;background:#081525e8;backdrop-filter:blur(14px)}input{min-width:min(100%,24rem);flex:1;border:1px solid var(--line);border-radius:.65rem;padding:.65rem .8rem;background:#07111f;color:var(--text)}button{border:1px solid var(--line);border-radius:.65rem;padding:.65rem .8rem;background:var(--panel2);color:var(--text);cursor:pointer}.table-scroll{max-width:100%;overflow:auto;border:1px solid var(--line);border-radius:.8rem;margin:1rem 0}table{width:100%;border-collapse:collapse;font-size:.86rem;background:#07111f80}th,td{padding:.68rem .72rem;text-align:left;border-bottom:1px solid var(--line);vertical-align:top}th{position:sticky;top:0;background:#172941;color:#bfd0e4;white-space:nowrap}td.number{font-variant-numeric:tabular-nums;white-space:nowrap}tr:hover{background:#ffffff08}.bar{display:block;width:8rem;height:.42rem;border-radius:999px;background:#ffffff12;overflow:hidden}.bar i{display:block;height:100%;border-radius:inherit;background:linear-gradient(90deg,var(--accent),var(--accent2))}.delta-up{color:var(--bad)}.delta-down{color:var(--good)}.empty{display:grid;gap:.25rem;padding:1.2rem;border:1px dashed #f4c95d66;border-radius:.85rem;color:var(--warn)}.empty span{color:var(--muted)}pre{max-width:100%;overflow:auto;padding:1rem;border:1px solid var(--line);border-radius:.8rem;background:#050c16}footer{padding:2rem;color:var(--muted);text-align:center}@media(max-width:900px){.kpis{grid-template-columns:repeat(2,1fr)}.coverage-grid{grid-template-columns:1fr}.section-head{align-items:start;flex-direction:column}}@media(max-width:520px){main{width:min(100% - 1rem,1480px)}.kpis{grid-template-columns:1fr}.hero{border-radius:1.2rem}.bar{width:5rem}}
</style></head><body><main>
<header class="hero"><p class="eyebrow">Graphite performance telemetry</p><h1>Benchmark Observatory</h1><p class="hero-copy">A fresh absolute JMH snapshot for the exact main commit, paired with the authoritative PR regression evidence and its explicit coverage boundaries.</p><div class="meta"><span class="pill ${verdictClass}">${verdict}</span><span class="pill"><code>${escapeHtml(String(commitSha).slice(0, 12))}</code></span><span class="pill">${escapeHtml(branch)}</span><span class="pill">${escapeHtml(sourceLabel)}</span></div><div class="actions"><a class="button" href="${escapeHtml(safeUrl(runUrl))}">Open Pages workflow</a>${sourceLink}</div></header>
<section class="kpis"><article class="kpi"><strong>${escapeHtml(passedCount)}/${BENCHMARK_COMPONENTS.length}</strong><span>paired component reports passed</span></article><article class="kpi"><strong>${snapshot.length}</strong><span>fresh main JMH measurements</span></article><article class="kpi"><strong>${updatedHistory.length}</strong><span>main snapshots in the rolling history</span></article><article class="kpi"><strong>Nightly</strong><span>known-bad historical proof cadence</span></article></section>
<section class="section"><div class="section-head"><div><p class="eyebrow">Exact main commit</p><h2>Fresh method-level snapshot</h2><p>Absolute scores and cross-run deltas are informational on hosted runners. Paired blocking decisions live in the evidence section.</p></div></div>${renderSnapshot(snapshot, previousSnapshot)}</section>
<section class="section"><div class="section-head"><div><p class="eyebrow">PR #104 coverage model</p><h2>Coverage map and known gaps</h2><p>✅ means no gate-specific gap is identified. ⚠️ means implemented but intentionally incomplete. Uncovered scope does not change this run's verdict.</p></div></div><div class="coverage-grid">${renderCoverage()}</div></section>
<section class="section" id="evidence"><div class="section-head"><div><p class="eyebrow">Paired regression evidence</p><h2>Detailed benchmark report</h2></div></div><div class="toolbar"><input id="search" type="search" placeholder="Filter benchmark rows…" aria-label="Filter benchmark rows"><button id="attention" type="button" aria-pressed="false">Show attention rows</button><button id="expand" type="button">Expand details</button></div><div id="report">${report}</div></section>
<footer>Generated ${escapeHtml(generatedAt)} · evidence is scoped, reproducible, and intentionally explicit about what it does not prove.</footer>
</main><script id="benchmark-history" type="application/json">${jsonForHtml(updatedHistory)}</script><script>
const search=document.querySelector('#search');const attention=document.querySelector('#attention');let attentionOnly=false;
function filterRows(){const q=search.value.trim().toLowerCase();document.querySelectorAll('tbody tr').forEach(row=>{const text=(row.dataset.search||row.textContent).toLowerCase();const keepText=!q||text.includes(q);const keepAttention=!attentionOnly||row.dataset.attention==='true';row.hidden=!(keepText&&keepAttention)})}
search.addEventListener('input',filterRows);attention.addEventListener('click',()=>{attentionOnly=!attentionOnly;attention.setAttribute('aria-pressed',String(attentionOnly));attention.textContent=attentionOnly?'Show all rows':'Show attention rows';filterRows()});document.querySelector('#expand').addEventListener('click',()=>document.querySelectorAll('details').forEach(item=>item.open=true));
</script></body></html>`;
}

function parseArgs(argv) {
    const args = {};
    for (let index = 0; index < argv.length; index += 2) {
        const key = argv[index];
        if (!key?.startsWith("--") || argv[index + 1] === undefined) throw new Error(`Invalid argument ${key}`);
        args[key.slice(2)] = argv[index + 1];
    }
    return args;
}

function readJsonIfPresent(file, fallback) {
    return file && fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : fallback;
}

function main() {
    const [command, ...rest] = process.argv.slice(2);
    const args = parseArgs(rest);
    if (command === "extract-history") {
        if (!args.input || !args.output) throw new Error("extract-history requires --input and --output");
        const html = fs.readFileSync(args.input, "utf8");
        const match = html.match(/<script id="benchmark-history" type="application\/json">([^]*?)<\/script>/);
        if (match === null) throw new Error("Published benchmark page has no compatible history payload");
        const history = JSON.parse(match[1]);
        if (!Array.isArray(history)) throw new Error("Published benchmark history is not an array");
        fs.writeFileSync(args.output, `${JSON.stringify(history, null, 2)}\n`);
        return;
    }
    if (command !== "build") throw new Error(`Unknown command: ${command ?? "<none>"}`);
    if (!args.output || !args.sha || !args["run-url"]) throw new Error("build requires --output, --sha, and --run-url");
    const reportMarkdown = args.report && fs.existsSync(args.report) ? fs.readFileSync(args.report, "utf8") : "";
    const html = buildBenchmarkPage({
        reportMarkdown,
        status: readJsonIfPresent(args.status, null),
        snapshot: readJsonIfPresent(args.snapshot, []),
        commitSha: args.sha,
        branch: args.branch ?? "main",
        runUrl: args["run-url"],
        sourceRunUrl: args["source-run-url"] ?? "",
        sourcePr: args["source-pr"] ?? "",
        repository: args.repository ?? "johnsonlee/graphite",
        history: readJsonIfPresent(args.history, []),
        generatedAt: args["generated-at"] ?? new Date().toISOString()
    });
    fs.mkdirSync(args.output, { recursive: true });
    fs.writeFileSync(path.join(args.output, "index.html"), html);
    fs.writeFileSync(path.join(args.output, "404.html"), html);
    fs.writeFileSync(path.join(args.output, ".nojekyll"), "");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        main();
    } catch (error) {
        console.error(error instanceof Error ? error.stack : error);
        process.exitCode = 1;
    }
}
