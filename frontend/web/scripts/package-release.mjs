// Compile the frontend, TypeScript and its filesystem-backed standard library into one executable.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const target = process.argv[2];
const targets = new Map([
  ['aarch64-apple-darwin', 'bun-darwin-arm64'],
  ['x86_64-apple-darwin', 'bun-darwin-x64-baseline'],
  ['aarch64-unknown-linux-gnu', 'bun-linux-arm64'],
  ['x86_64-unknown-linux-gnu', 'bun-linux-x64-baseline'],
  ['aarch64-unknown-linux-musl', 'bun-linux-arm64-musl'],
  ['x86_64-unknown-linux-musl', 'bun-linux-x64-musl-baseline'],
]);
if (!targets.has(target)) throw new Error(`Expected one target: ${[...targets.keys()].join(', ')}`);
const { packageManager } = await Bun.file(path.resolve(root, '../../.github/scripts/package.json')).json();
if (packageManager !== `bun@${Bun.version}`) throw new Error(`This build requires ${packageManager}`);
const version = process.env.RELEASE_VERSION ?? (await Bun.file(path.join(root, 'package.json')).json()).version;
const compiler = path.join(root, 'node_modules/typescript');
const libraries = fs.readdirSync(path.join(compiler, 'lib')).filter(name => /^lib(?:\..+)?\.d\.ts$/.test(name)).sort();
const licenseFiles = [
  ['Graphite', path.resolve(root, '../../LICENSE')],
  ['TypeScript', path.join(compiler, 'LICENSE.txt')],
  ['TypeScript third-party notices', path.join(compiler, 'ThirdPartyNoticeText.txt')],
  [`Bun ${Bun.version} (https://github.com/oven-sh/bun/tree/bun-v${Bun.version})`,
    path.join(root, 'scripts/licenses', `bun-${Bun.version}.md`)],
];
const notices = licenseFiles.map(([name, file]) => name + '\n\n' + fs.readFileSync(file, 'utf8')).join('\n\n');
const asset = `graphite-frontend-js-${target}`;
const output = path.join(root, 'release-assets', asset);
fs.mkdirSync(path.dirname(output), { recursive: true });
await Bun.build({
  entrypoints: [path.join(root, 'src/cli.ts')],
  naming: { asset: '[name].[ext]' },
  // TypeScript's CommonJS helpers must resolve inside the executable on the user's machine.
  define: { __filename: 'import.meta.filename', __dirname: 'import.meta.dirname' },
  compile: {
    target: targets.get(target), outfile: output,
    autoloadBunfig: false, autoloadDotenv: false, autoloadPackageJson: false, autoloadTsconfig: false,
  },
  plugins: [{
    name: 'standalone-distribution',
    setup(build) {
      build.onLoad({ filter: /[/\\]src[/\\]distribution\.ts$/ }, () => ({
        loader: 'js',
        contents: [
          ...libraries.map((name, i) => `import lib${i} from 'typescript/lib/${name}' with { type: 'file' };`),
          `export const distribution = {version: ${JSON.stringify(version)}, notices: ${JSON.stringify(notices)},`,
          `libraries: [${libraries.map((_, i) => 'lib' + i).join(',')}]};`,
        ].join('\n'),
      }));
    },
  }],
});
// Changing Bun's Mach-O invalidates the upstream signature. Sign the final bytes
// on the macOS release runner before computing the digest used by the installer.
if (target.endsWith('-apple-darwin')) {
  execFileSync('codesign', ['--force', '--sign', '-', '--timestamp=none', '--identifier',
    'io.johnsonlee.graphite.frontend-js', output], { stdio: 'inherit' });
  execFileSync('codesign', ['--verify', '--strict', output], { stdio: 'inherit' });
}
const hash = createHash('sha256');
for await (const chunk of fs.createReadStream(output)) hash.update(chunk);
const digest = hash.digest('hex');
fs.writeFileSync(output + '.sha256', `${digest}  ${asset}\n`);
console.log(`Packaged ${asset} for ${version} (sha256 ${digest})`);
