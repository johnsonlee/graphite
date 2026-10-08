#!/usr/bin/env node
import fs from 'node:fs';
import { analyze } from './analyzer.js';

const help = `Graphite TypeScript frontend\n\nUsage: graphite build --lang ts <directory|tsconfig.json|source.ts> -o <graph>\nFrontend protocol: graphite-frontend-ts build <input> --output <interchange.json>\n\nUses TypeScript's project configuration and symbol checker; never executes project code.\nSemantic diagnostics are reported; syntax/configuration errors fail the build.\nNode.js 20 or newer is required.\n`;
try {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) process.stdout.write(help);
  else if (args.includes('--version') || args.includes('-V')) console.log(`graphite-frontend-ts ${JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version}`);
  else {
    if (args.shift() !== 'build') throw new Error(help);
    let input: string | undefined;
    let output: string | undefined;
    let positional = false;
    for (let i = 0; i < args.length; i++) {
      const arg = args[i]!;
      if (!positional && arg === '--') { positional = true; continue; }
      if (!positional && (arg === '-o' || arg === '--output')) {
        if (output !== undefined) throw new Error('Output specified more than once.');
        output = args[++i];
        if (!output) throw new Error(`${arg} requires a path.`);
      } else if (!positional && arg.startsWith('--output=')) {
        if (output !== undefined) throw new Error('Output specified more than once.');
        output = arg.slice('--output='.length);
      } else if (!positional && arg.startsWith('-')) throw new Error(`Unsupported TypeScript frontend option: ${arg}`);
      else if (input !== undefined) throw new Error('Expected exactly one input project.');
      else input = arg;
    }
    if (!input || !output) throw new Error(help);
    const graph = analyze(input, message => console.error(`Warning: ${message}`));
    fs.writeFileSync(output, JSON.stringify(graph), { flag: 'wx' });
    console.error(`Analyzed ${graph.methods.length} methods, ${graph.nodes.length} nodes, ${graph.edges.length} edges.`);
  }
} catch (error) {
  console.error(`Error: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
