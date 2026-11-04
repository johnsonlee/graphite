// npm pack normalizes archive timestamps and bundles the compiler with its declaration files.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const { name, version, description, type, bin, engines, dependencies } = JSON.parse(
  fs.readFileSync(path.join(root, 'package.json'), 'utf8'),
);
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'graphite-frontend-pack-'));
try {
  const staging = path.join(work, 'package');
  fs.mkdirSync(staging);
  const manifest = {
    name, version: process.env.RELEASE_VERSION ?? version, description, private: true, type, bin, engines,
    files: ['dist'], dependencies, bundleDependencies: Object.keys(dependencies),
  };
  fs.writeFileSync(path.join(staging, 'package.json'), JSON.stringify(manifest, null, 2) + '\n');
  fs.copyFileSync(path.resolve(root, '../../LICENSE'), path.join(staging, 'LICENSE'));
  fs.cpSync(path.join(root, 'dist'), path.join(staging, 'dist'), { recursive: true });
  // npm pack selects the production dependency tree, including hoisted transitive dependencies.
  fs.cpSync(path.join(root, 'node_modules'), path.join(staging, 'node_modules'), { recursive: true });

  const output = execFileSync(process.execPath, [process.env.npm_execpath, 'pack',
    '--offline', '--ignore-scripts', '--json', '--pack-destination', work], { cwd: staging, encoding: 'utf8' });
  const [{ filename }] = JSON.parse(output);
  const bytes = fs.readFileSync(path.join(work, filename));
  const digest = createHash('sha256').update(bytes).digest('hex');
  const asset = 'graphite-frontend-js.tgz';
  const assets = path.join(root, 'release-assets');
  fs.mkdirSync(assets, { recursive: true });
  fs.writeFileSync(path.join(assets, asset), bytes);
  fs.writeFileSync(path.join(assets, asset + '.sha256'), `${digest}  ${asset}\n`);
  console.log(`Packaged ${asset} for ${manifest.version} (${bytes.length} bytes, sha256 ${digest})`);
} finally {
  fs.rmSync(work, { recursive: true, force: true });
}
