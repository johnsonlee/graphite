#!/usr/bin/env node
import fs from 'node:fs';
import { distribution } from './distribution.js';
import { analyze, UsageError } from './analyzer.js';
import { writeInterchange } from './interchange.js';

const help = `Graphite JavaScript artifact frontend\n\nUsage: graphite build --lang js <artifact-directory|output.js|output.mjs|output.cjs> -o <graph>\nFrontend protocol: graphite-frontend-js build <input> --output <interchange.json>\n\nCompile your JS/TS project first, then pass its .next, dist or build output.\nUses TypeScript's parser/checker; never executes project code or build scripts.\nThe ts and es language selectors are aliases for compiled-JavaScript analysis.\n${distribution ? 'Standalone executable (includes runtime and compiler). Use --licenses for third-party notices.' : 'Node.js 20 or newer is required for the source frontend.'}\n`;
function printInfo(arg: string | undefined): boolean {
  if (arg === '--help' || arg === '-h') process.stdout.write(help);
  else if (arg === '--version' || arg === '-V') console.log(`graphite ${distribution?.version ?? JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version}`);
  else if (arg === '--licenses' && distribution) process.stdout.write(distribution.notices);
  else return false;
  return true;
}

function main(args: string[]): void {
  if (printInfo(args[0])) return;
  const command = args.shift();
  if (command !== 'build') throw new UsageError(command ? `Unsupported frontend command: ${command}` : 'Expected the build command.');
  let input: string | undefined;
  let output: string | undefined;
  let positional = false;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]!;
    if (!positional && printInfo(arg)) return;
    if (!positional && arg === '--') { positional = true; continue; }
    if (!positional && (arg === '-o' || arg === '--output')) {
      if (output !== undefined) throw new UsageError('Output specified more than once.');
      output = args[++i];
      if (!output || output === '--') throw new UsageError(`${arg} requires a path.`);
    } else if (!positional && arg.startsWith('--output=')) {
      if (output !== undefined) throw new UsageError('Output specified more than once.');
      output = arg.slice('--output='.length);
      if (!output) throw new UsageError('--output requires a path.');
    } else if (!positional && arg.startsWith('-')) throw new UsageError(`Unsupported JavaScript frontend option: ${arg}`);
    else if (input !== undefined) throw new UsageError('Expected exactly one emitted JavaScript input.');
    else input = arg;
  }
  if (!input) throw new UsageError('Expected one emitted JavaScript input.');
  if (!output) throw new UsageError('Output is required (-o or --output).');
  const graph = analyze(input);
  writeInterchange(output, graph);
  console.error(`Analyzed ${graph.methods.length} methods, ${graph.nodes.length} nodes, ${graph.edges.length} edges.`);
}

try {
  main(process.argv.slice(2));
} catch (error) {
  console.error(`Error: ${error instanceof Error ? error.message : String(error)}`);
  if (error instanceof UsageError) process.stderr.write(`\n${help}`);
  process.exitCode = error instanceof UsageError ? 2 : 1;
}
