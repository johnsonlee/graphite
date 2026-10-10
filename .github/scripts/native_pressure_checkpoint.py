#!/usr/bin/env python3
"""Byte transport for existing native/JVM proof inputs; never proof authority.

Restore into the original workspace and exact tool paths, then run the original
independent audits. No graph, runtime, proof, or performance result is rebuilt.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile

SCHEMA = 'graphite.pressure-checkpoint.v1'
ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
DIGEST = re.compile(r'[0-9a-f]{64}\Z')
SYSTEM_JDK_PARENT = Path('/usr/lib/jvm')
SYSTEM_CACERTS = Path('/etc/ssl/certs/java/cacerts')
SYSTEM_JDK_NAME = re.compile(r'temurin-[1-9][0-9]*-jdk-(?:amd64|arm64)\Z')
IMAGE_KEYS = ('ImageOS', 'ImageVersion', 'RUNNER_OS', 'RUNNER_ARCH')
PRODUCER_TREES = ('runtime', 'graphs', 'query-correctness', 'core-string-exports',
                  'core-marker', 'core-formatter-source', 'formatter-tests')
PHASES = ('java-version', 'rustc-version', 'cargo-version', 'build-jvm', 'build-native',
          'prepare-real64', 'verify-real64')


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def canonical(path):
    p = Path(path)
    require(p.is_absolute() and '..' not in p.parts and p == p.resolve(), 'noncanonical path: '+str(p))
    return p


def system_jdk(root):
    return root.parent == SYSTEM_JDK_PARENT and SYSTEM_JDK_NAME.fullmatch(root.name) is not None


def identity(args):
    require(args.stage in ('producer', 'core', 'jvm'), 'stage')
    require(args.arm in (('producer',) if args.stage == 'producer' else ('A', 'B') if args.stage == 'core' else ('C', 'A', 'B')), 'stage arm')
    require(all(re.fullmatch('[0-9a-f]{40}', x) for x in (args.head_sha, args.base_sha)), 'exact revisions')
    require(str(args.run_id).isdigit() and str(args.run_attempt).isdigit(), 'run identity')
    require(args.arm != 'A' or args.base_sha != ACCEPTED, 'aliased arm has no checkpoint')
    return dict(stage=args.stage, arm=args.arm, head=args.head_sha, base=args.base_sha,
                accepted=ACCEPTED, runId=str(args.run_id), runAttempt=str(args.run_attempt),
                workspace=str(canonical(Path(args.workspace).absolute())))


def git_sources(workspace, base, head):
    result = {}
    for name, revision in [('base', base), ('candidate', head), *([] if base == ACCEPTED else [('accepted', ACCEPTED)])]:
        root = workspace/name
        require((root/'.git').is_dir() and not (root/'.git').is_symlink(), 'ordinary source checkout required')
        def git(*argv):
            return subprocess.check_output(['git', '-c', 'core.fsmonitor=false', '-C', str(root), *argv],
                env={**{k: v for k, v in os.environ.items() if not k.startswith('GIT_')}, 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                     'GIT_TERMINAL_PROMPT': '0'}, text=True, timeout=30).strip()
        require(git('rev-parse', 'HEAD') == revision and not git('status', '--porcelain', '--untracked-files=normal'), 'exact clean checkout: '+name)
        files = {}
        for relative in git('ls-files').splitlines():
            p = canonical(root/relative)
            require(p.is_file() and not p.is_symlink(), 'tracked source type')
            files[str(p)] = sha(p)
        result[name] = {'revision': revision, 'root': str(root), 'files': files}
    return result


def selected_roots(workspace, stage, arm):
    root = workspace/'native-pressure-artifacts'
    if stage == 'core':
        return [root/arm/'core-equivalence']
    if stage == 'jvm':
        return [root/arm/'jvm-raw-derivation', root/arm/'jvm-query-correctness']
    return [root, workspace/'benchmark-results/rust-provenance', workspace/'shared-fixture64']


def evidence_paths(workspace, stage, arm):
    roots = selected_roots(workspace, stage, arm)
    for root in roots:
        require(root.is_dir() and not root.is_symlink(), 'missing stage root: '+str(root))
    if stage != 'producer':
        return roots
    root = roots[0]
    paths = [p for p in root.iterdir() if p.is_file()]
    for name in ('C', 'A', 'B'):
        directory = root/name
        if not directory.exists():
            continue
        paths.extend(p for p in directory.iterdir() if p.is_file())
        paths.extend(directory/n for n in (*PRODUCER_TREES, *PHASES) if (directory/n).exists())
    return paths+roots[1:]


class Collector:
    def __init__(self, workspace, inherited=()):
        self.workspace = workspace
        self.files = {}
        self.directories = set()
        self.closed = {}
        self.absent = set()
        self.links = {}
        self.system_jdk_roots = {root for value in inherited for root in value.get('systemJdkRoots', [])}
        self.system_jdk_links = {name: facts for value in inherited for name, facts in value.get('systemJdkLinks', {}).items()}
        self.system_jdk_modes = {name: mode for value in inherited for name, mode in value.get('systemJdkRootModes', {}).items()}
        for value in inherited:
            for name, facts in value.get('systemJdkLinks', {}).items():
                self.links[name] = value['links'][name]
                target = facts['resolved']
                if target == str(SYSTEM_CACERTS):
                    self.files[target] = value['files'][target]
        self.verification_only = {name for value in inherited for name in value.get('verificationOnly', [])}
        self.jdk_roots = set()
        self.system_files = {str(Path(sys.executable).resolve()), '/usr/bin/time'}
        self.inherited_jdks = {root for value in inherited for root in value['jdkRoots']}
        self.jdk_roots.update(self.inherited_jdks)
        self.system_files.update(name for value in inherited for name in value['systemFiles'])

    def add_file(self, path, digest=None):
        p = canonical(path)
        require(p.is_file() and not p.is_symlink(), 'missing artifact: '+str(p))
        prior = self.files.get(str(p))
        actual = prior['sha256'] if prior is not None else sha(p)
        require(digest is None or actual == digest, 'artifact digest: '+str(p))
        mode = stat.S_IMODE(p.stat().st_mode)
        require(not mode & 0o6000, 'setuid/setgid payload prohibited')
        value = {'sha256': actual, 'mode': mode, 'size': p.stat().st_size}
        require(str(p) not in self.files or self.files[str(p)] == value, 'conflicting file')
        self.files[str(p)] = value

    def tree(self, root, closed=True, tool=False):
        root = canonical(root)
        require(root.is_dir(), 'missing directory: '+str(root))
        members = []
        for p in sorted(root.rglob('*')):
            if p.is_symlink():
                target = p.resolve()
                external_certificate = (tool and system_jdk(root) and p == root/'lib/security/cacerts' and target == SYSTEM_CACERTS)
                require(tool and (target.is_relative_to(root) or external_certificate), 'unsafe symlink: '+str(p))
                self.links[str(p)] = os.readlink(p)
                if tool and system_jdk(root):
                    require(target.exists(), 'dangling system JDK symlink: '+str(p))
                    self.system_jdk_links[str(p)] = {'resolved': str(target), 'mode': stat.S_IMODE(p.lstat().st_mode)}
                    if external_certificate:
                        self.add_file(target); self.verification_only.add(str(target)); self.system_files.add(str(target))
                members.append(str(p.relative_to(root)))
            elif p.is_dir():
                self.directories.add(str(p))
                members.append(str(p.relative_to(root)))
            else:
                self.add_file(p)
                members.append(str(p.relative_to(root)))
        self.directories.add(str(root))
        if closed:
            self.closed[str(root)] = sorted(members)

    def metadata(self, value):
        if isinstance(value, list):
            for item in value:
                self.metadata(item)
        elif isinstance(value, dict):
            if set(('path', 'sha256')) <= value.keys() and isinstance(value['path'], str) and DIGEST.fullmatch(str(value['sha256'])):
                self.add_file(Path(value['path']), value['sha256'])
            for key, child in value.items():
                if key in ('pins', 'files', 'originalArtifacts', 'sourceFiles', 'inputs') and isinstance(child, dict):
                    for name, digest in child.items():
                        if name.startswith('/') and isinstance(digest, str) and DIGEST.fullmatch(digest):
                            self.add_file(Path(name), digest)
                if key in ('configurationBefore', 'configurationAfter'):
                    for name, state in child.items():
                        p = canonical(Path(name))
                        if state is None:
                            require(not p.exists(), 'absent configuration appeared')
                            self.absent.add(name)
                        elif state == 'EMPTY_DIRECTORY':
                            require(p.is_dir() and not list(p.iterdir()), 'nonempty configuration directory')
                            self.tree(p)
                        else:
                            self.add_file(p, state)
                if key == 'toolchainIdentity':
                    for name in ('cargo', 'rustc', 'java'):
                        p = canonical(Path(child[name])); self.add_file(p)
                        self.system_files.add(str(p))
                    self.jdk_roots.add(str(Path(child['java']).parent.parent))
                self.metadata(child)
            if value.get('schema') == 'graphite.formatter-test-classpath.v1':
                for item in value['classpath']:
                    p = canonical(Path(item['path']))
                    if item['kind'] == 'absent':
                        require(not p.exists(), 'absent classpath appeared'); self.absent.add(str(p))
                    elif item['kind'] == 'directory':
                        self.tree(p)
                        actual = {str(q.relative_to(p)): sha(q) for q in p.rglob('*') if q.is_file()}
                        require(actual == item['files'], 'classpath member mismatch')
                    else:
                        require(item['kind'] == 'file', 'classpath kind'); self.add_file(p, item['sha256'])

    def collect(self, paths):
        for p in paths:
            self.tree(p) if p.is_dir() else self.add_file(p)
        # Only orchestration/authority metadata. Large raw per-local/per-node reports
        # are payload, not documents to deserialize recursively in this transport.
        names = {'audit.json', 'plan.json', 'packet.json', 'source-manifest.json', 'runtime-manifest.json',
                 'inputs-before.json', 'source-inputs.json', 'provenance.json',
                 'artifact-audit.json', 'query-correctness-audit.json'}
        for path in list(self.files):
            p = Path(path)
            if p.name in names or p.name.startswith('classpath-') and p.suffix == '.json':
                value = read(p)
                self.metadata(value)
                if p == self.workspace/'benchmark-results/rust-provenance/provenance.json':
                    for name, digest in value['files'].items():
                        if digest is not None:
                            path = Path(name)
                            if not path.is_absolute():
                                require('..' not in path.parts, 'relative provenance traversal')
                                path = self.workspace/path
                            self.add_file(path, digest)
                            if path.parent.name == '.cargo' and path.name in ('config', 'config.toml'):
                                self.verification_only.add(str(path)); self.system_files.add(str(path))
        self.add_file(Path(sys.executable).resolve())
        self.system_files.add(str(Path(sys.executable).resolve()))
        for root in sorted(self.jdk_roots-self.inherited_jdks):
            self.tree(Path(root), tool=True)
            if system_jdk(Path(root)):
                self.system_jdk_roots.add(root)
                self.system_jdk_modes[root] = stat.S_IMODE(Path(root).stat().st_mode)
                self.verification_only.update(name for name in self.files if Path(name).is_relative_to(Path(root)))
        for path in self.files:
            require(Path(path).is_relative_to(self.workspace) or path in self.system_files or
                    any(Path(path).is_relative_to(Path(root)) for root in self.jdk_roots), 'unapproved external pinned file: '+path)
        return self


def check_system_jdks(manifest):
    """System packages are preexisting read-only dependencies, never restore roots."""
    roots = [canonical(Path(name)) for name in manifest.get('systemJdkRoots', [])]
    require(set(map(str, roots)) <= set(manifest['jdkRoots']), 'system JDK root not declared')
    for root in roots:
        require(system_jdk(root) and root.is_dir() and
                stat.S_IMODE(root.stat().st_mode) == manifest['systemJdkRootModes'][str(root)], 'system JDK root identity/mode')
    for name in manifest['payload']:
        require(name != str(SYSTEM_CACERTS) and not any(Path(name).is_relative_to(root) for root in roots),
                'read-only system JDK in payload')
    links = manifest.get('systemJdkLinks', {})
    require(set(links) == {name for name in manifest['links'] if any(Path(name).is_relative_to(root) for root in roots)},
            'complete system JDK link identities')
    for name, facts in links.items():
        p = Path(name); containing = [root for root in roots if p.is_relative_to(root)]
        require(len(containing) == 1, 'system JDK link root')
        root = containing[0]; target = Path(facts['resolved'])
        require(target.is_relative_to(root) or p == root/'lib/security/cacerts' and target == SYSTEM_CACERTS,
                'unapproved external system JDK target')
        require(p.is_symlink() and os.readlink(p) == manifest['links'][name] and
                p.resolve() == target and target.exists() and stat.S_IMODE(p.lstat().st_mode) == facts['mode'],
                'system JDK link identity/target/mode')
        if not target.is_relative_to(root):
            require(str(target) in manifest['files'] and str(target) in manifest['verificationOnly'], 'system certificate pin missing')
    for name in manifest['directories']:
        if any(Path(name).is_relative_to(root) for root in roots):
            require(canonical(Path(name)).is_dir() and stat.S_IMODE(Path(name).stat().st_mode) == manifest['directoryModes'][name],
                    'system JDK directory identity/mode')
    for name, expected in manifest['closedDirectories'].items():
        if any(Path(name).is_relative_to(root) for root in roots):
            require(sorted(str(p.relative_to(name)) for p in Path(name).rglob('*')) == expected, 'system JDK closed directory membership')


def check_files(manifest):
    check_system_jdks(manifest)
    for name, value in manifest['files'].items():
        p = canonical(Path(name))
        require(p.is_file() and not p.is_symlink() and sha(p) == value['sha256'] and
                stat.S_IMODE(p.stat().st_mode) == value['mode'] and p.stat().st_size == value['size'], 'restored file identity/mode: '+name)
    for name in manifest['directories']:
        require(canonical(Path(name)).is_dir() and stat.S_IMODE(Path(name).stat().st_mode) == manifest['directoryModes'][name], 'restored directory mode')
    for name in manifest['absent']:
        require(not Path(name).exists() and not Path(name).is_symlink(), 'absent path appeared: '+name)
    for name, target in manifest['links'].items():
        require(Path(name).is_symlink() and os.readlink(name) == target, 'tool symlink identity')
    for name, expected in manifest['closedDirectories'].items():
        actual = sorted(str(p.relative_to(name)) for p in Path(name).rglob('*'))
        require(actual == expected, 'closed directory membership: '+name)


def upstreams(args, ident):
    values = []
    for name in args.upstream:
        path = canonical(Path(name).absolute()); value = read(path)
        require(value['schema'] == SCHEMA and all(value['identity'][k] == ident[k] for k in
            ('head', 'base', 'accepted', 'runId', 'runAttempt', 'workspace')), 'upstream identity')
        require(value['python'] == str(Path(sys.executable).resolve()) and
                value['runnerImage'] == {key: os.environ.get(key) for key in IMAGE_KEYS}, 'upstream interpreter/runner identity')
        check_files(value)
        values.append({'sha256': sha(path), 'stage': value['identity']['stage'], 'arm': value['identity']['arm']})
    expected = [] if ident['stage'] == 'producer' else [('producer', 'producer')]
    if ident['stage'] == 'jvm':
        expected.extend(('core', arm) for arm in (('B',) if ident['base'] == ACCEPTED else ('A', 'B')))
    require(sorted((v['stage'], v['arm']) for v in values) == sorted(expected), 'exact upstream stages')
    return sorted(values, key=lambda v: (v['stage'], v['arm']))


def seal(args):
    ident = identity(args); workspace = Path(ident['workspace']); directory = Path(args.directory).absolute()
    require(not directory.exists(), 'fresh checkpoint directory required')
    sources = git_sources(workspace, ident['base'], ident['head'])
    upstream = upstreams(args, ident)
    collector = Collector(workspace, [read(path) for path in args.upstream]).collect(evidence_paths(workspace, args.stage, args.arm))
    # No unrelated upstream files are copied into delta payloads, but every pin is
    # still in the manifest and verified after restore.
    upstream_files = {}
    for path in args.upstream:
        upstream_files.update(read(path)['files'])
    source_files = {p for source in sources.values() for p in source['files']}
    payload = [p for p in sorted(collector.files) if p not in source_files and p not in collector.verification_only and
               collector.files[p] != upstream_files.get(p) and
               (Path(p).is_relative_to(workspace) or any(Path(p).is_relative_to(Path(r)) for r in collector.jdk_roots))]
    require(not any(directory.is_relative_to(root) for root in selected_roots(workspace, args.stage, args.arm)), 'checkpoint outside evidence roots')
    directory.mkdir(parents=True)
    archive = directory/'payload.tar.gz'
    require(shutil.disk_usage(directory).free >= 64*1024*1024, 'insufficient checkpoint disk headroom')
    with tarfile.open(archive, 'w:gz', compresslevel=1, dereference=True) as tar:
        for index, name in enumerate(payload):
            require(shutil.disk_usage(directory).free >= 64*1024*1024, 'checkpoint disk headroom exhausted')
            tar.add(name, arcname=f'files/{index:08d}', recursive=False)
    manifest = {'schema': SCHEMA, 'identity': ident, 'sources': sources, 'upstream': upstream,
                'python': str(Path(sys.executable).resolve()),
                'runnerImage': {key: os.environ.get(key) for key in IMAGE_KEYS}, 'files': collector.files,
                'directories': sorted(collector.directories),
                'directoryModes': {name: stat.S_IMODE(Path(name).stat().st_mode) for name in sorted(collector.directories)},
                'closedDirectories': collector.closed,
                'absent': sorted(collector.absent), 'links': collector.links, 'jdkRoots': sorted(collector.jdk_roots),
                'systemJdkRoots': sorted(collector.system_jdk_roots), 'systemJdkLinks': collector.system_jdk_links,
                'systemJdkRootModes': collector.system_jdk_modes,
                'systemFiles': sorted(collector.system_files), 'verificationOnly': sorted(collector.verification_only), 'payload': payload,
                'archiveSha256': sha(archive), 'proofAuthority': False, 'performanceAcceptance': False}
    check_files(manifest)
    require(upstreams(args, ident) == upstream, 'upstream changed during seal')
    require(git_sources(workspace, ident['base'], ident['head']) == sources, 'source changed during seal')
    save(directory/'manifest.json', manifest)
    return manifest


def restore(args):
    ident = identity(args); workspace = Path(ident['workspace']); directory = Path(args.directory).absolute()
    manifest = read(directory/'manifest.json')
    require(manifest['schema'] == SCHEMA and manifest['identity'] == ident and
            manifest['proofAuthority'] is False and manifest['performanceAcceptance'] is False, 'checkpoint identity')
    require(manifest['python'] == str(Path(sys.executable).resolve()), 'Python interpreter path changed')
    require(manifest['runnerImage'] == {key: os.environ.get(key) for key in IMAGE_KEYS}, 'runner image changed')
    require(git_sources(workspace, ident['base'], ident['head']) == manifest['sources'], 'source inventory changed')
    require(upstreams(args, ident) == manifest['upstream'], 'upstream checkpoint binding')
    require(sha(directory/'payload.tar.gz') == manifest['archiveSha256'], 'archive digest')
    require(len(manifest['payload']) == len(set(manifest['payload'])), 'duplicate payload destination')
    check_system_jdks(manifest)
    readonly_jdks = [Path(p) for p in manifest.get('systemJdkRoots', [])]
    jdk_roots = [canonical(Path(p)) for p in manifest['jdkRoots'] if Path(p) not in readonly_jdks]
    for p in jdk_roots:
        require(p.is_relative_to(workspace) or (p.is_relative_to(Path('/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk')) and len(p.parts) >= 6), 'unsafe JDK root')
    def writable(name):
        p = canonical(Path(name))
        require(p != SYSTEM_CACERTS and not any(p.is_relative_to(root) for root in readonly_jdks), 'read-only system JDK write prohibited')
        require(p.is_relative_to(workspace) or any(p.is_relative_to(root) for root in jdk_roots), 'outside restore roots')
        require(not p.is_relative_to(directory), 'payload overlaps checkpoint')
        require('.git' not in p.parts, 'Git metadata overwrite prohibited')
        return p
    # Check non-payload identities before writing anything; system tools are never
    # overwritten, and source checkout content is never supplied by the payload.
    for name, facts in manifest['files'].items():
        if name not in manifest['payload']:
            p = canonical(Path(name))
            require(p.is_file() and sha(p) == facts['sha256'] and stat.S_IMODE(p.stat().st_mode) == facts['mode'], 'preexisting dependency identity: '+name)
    for name in manifest['absent']:
        require(not Path(name).exists() and not Path(name).is_symlink(), 'absent path appeared: '+name)
    with tarfile.open(directory/'payload.tar.gz', 'r:gz') as tar:
        expected = [f'files/{i:08d}' for i in range(len(manifest['payload']))]
        members = tar.getmembers()
        require([m.name for m in members] == expected and all(m.isfile() and not m.issym() and not m.islnk() for m in members), 'unsafe or incomplete archive members')
        for member, name in zip(members, manifest['payload']):
            p = writable(name); facts = manifest['files'][name]
            require(member.size == facts['size'] and member.mode == facts['mode'] and not member.mode & 0o6000, 'archive metadata')
            if p.exists():
                require(p.is_file() and sha(p) == facts['sha256'] and stat.S_IMODE(p.stat().st_mode) == facts['mode'], 'refuse overwriting different file: '+name)
                continue
            p.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as src, p.open('xb') as dst:
                while chunk := src.read(1024*1024):
                    dst.write(chunk)
            p.chmod(facts['mode'])
            require(sha(p) == facts['sha256'], 'restored payload digest')
    for name in manifest['directories']:
        if any(Path(name).is_relative_to(root) for root in readonly_jdks):
            continue
        p = writable(name); p.mkdir(parents=True, exist_ok=True)
        mode = manifest['directoryModes'][name]
        require(type(mode) is int and 0 <= mode <= 0o777, 'safe directory mode')
        p.chmod(mode)
    for name, target in manifest['links'].items():
        if name in manifest.get('systemJdkLinks', {}):
            continue
        p = Path(name)
        require(any(p.is_relative_to(root) and (p.parent/target).resolve().is_relative_to(root) for root in jdk_roots), 'unsafe tool symlink')
        if p.is_symlink():
            require(os.readlink(p) == target, 'existing tool symlink mismatch')
        else:
            require(not p.exists(), 'existing tool link destination'); p.parent.mkdir(parents=True, exist_ok=True); p.symlink_to(target)
    check_files(manifest)
    require(git_sources(workspace, ident['base'], ident['head']) == manifest['sources'], 'restored source changed')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('seal', 'restore'))
    for name in ('stage', 'arm', 'directory', 'workspace', 'head-sha', 'base-sha', 'run-id', 'run-attempt'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--upstream', action='append', default=[])
    args = parser.parse_args()
    value = seal(args) if args.action == 'seal' else restore(args)
    print(json.dumps({'checkpointTransport': args.action, 'identity': value['identity'],
                      'proofAuthority': False, 'performanceAcceptance': False}))


if __name__ == '__main__':
    main()
