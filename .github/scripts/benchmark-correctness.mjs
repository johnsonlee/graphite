#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const require = (condition, message) => { if (!condition) throw new Error(message); };
export function checkMarkers(text, expected) {
    const actual = text.split(/\r?\n/).filter(line => line.startsWith('CORRECTNESS_PASS\t')).map(line => line.slice(17));
    require(actual.length === expected.length && new Set(actual).size === actual.length &&
        [...actual].sort().join('\n') === [...expected].sort().join('\n'), 'Missing, duplicate or unexpected correctness case');
    return actual;
}
export function correctnessReport(component, logs, expected) {
    require(logs.length === 2 && expected.length > 0, 'Two revisions and explicit cases required');
    const evidence = logs.map(file => {
        const bytes = fs.readFileSync(file);
        return {path: path.resolve(file), sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
            cases: checkMarkers(bytes.toString('utf8'), expected)};
    });
    return {passed: true, errors: [], component, scope: 'correctness-only', performanceAcceptance: false, evidence};
}
function main(argv) {
    const [component, output, base, candidate, ...expected] = argv;
    require(component && output && base && candidate, 'component output-prefix base-log candidate-log expected-case...');
    let result;
    try { result = correctnessReport(component, [base,candidate], expected); }
    catch (e) { result = {passed:false,errors:[e.message],component,scope:'correctness-only',performanceAcceptance:false}; }
    fs.mkdirSync(path.dirname(output), {recursive:true});
    fs.writeFileSync(output+'-status.json', JSON.stringify(result,null,2)+'\n');
    fs.writeFileSync(output+'-report.md', `### ${component}: correctness only\n\n${result.passed?'PASS':'FAIL'}. No single-graph performance samples or acceptance claim.\n`+result.errors.map(e=>`\n- ${e}`).join(''));
    if (!result.passed) process.exitCode=1;
}
if (process.argv[1] && fs.realpathSync(process.argv[1])===fs.realpathSync(fileURLToPath(import.meta.url))) main(process.argv.slice(2));
