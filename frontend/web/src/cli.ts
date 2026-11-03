#!/usr/bin/env node
import fs from 'node:fs';
import { analyze } from './analyzer.js';
import { writeInterchange } from './interchange.js';

const help = `Graphite JavaScript artifact frontend\n\nUsage: graphite build --lang js <artifact-directory|output.js|output.mjs|output.cjs> -o <graph>\nFrontend protocol: graphite-frontend-js build <input> --output <interchange.json>\n\nCompile your JS/TS project first, then pass its .next, dist or build output.\nUses TypeScript's parser/checker; never executes project code or build scripts.\nThe ts language selector is an alias for compiled-JavaScript analysis.\nNode.js 20 or newer is required.\n`;
function printInfo(arg: string | undefined): boolean {
  if (arg === '--help' || arg === '-h') process.stdout.write(help);
  else if (arg === '--version' || arg === '-V') console.log(`graphite ${JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version}`);
  else return false;
  return true;
}

function main(args: string[]): void {
  if (printInfo(args[0])) return;
  if (args.shift() !== 'build') throw new Error(help);
  let input: string | undefined;
  let output: string | undefined;
  let positional = false;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]!;
    if (!positional && printInfo(arg)) return;
    if (!positional && arg === '--') { positional = true; continue; }
    if (!positional && (arg === '-o' || arg === '--output')) {
      if (output !== undefined) throw new Error('Output specified more than once.');
      output = args[++i];
      if (!output) throw new Error(`${arg} requires a path.`);
    } else if (!positional && arg.startsWith('--output=')) {
      if (output !== undefined) throw new Error('Output specified more than once.');
      output = arg.slice('--output='.length);
    } else if (!positional && arg.startsWith('-')) throw new Error(`Unsupported JavaScript frontend option: ${arg}`);
    else if (input !== undefined) throw new Error('Expected exactly one input project.');
    else input = arg;
  }
  if (!input || !output) throw new Error(help);
  const graph = analyze(input);
  writeInterchange(output, graph);
  console.error(`Analyzed ${graph.methods.length} methods, ${graph.nodes.length} nodes, ${graph.edges.length} edges.`);
}

try {
  main(process.argv.slice(2));
} catch (error) {
  console.error(`Error: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
