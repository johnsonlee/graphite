#!/usr/bin/env python3
"""Byte transport for existing native/JVM proof inputs; never proof authority.

Restore into the original workspace and exact tool paths, then run the original
independent audits. No graph, runtime, proof, or performance result is rebuilt.
"""
import argparse
from contextlib import contextmanager
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
import time

SCHEMA = 'graphite.pressure-checkpoint.v1'
ACCEPTED = '4f2ccf33b969e684972e56b5e810034e6e67c1b3'
DIGEST = re.compile(r'[0-9a-f]{64}\Z')
SYSTEM_JDK_PARENT = Path('/usr/lib/jvm')
# Temurin's Debian package uses adoptium-ca-certificates; older images use java.
# Both targets remain pinned preexisting dependencies, never restore destinations.
SYSTEM_CACERTS = frozenset((Path('/etc/ssl/certs/java/cacerts'), Path('/etc/ssl/certs/adoptium/cacerts')))
SYSTEM_JDK_NAME = re.compile(r'temurin-[1-9][0-9]*-jdk-(?:amd64|arm64)\Z')
IMAGE_KEYS = ('ImageOS', 'ImageVersion', 'RUNNER_OS', 'RUNNER_ARCH')
# The hosted image's version label may roll out while a run is in flight (run 912: the sealing
# jobs ran on provisioner 20261002.596, the consumers started at 10:54Z on 20261012.601), so a
# consumer matches the sealing job's OS and architecture exactly, lets the version label differ,
# and reports the drift. What a new image could change in the toolchain is bound by content: the
# JDK files, the interpreter path and the pinned binaries are compared by digest, not by label.
IMAGE_LABEL = 'ImageVersion'


def runner_image():
    return {key: os.environ.get(key) for key in IMAGE_KEYS}


def same_runner_image(recorded):
    current = runner_image()
    return isinstance(recorded, dict) and set(recorded) == set(IMAGE_KEYS) and all(
        recorded[key] == current[key] for key in IMAGE_KEYS if key != IMAGE_LABEL)


def runner_image_drift(recorded):
    current = runner_image()
    if recorded.get(IMAGE_LABEL) == current[IMAGE_LABEL]:
        return None
    return {'sealed': recorded.get(IMAGE_LABEL), 'current': current[IMAGE_LABEL]}
PRODUCER_TREES = ('runtime', 'graphs', 'query-correctness', 'core-string-exports',
                  'core-marker', 'core-formatter-source', 'formatter-tests')
CORE_INPUT_TREES = ('core-string-exports', 'core-marker', 'core-formatter-source', 'formatter-tests')
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
    require(args.stage in ('producer', 'producer-common', 'producer-arm', 'core', 'core-prepare', 'core-shard', 'jvm','jvm-pending'), 'stage')
    require(args.arm in (('producer',) if args.stage == 'producer' else ('common',) if args.stage == 'producer-common' else ('A', 'B') if args.stage in ('core', 'core-prepare', 'core-shard') else ('C', 'A', 'B')), 'stage arm')
    require(all(re.fullmatch('[0-9a-f]{40}', x) for x in (args.head_sha, args.base_sha)), 'exact revisions')
    require(str(args.run_id).isdigit() and str(args.run_attempt).isdigit(), 'run identity')
    require(args.arm != 'A' or args.base_sha != ACCEPTED, 'aliased arm has no checkpoint')
    count = getattr(args, 'shard_count', None); index = getattr(args, 'shard_index', None)
    sharded = args.stage in ('core-prepare', 'core-shard')
    require((type(count) is int and count in (4, 8)) if sharded else count is None, 'checkpoint shard count')
    require((type(index) is int and 0 <= index < count) if args.stage == 'core-shard' else index is None, 'checkpoint shard index')
    result = dict(stage=args.stage, arm=args.arm, head=args.head_sha, base=args.base_sha,
                accepted=ACCEPTED, runId=str(args.run_id), runAttempt=str(args.run_attempt),
                workspace=str(canonical(Path(args.workspace).absolute())))
    if sharded: result['shardCount'] = count
    if args.stage == 'core-shard': result['shardIndex'] = index
    return result


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
    if stage == 'producer-common':
        return [root, workspace/'benchmark-results/rust-provenance', workspace/'shared-fixture64']
    if stage == 'producer-arm':
        return [root/arm]
    if stage in ('core', 'core-prepare', 'core-shard'):
        paths = [root/arm/'core-equivalence']
        if stage != 'core-shard':
            # Preparation transports new arm-local correctness inputs. The final
            # core checkpoint must carry them again: its only ancestor is producer.
            paths.extend(root/arm/name for name in CORE_INPUT_TREES if (root/arm/name).exists())
        return paths
    if stage in ('jvm','jvm-pending'):
        return [root/arm/'jvm-raw-derivation', root/arm/'jvm-query-correctness',*([root/arm/'jvm-raw-edges'] if stage=='jvm-pending' else [])]
    return [root, workspace/'benchmark-results/rust-provenance', workspace/'shared-fixture64']


def evidence_paths(workspace, stage, arm, ident=None):
    roots = selected_roots(workspace, stage, arm)
    for root in roots:
        require(root.is_dir() and not root.is_symlink(), 'missing stage root: '+str(root))
    if stage in ('core-prepare', 'core-shard'):
        return core_shard_paths(roots[0], ident) + roots[1:]
    if stage == 'producer-common':
        return [roots[0]/name for name in ('source-inputs.json','portable-expectations.json','producer-common.json')]+roots[1:]
    if stage == 'producer-arm':
        root=roots[0]
        return [p for p in root.iterdir() if p.is_file()] + [
            root/name for name in (*PRODUCER_TREES,*PHASES) if (root/name).exists()]
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


def check_producer_common_paths(workspace, paths):
    root=workspace/'native-pressure-artifacts'
    for leaf in ('source-inputs.json','portable-expectations.json','producer-common.json'):
        require(str(root/leaf) in paths,'common producer input required')
    for name in paths:
        p=Path(name)
        require(p.is_absolute() and '..' not in p.parts,'canonical producer partition path')
        if p.is_relative_to(root):
            parts=p.relative_to(root).parts
            require(not parts or parts[0] not in ('C','A','B'),'common producer contains arm output')


def producer_common_descriptor(workspace):
    descriptor=read(workspace/'native-pressure-artifacts/producer-common.json')
    require(descriptor.get('schema')=='graphite.producer-common-inputs.v1' and
            set(descriptor.get('toolchainIdentity',{}))=={'java','cargo','rustc'},'declared common producer toolchain')
    return descriptor


def check_producer_arm_paths(workspace, ident, paths):
    """An arm transport has no authority to write or depend on another arm."""
    root=workspace/'native-pressure-artifacts'
    require(str(root/ident['arm']/'packet.json') in paths,'producer arm packet required')
    for name in paths:
        p=Path(name)
        require(p.is_absolute() and '..' not in p.parts,'canonical producer partition path')
        if p.is_relative_to(root):
            parts=p.relative_to(root).parts
            require(not parts or parts[0] not in ('C','A','B') or parts[0]==ident['arm'],
                    'producer arm contains another arm')


def check_producer_arm_packet(workspace, ident):
    packet=read(workspace/'native-pressure-artifacts'/ident['arm']/'packet.json')
    revision,role={'C':(ACCEPTED,'accepted-baseline'), 'A':(ident['base'],'parent'),
                   'B':(ident['head'],'candidate')}[ident['arm']]
    require(packet.get('revision')==revision and packet.get('role')==role,
            'producer arm exact revision and role')


def core_shard_paths(root, ident):
    # Reuse the runner's declared partition; transport cannot invent another
    # graph assignment or close a shared directory that later workers extend.
    import run_native_core_equivalence as core
    plan = read(root/'plan.json')
    require(plan.get('schema') == 'graphite.native-core-equivalence-plan.v1' and
            plan.get('output') == str(root) and plan.get('execution') == core.shard_execution(ident['shardCount']),
            'checkpoint core shard plan identity')
    index = ident.get('shardIndex', 0)
    rows = core.shard_rows(plan, index)
    for row in plan['graphs']:
        require(isinstance(row['id'], str) and re.fullmatch(r'[A-Za-z0-9_-]+', row['id']), 'safe graph directory name')
    if ident['stage'] == 'core-prepare':
        names = [name for name, _, _ in core.preparation_commands(plan)]
        paths = [root/'plan.json', root/'preparation.json', root/'classes']
        if 'rawLocalExports' in plan: paths.append(root/'raw-locals')
    else:
        members = list((root/'graphs').iterdir())
        require({p.name for p in members} == {row['id'] for row in rows} and
                all(p.is_dir() and not p.is_symlink() for p in members),
                'exact assigned shard graph output inventory')
        names = [name for name, _, _ in core.shard_commands(plan, index)]
        record = read(root/'shards'/str(index)/'record.json')
        require(record.get('shardIndex') == index and record.get('shardCount') == ident['shardCount'] and
                record.get('plan') == {'path': str(root/'plan.json'), 'sha256': sha(root/'plan.json')} and
                record.get('preparation') == {'path': str(root/'preparation.json'), 'sha256': sha(root/'preparation.json')},
                'checkpoint shard receipt identity')
        paths = [root/'graphs'/row['id'] for row in rows] + [root/'shards'/str(index)]
    require(len(names) == len(set(names)), 'unique core checkpoint phase directories')
    for name in names:
        require(re.fullmatch(r'[A-Za-z0-9_-]+', name) is not None, 'safe phase directory name')
    return paths + [root/name for name in names]


def check_shard_transport(workspace, ident, files):
    """Check the audited claim's exact byte closure; semantic authority stays with the runner."""
    import run_native_core_equivalence as core
    root = workspace/'native-pressure-artifacts'/ident['arm']/'core-equivalence'
    index = ident['shardIndex']; plan = read(root/'plan.json')
    record_path = root/'shards'/str(index)/'record.json'
    record = read(record_path); audit = read(record_path.with_name('audit.json'))
    def ref(path):
        name = str(path)
        require(name in files, 'audited reference omitted from checkpoint: '+name)
        return {'path': name, 'sha256': files[name]['sha256']}
    expected_refs = {'plan': ref(root/'plan.json'), 'preparation': ref(root/'preparation.json'),
                     'record': ref(record_path),
                     'checker': ref(workspace/'candidate/.github/scripts/run_native_core_equivalence.py')}
    require(audit.get('schema') == 'graphite.native-core-shard-audit.v1' and
            audit.get('status') == core.SHARD_AUDIT_PASS and audit.get('errors') == [] and
            audit.get('finalIdentity') == 'PASS' and audit.get('shardIndex') == index and
            audit.get('shardCount') == ident['shardCount'] and
            audit.get('completeAssignedCoreTopologyIndexComparison') is True and
            all(audit.get(key) is False for key in core.FALSE_CLAIMS) and
            all(audit.get(key) == value for key, value in expected_refs.items()),
            'complete exact shard audit transport identity')
    pins = audit.get('pins')
    require(isinstance(pins, dict) and all(name in files and files[name]['sha256'] == digest
                                         for name, digest in pins.items()), 'audited pins match transported bytes')
    required = dict(plan['pins'])
    required.update(read(root/'preparation.json')['files'])
    required.update({value['path']: value['sha256'] for value in expected_refs.values()})
    required.update({name: facts['sha256'] for name, facts in files.items()
                     if Path(name).is_relative_to(root/'classes')})
    rows = audit.get('graphs', [])
    require(rows == record.get('graphs') and
            [row['id'] for row in rows] == [row['id'] for row in core.shard_rows(plan, index)],
            'audited complete assigned graph claims')
    for row in rows:
        directory = root/'graphs'/row['id']
        actual = {name: facts['sha256'] for name, facts in files.items() if Path(name).is_relative_to(directory)}
        require(row.get('files') == actual and row.get('core') == ref(directory/'record.json') and
                row.get('topology') == ref(directory/'topology.json'), 'audited complete graph byte closure')
        required.update(actual)
    phases = []
    for name, _, _ in core.shard_commands(plan, index):
        phases.append(ref(root/name/'record.json'))
        for leaf in ('record.json', 'owner.json', 'stdout.log', 'stderr.log'):
            value = ref(root/name/leaf); required[value['path']] = value['sha256']
    require(audit.get('phases') == record.get('phases') == phases, 'audited complete phase closure')
    require(pins == required, 'exact complete shard audit pin closure')


class Collector:
    def __init__(self, workspace, inherited=(), _authority=None):
        self.workspace = workspace
        self._authority = _authority
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
                if Path(target) in SYSTEM_CACERTS:
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
        if prior is None and self._authority is not None:
            prior = self._authority.verified.get(str(p))
        if prior is not None and p in SYSTEM_CACERTS:
            # A delta checkpoint keeps the bundle facts it inherited; the bundle itself may have
            # drifted with the image, which dependency_identity reports.
            dependency_identity(p, prior)
            value = dict(prior)
        else:
            actual = (self._authority.digest(p, prior) if prior is not None and self._authority is not None
                      else prior['sha256'] if prior is not None else sha(p))
            require(digest is None or actual == digest, 'artifact digest: '+str(p))
            mode = stat.S_IMODE(p.stat().st_mode)
            require(not mode & 0o6000, 'setuid/setgid payload prohibited')
            value = {'sha256': actual, 'mode': mode, 'size': p.stat().st_size}
        require(str(p) not in self.files or self.files[str(p)] == value, 'conflicting file')
        self.files[str(p)] = value
        if self._authority is not None:
            require(str(p) not in self._authority.verified or self._authority.verified[str(p)] == value,
                    'conflicting collected file attributes')
            self._authority.verified[str(p)] = dict(value)

    def tree(self, root, closed=True, tool=False):
        root = canonical(root)
        require(root.is_dir(), 'missing directory: '+str(root))
        members = []
        for p in sorted(root.rglob('*')):
            if p.is_symlink():
                raw_target = os.readlink(p); target = p.resolve()
                details = f'link={p}, root={root}, rawTarget={raw_target!r}, resolved={target}, tool={tool}'
                external_certificate = (tool and system_jdk(root) and p == root/'lib/security/cacerts' and target in SYSTEM_CACERTS)
                require(tool and (target.is_relative_to(root) or external_certificate), 'unsafe symlink: '+details)
                self.links[str(p)] = raw_target
                if tool and system_jdk(root):
                    require(target.exists(), 'dangling system JDK symlink: '+details)
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
                 'inputs-before.json', 'source-inputs.json', 'producer-common.json', 'provenance.json',
                 'artifact-audit.json', 'query-correctness-audit.json', 'preparation.json'}
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


def dependency_identity(p, facts, _authority=None):
    """Whether a preexisting dependency is the one the manifest sealed. Every file is bound by
    content, except the system CA bundle the JDK's lib/security/cacerts links to: a trust store
    is configuration the hosted image updates on its own schedule (run 913: the producer-common
    checkpoint sealed one image's bundle and rust-producer-arm (A) landed on the next image's), and
    no build, measurement or query result reads it. It must still exist as a regular file with the
    sealed mode; a content change is reported as a system-certificates drift event, never hidden."""
    if p in SYSTEM_CACERTS:
        require(p.is_file() and not p.is_symlink() and stat.S_IMODE(p.stat().st_mode) == facts['mode'],
                'system certificate bundle identity: '+str(p))
        actual = sha(p)
        if actual != facts['sha256']:
            _chain_progress('system-certificates', 'drift', path=str(p), sealed=facts['sha256'], current=actual)
        return True
    return (p.is_file() and not p.is_symlink() and
            (_authority.digest(p, facts) if _authority is not None else sha(p)) == facts['sha256'] and
            stat.S_IMODE(p.stat().st_mode) == facts['mode'] and p.stat().st_size == facts['size'])


def check_system_jdks(manifest):
    """System packages are preexisting read-only dependencies, never restore roots."""
    roots = [canonical(Path(name)) for name in manifest.get('systemJdkRoots', [])]
    require(set(map(str, roots)) <= set(manifest['jdkRoots']), 'system JDK root not declared')
    for root in roots:
        require(system_jdk(root) and root.is_dir() and
                stat.S_IMODE(root.stat().st_mode) == manifest['systemJdkRootModes'][str(root)], 'system JDK root identity/mode')
    for name in manifest['payload']:
        require(Path(name) not in SYSTEM_CACERTS and not any(Path(name).is_relative_to(root) for root in roots),
                'read-only system JDK in payload')
    links = manifest.get('systemJdkLinks', {})
    require(set(links) == {name for name in manifest['links'] if any(Path(name).is_relative_to(root) for root in roots)},
            'complete system JDK link identities')
    for name, facts in links.items():
        p = Path(name); containing = [root for root in roots if p.is_relative_to(root)]
        require(len(containing) == 1, 'system JDK link root')
        root = containing[0]; target = Path(facts['resolved'])
        raw_target = os.readlink(p) if p.is_symlink() else None
        resolved = p.resolve()
        details = f'link={p}, root={root}, rawTarget={raw_target!r}, resolved={resolved}, expectedResolved={target}'
        require(target.is_relative_to(root) or p == root/'lib/security/cacerts' and target in SYSTEM_CACERTS,
                'unapproved external system JDK target: '+details)
        require(p.is_symlink() and raw_target == manifest['links'][name] and
                resolved == target and target.exists() and stat.S_IMODE(p.lstat().st_mode) == facts['mode'],
                'system JDK link identity/target/mode: '+details)
        if not target.is_relative_to(root):
            require(str(target) in manifest['files'] and str(target) in manifest['verificationOnly'], 'system certificate pin missing')
    for name in manifest['directories']:
        if any(Path(name).is_relative_to(root) for root in roots):
            require(canonical(Path(name)).is_dir() and stat.S_IMODE(Path(name).stat().st_mode) == manifest['directoryModes'][name],
                    'system JDK directory identity/mode')
    for name, expected in manifest['closedDirectories'].items():
        if any(Path(name).is_relative_to(root) for root in roots):
            require(sorted(str(p.relative_to(name)) for p in Path(name).rglob('*')) == expected, 'system JDK closed directory membership')


def check_files(manifest, _authority=None):
    check_system_jdks(manifest)
    for name, value in manifest['files'].items():
        p = canonical(Path(name))
        require(dependency_identity(p, value, _authority), 'restored file identity/mode: '+name)
    for name in manifest['directories']:
        require(canonical(Path(name)).is_dir() and stat.S_IMODE(Path(name).stat().st_mode) == manifest['directoryModes'][name], 'restored directory mode')
    for name in manifest['absent']:
        require(not Path(name).exists() and not Path(name).is_symlink(), 'absent path appeared: '+name)
    for name, target in manifest['links'].items():
        require(Path(name).is_symlink() and os.readlink(name) == target, 'tool symlink identity')
    for name, expected in manifest['closedDirectories'].items():
        actual = sorted(str(p.relative_to(name)) for p in Path(name).rglob('*'))
        require(actual == expected, 'closed directory membership: '+name)


def upstreams(args, ident, _authority=None):
    values = []
    for name in args.upstream:
        path = canonical(Path(name).absolute()); value = read(path)
        require(value['schema'] == SCHEMA and all(value['identity'][k] == ident[k] for k in
            ('head', 'base', 'accepted', 'runId', 'runAttempt', 'workspace')), 'upstream identity')
        require(value['python'] == str(Path(sys.executable).resolve()) and
                same_runner_image(value['runnerImage']), 'upstream interpreter/runner identity')
        if _authority is None or not _authority.preflight:
            check_files(value, _authority)
        upstream_ident = value['identity']
        entry = {'sha256': sha(path), 'stage': upstream_ident['stage'], 'arm': upstream_ident['arm']}
        if upstream_ident['stage'] == 'core-prepare':
            require(((ident['stage'] == 'core-shard' and upstream_ident['arm'] == ident['arm'] and
                    upstream_ident.get('shardCount') == ident['shardCount']) or
                    (ident['stage']=='jvm-pending' and upstream_ident['arm'] in ('A','B') and upstream_ident.get('shardCount')==8)) and 'shardIndex' not in upstream_ident,
                    'same arm and partition preparation upstream')
            entry['shardCount'] = upstream_ident['shardCount']
        values.append(entry)
    expected = [] if ident['stage'] in ('producer','producer-common','producer-arm') else [('producer', 'producer')]
    if ident['stage']=='producer-arm' and args.upstream:expected=[('producer-common','common')]
    if ident['stage'] == 'core-shard':
        expected.append(('core-prepare', ident['arm']))
        preparation = [read(name) for name in args.upstream if read(name)['identity']['stage'] == 'core-prepare']
        producers = [entry for entry in values if entry['stage'] == 'producer']
        require(len(preparation) == 1 and len(producers) == 1 and preparation[0]['upstream'] == producers,
                'preparation bound to exact same producer checkpoint')
    if ident['stage']=='jvm-pending':
        expected.extend(('core-prepare',arm) for arm in (('B',) if ident['base']==ACCEPTED else ('A','B')))
        producer_entries=[entry for entry in values if entry['stage']=='producer']
        require(len(producer_entries)==1 and all(read(name)['upstream']==producer_entries for name in args.upstream if read(name)['identity']['stage']=='core-prepare'),
                'pending JVM preparations bind same producer')
    if ident['stage'] == 'jvm':
        expected.extend(('core', arm) for arm in (('B',) if ident['base'] == ACCEPTED else ('A', 'B')))
    require(sorted((v['stage'], v['arm']) for v in values) == sorted(expected), 'exact upstream stages')
    return sorted(values, key=lambda v: (v['stage'], v['arm']))


def seal(args):
    ident = identity(args); workspace = Path(ident['workspace']); directory = Path(args.directory).absolute()
    require(not directory.exists(), 'fresh checkpoint directory required')
    with _seal_stage('seal-collect'):
        sources = git_sources(workspace, ident['base'], ident['head'])
        # Share byte reads only inside this entry boundary. Structural checks still
        # run for every upstream and collection. This authority never survives into
        # the independent verification after archive creation.
        entry = _RestoreChainAuthority(sources); entry.preflight = False
        upstream = upstreams(args, ident, entry)
        collector = Collector(workspace, [read(path) for path in args.upstream], entry).collect(
            evidence_paths(workspace, args.stage, args.arm, ident))
        if ident['stage']=='producer-common':
            check_producer_common_paths(workspace,set(collector.files)|collector.directories|set(collector.links)|collector.absent)
            producer_common_descriptor(workspace)
        if ident['stage']=='producer-arm':
            check_producer_arm_paths(workspace,ident,set(collector.files)|collector.directories|set(collector.links)|collector.absent)
            check_producer_arm_packet(workspace,ident)
        collector._authority = None
        del entry
        if ident['stage'] == 'core-shard': check_shard_transport(workspace, ident, collector.files)
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
    with _seal_stage('seal-archive'):
        with tarfile.open(archive, 'w:gz', compresslevel=1, dereference=True) as tar:
            for index, name in enumerate(payload):
                require(shutil.disk_usage(directory).free >= 64*1024*1024, 'checkpoint disk headroom exhausted')
                tar.add(name, arcname=f'files/{index:08d}', recursive=False)
        manifest = {'schema': SCHEMA, 'identity': ident, 'sources': sources, 'upstream': upstream,
                    'python': str(Path(sys.executable).resolve()),
                    'runnerImage': runner_image(), 'files': collector.files,
                    'directories': sorted(collector.directories),
                    'directoryModes': {name: stat.S_IMODE(Path(name).stat().st_mode) for name in sorted(collector.directories)},
                    'closedDirectories': collector.closed,
                    'absent': sorted(collector.absent), 'links': collector.links, 'jdkRoots': sorted(collector.jdk_roots),
                    'systemJdkRoots': sorted(collector.system_jdk_roots), 'systemJdkLinks': collector.system_jdk_links,
                    'systemJdkRootModes': collector.system_jdk_modes,
                    'systemFiles': sorted(collector.system_files), 'verificationOnly': sorted(collector.verification_only), 'payload': payload,
                    'archiveSha256': sha(archive), 'proofAuthority': False, 'performanceAcceptance': False}
    # No entry digest is reused here: independently hash each unique final
    # file, then check every declaration's attributes and complete inventories.
    with _seal_stage('seal-final-identity'):
        final = _RestoreChainAuthority(sources); final.preflight = False
        check_files(manifest, final)
        require(upstreams(args, ident, final) == upstream, 'upstream changed during seal')
        require(git_sources(workspace, ident['base'], ident['head']) == sources, 'source changed during seal')
        if ident['stage'] == 'core-shard':
            core_shard_paths(workspace/'native-pressure-artifacts'/ident['arm']/'core-equivalence', ident)
            check_shard_transport(workspace, ident, collector.files)
    if ident['stage']=='producer-arm':check_producer_arm_packet(workspace,ident)
    save(directory/'manifest.json', manifest)
    return manifest


def restore(args, _authority=None):
    ident = identity(args); workspace = Path(ident['workspace']); directory = Path(args.directory).absolute()
    manifest = read(directory/'manifest.json')
    require(manifest['schema'] == SCHEMA and manifest['identity'] == ident and
            manifest['proofAuthority'] is False and manifest['performanceAcceptance'] is False, 'checkpoint identity')
    require(manifest['python'] == str(Path(sys.executable).resolve()), 'Python interpreter path changed')
    require(same_runner_image(manifest['runnerImage']), 'runner image changed')
    drift = runner_image_drift(manifest['runnerImage'])
    if drift is not None:
        _chain_progress('runner-image', 'drift', stage=ident['stage'], arm=ident['arm'], **drift)
    if _authority is None:
        require(git_sources(workspace, ident['base'], ident['head']) == manifest['sources'], 'source inventory changed')
    else:
        _authority.check_manifest(directory, manifest)
    require(upstreams(args, ident, _authority) == manifest['upstream'], 'upstream checkpoint binding')
    if ident['stage']=='producer-common':
        check_producer_common_paths(workspace,set(manifest['files'])|set(manifest['directories'])|set(manifest['links'])|set(manifest['absent'])|set(manifest['payload'])|set(manifest['closedDirectories']))
    if ident['stage']=='producer-arm':
        check_producer_arm_paths(workspace,ident,set(manifest['files'])|set(manifest['directories'])|set(manifest['links'])|set(manifest['absent'])|set(manifest['payload'])|set(manifest['closedDirectories']))
    require(sha(directory/'payload.tar.gz') == manifest['archiveSha256'], 'archive digest')
    require(len(manifest['payload']) == len(set(manifest['payload'])), 'duplicate payload destination')
    check_system_jdks(manifest)
    readonly_jdks = [Path(p) for p in manifest.get('systemJdkRoots', [])]
    jdk_roots = [canonical(Path(p)) for p in manifest['jdkRoots'] if Path(p) not in readonly_jdks]
    for p in jdk_roots:
        require(p.is_relative_to(workspace) or (p.is_relative_to(Path('/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk')) and len(p.parts) >= 6), 'unsafe JDK root')
    def writable(name):
        p = canonical(Path(name))
        require(p not in SYSTEM_CACERTS and not any(p.is_relative_to(root) for root in readonly_jdks), 'read-only system JDK write prohibited')
        require(p.is_relative_to(workspace) or any(p.is_relative_to(root) for root in jdk_roots), 'outside restore roots')
        require(not p.is_relative_to(directory), 'payload overlaps checkpoint')
        require('.git' not in p.parts, 'Git metadata overwrite prohibited')
        return p
    # Check non-payload identities before writing anything; system tools are never
    # overwritten, and source checkout content is never supplied by the payload.
    for name, facts in manifest['files'].items():
        if name not in manifest['payload']:
            p = canonical(Path(name))
            require(dependency_identity(p, facts, _authority), 'preexisting dependency identity: '+name)
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
            digest = hashlib.sha256()
            with tar.extractfile(member) as src, p.open('xb') as dst:
                while chunk := src.read(1024*1024):
                    dst.write(chunk); digest.update(chunk)
            p.chmod(facts['mode'])
            require(digest.hexdigest() == facts['sha256'], 'restored payload digest')
            # check_files below independently reads the final on-disk bytes; the
            # transport stream check does not need an additional full-file read.
    for name in manifest['directories']:
        if any(Path(name).is_relative_to(root) for root in readonly_jdks):
            continue
        p = writable(name)
        mode = manifest['directoryModes'][name]
        require(type(mode) is int and 0 <= mode <= 0o777, 'safe directory mode')
        if _authority is not None and name in _authority.frozen_directories:
            require(p.is_dir() and not p.is_symlink() and stat.S_IMODE(p.stat().st_mode) == mode, 'frozen directory changed')
        else:
            p.mkdir(parents=True, exist_ok=True); p.chmod(mode)
    for name, target in manifest['links'].items():
        if name in manifest.get('systemJdkLinks', {}):
            continue
        p = Path(name)
        require(any(p.is_relative_to(root) and (p.parent/target).resolve().is_relative_to(root) for root in jdk_roots), 'unsafe tool symlink')
        if p.is_symlink():
            require(os.readlink(p) == target, 'existing tool symlink mismatch')
        else:
            require(not p.exists(), 'existing tool link destination'); p.parent.mkdir(parents=True, exist_ok=True); p.symlink_to(target)
    check_files(manifest, _authority)
    if ident['stage']=='producer-arm':check_producer_arm_packet(workspace,ident)
    if ident['stage']=='producer-common':producer_common_descriptor(workspace)
    if ident['stage'] == 'core-shard': check_shard_transport(workspace, ident, manifest['files'])
    if _authority is None:
        require(git_sources(workspace, ident['base'], ident['head']) == manifest['sources'], 'restored source changed')
    else:
        _authority.frozen_directories.update(manifest['directories'])
    return manifest


def _chain_progress(phase, event, **details):
    try:
        print('CHECKPOINT_PROGRESS '+json.dumps({'diagnosticOnly': True, 'phase': phase,
                                                'event': event, **details}), file=sys.stderr, flush=True)
    except (OSError, ValueError):
        pass  # A diagnostic stream is never proof authority.


@contextmanager
def _seal_stage(phase):
    started = time.monotonic()
    _chain_progress(phase, 'start')
    try:
        yield
    except BaseException:
        _chain_progress(phase, 'failed', elapsedSeconds=time.monotonic()-started)
        raise
    else:
        _chain_progress(phase, 'complete', elapsedSeconds=time.monotonic()-started)


class _RestoreChainAuthority:
    def __init__(self, sources):
        self.sources = sources
        self.manifests = {}
        self.verified = {}
        self.frozen_directories = set()
        self.preflight = True

    def check_manifest(self, directory, manifest):
        require(manifest == self.manifests[str(directory)] and manifest['sources'] == self.sources,
                'preflight checkpoint manifest changed')

    def digest(self, path, facts):
        name = str(path)
        require(path.is_file() and not path.is_symlink() and
                stat.S_IMODE(path.stat().st_mode) == facts['mode'] and path.stat().st_size == facts['size'],
                'chain file type/mode/size changed: '+name)
        if name in self.verified:
            require(self.verified[name] == facts, 'conflicting previously verified file')
            return facts['sha256']
        actual = sha(path)
        require(actual == facts['sha256'], 'chain file digest changed: '+name)
        self.verified[name] = dict(facts)
        return actual


def merge_producer(args):
    """Join one same-run common closure and its exact arms into legacy transport.

    The result carries original producer bytes; it grants no semantic acceptance.
    """
    require(args.stage=='producer' and args.arm=='producer' and not args.upstream and
            getattr(args,'shard_count',None) is None and getattr(args,'shard_index',None) is None,
            'fixed producer merge arguments')
    root=canonical(Path(args.directory).absolute());workspace=canonical(Path(args.workspace).absolute())
    arms=('C','B') if args.base_sha==ACCEPTED else ('C','A','B')
    require({p.name for p in root.glob('producer-arm-*')}=={'producer-arm-'+arm for arm in arms},
            'exact complete producer arm checkpoints')
    output=root/'producer-producer'
    require(not output.exists(),'fresh complete producer checkpoint required')
    common=root/'producer-common-common';common_ref=common/'manifest.json'
    entries=[]
    for stage,arm,directory,upstream in [('producer-common','common',common,[])]+[
            ('producer-arm',arm,root/('producer-arm-'+arm),[str(common_ref)]) for arm in arms]:
        values=vars(args).copy();values.update(stage=stage,arm=arm,directory=str(directory),upstream=upstream)
        entries.append(argparse.Namespace(**values))
    _restore_chain(args,entries)
    descriptor=producer_common_descriptor(workspace)
    for arm in arms:
        runtime=read(workspace/'native-pressure-artifacts'/arm/'runtime-manifest.json')
        require(all(runtime['toolchainIdentity'].get(k)==v for k,v in descriptor['toolchainIdentity'].items()),
                'same common producer toolchain')
    aliases=workspace/'native-pressure-artifacts/arm-directories.json'
    require(not aliases.exists(),'fresh producer arm aliases required')
    save(aliases,{'schema':'graphite.native-artifact-arm-directories.v1',
                  'directories':{'C':'C','A':'C' if args.base_sha==ACCEPTED else 'A','B':'B'},
                  'acceptedRevision':ACCEPTED,'baseRevision':args.base_sha,'candidateRevision':args.head_sha,
                  'status':'ARTIFACTS_ONLY_INDEPENDENT_PROOFS_REQUIRED','performanceAcceptance':False})
    values=vars(args).copy();values.update(directory=str(output))
    try:
        return seal(argparse.Namespace(**values))
    except BaseException:
        aliases.unlink(missing_ok=True)
        raise


def restore_core_chain(args):
    """Restore exactly producer, preparation and eight shards under one private authority.

    No consumer runs inside this transaction. Every final byte is independently
    rehashed before success; only earlier validated dependency bytes are reused.
    """
    require(args.stage == 'core-shard' and args.shard_count == 8 and
            getattr(args, 'shard_index', None) is None and not args.upstream,
            'fixed eight-shard core chain arguments')
    workspace = canonical(Path(args.workspace).absolute())
    root = canonical(Path(args.directory).absolute())
    entries = []
    producer = root/'producer-producer'; preparation = root/('core-prepare-'+args.arm)
    for stage, arm, directory, index, upstream in [
        ('producer', 'producer', producer, None, []),
        ('core-prepare', args.arm, preparation, None, [producer/'manifest.json']),
        *[('core-shard', args.arm,
           root/f'native-checkpoint-core-shard-{args.arm}-{index}-{args.run_id}-{args.run_attempt}', index,
           [producer/'manifest.json', preparation/'manifest.json']) for index in range(8)]]:
        values = vars(args).copy()
        values.update(stage=stage, arm=arm, directory=str(directory), shard_index=index,
                      shard_count=None if stage == 'producer' else 8, upstream=[str(p) for p in upstream])
        entries.append(argparse.Namespace(**values))
    _restore_chain(args, entries)
    return {'identity': {'stage': 'core-chain', 'arm': args.arm, 'head': args.head_sha, 'base': args.base_sha,
                        'runId': str(args.run_id), 'runAttempt': str(args.run_attempt), 'workspace': str(workspace),
                        'shardCount': 8}, 'proofAuthority': False, 'performanceAcceptance': False}


def restore_worker_chain(args):
    """Restore the fixed producer/preparation pair before a core worker runs."""
    require(args.stage == 'core-prepare' and args.arm in ('A', 'B') and
            type(getattr(args, 'shard_count', None)) is int and args.shard_count == 8 and
            getattr(args, 'shard_index', None) is None and not args.upstream,
            'fixed worker chain arguments')
    ident = identity(args)
    root = canonical(Path(args.directory).absolute())
    producer = root/'producer-producer'
    entries = []
    for stage, arm, directory, upstream in (
            ('producer', 'producer', producer, []),
            ('core-prepare', args.arm, root/('core-prepare-'+args.arm), [producer/'manifest.json'])):
        values = vars(args).copy()
        values.update(stage=stage, arm=arm, directory=str(directory), shard_index=None,
                      shard_count=None if stage == 'producer' else 8,
                      upstream=[str(path) for path in upstream])
        entries.append(argparse.Namespace(**values))
    _restore_chain(args, entries)
    return {'identity': {**ident, 'stage': 'worker-chain'},
            'proofAuthority': False, 'performanceAcceptance': False}


def restore_consumer_chain(args):
    """Restore the fixed complete core or JVM consumer input closure.

    The coordinator arm is always B; accepted-base aliasing determines whether
    distinct A checkpoints exist. This transport never establishes acceptance.
    """
    require(args.stage in ('core', 'jvm','jvm-inputs','jvm-pending','jvm-final') and args.arm == 'B' and
            getattr(args, 'shard_count', None) is None and
            getattr(args, 'shard_index', None) is None and not args.upstream,
            'fixed consumer chain arguments')
    workspace = canonical(Path(args.workspace).absolute())
    root = canonical(Path(args.directory).absolute())
    producer = root/'producer-producer'
    core_arms = ('B',) if args.base_sha == ACCEPTED else ('A', 'B')
    core_directories = [root/f'native-checkpoint-core-{arm}-{args.run_id}-{args.run_attempt}'
                        for arm in core_arms]
    specifications = [('producer', 'producer', producer, [])]
    early=args.stage in ('jvm-inputs','jvm-pending','jvm-final')
    preparation_directories=[root/f'native-checkpoint-core-prepare-{arm}-{args.run_id}-{args.run_attempt}' for arm in core_arms]
    if early:
        specifications.extend(('core-prepare',arm,directory,[producer/'manifest.json']) for arm,directory in zip(core_arms,preparation_directories))
    else:
        specifications.extend(('core',arm,directory,[producer/'manifest.json']) for arm,directory in zip(core_arms,core_directories))
    if args.stage in ('jvm','jvm-pending','jvm-final'):
        upstream=[producer/'manifest.json',*[directory/'manifest.json' for directory in (preparation_directories if early else core_directories)]]
        jvm_stage='jvm-pending' if early else 'jvm'
        specifications.extend((jvm_stage,arm,root/f'native-checkpoint-{jvm_stage}-{arm}-{args.run_id}-{args.run_attempt}',upstream) for arm in ('C',*core_arms))
    if args.stage=='jvm-final':
        specifications.extend(('core',arm,directory,[producer/'manifest.json']) for arm,directory in zip(core_arms,core_directories))
    entries = []
    for stage, arm, directory, upstream in specifications:
        values = vars(args).copy()
        values.update(stage=stage, arm=arm, directory=str(directory), shard_index=None,
                      shard_count=8 if stage=='core-prepare' else None, upstream=[str(p) for p in upstream])
        entries.append(argparse.Namespace(**values))
    _restore_chain(args, entries)
    return {'identity': {'stage': args.stage+'-consumer-chain', 'arm': args.arm,
                        'head': args.head_sha, 'base': args.base_sha,
                        'runId': str(args.run_id), 'runAttempt': str(args.run_attempt),
                        'workspace': str(workspace)},
            'proofAuthority': False, 'performanceAcceptance': False}


class _ClosedDirectoryIndex:
    """Lexical membership only; complete original inventories remain authoritative."""
    def __init__(self):
        self.members = {}

    def extend(self, inventories):
        for name, members in inventories.items():
            root = Path(name)
            allowed = frozenset(members)
            # Distinct manifest strings may normalize to the same lexical Path.
            # Every declaration must still hold, so combine by intersection.
            if root in self.members:
                allowed = self.members[root] & allowed
            self.members[root] = allowed

    def check(self, path):
        # parents excludes path itself, matching the original strict-descendant
        # predicate. Visit every ancestor so nested inventories all constrain it.
        for root in path.parents:
            members = self.members.get(root)
            if members is not None:
                require(str(path.relative_to(root)) in members, 'chain extends frozen closed directory')


def _restore_chain(args, entries):
    """Private transaction shared by fixed profiles; no caller-supplied manifest list.

    Preflight freezes all manifests and rejects conflicting writes before any
    restore. A fresh final authority independently hashes every unique file.
    No consumer executes until the complete transaction has returned.
    """
    workspace = canonical(Path(args.workspace).absolute())
    root = canonical(Path(args.directory).absolute())
    _chain_progress('preflight', 'start')
    sources = git_sources(workspace, args.base_sha, args.head_sha)
    authority = _RestoreChainAuthority(sources)
    prior_files = {}; prior_links = {}; prior_directories = {}; prior_closed = {}; prior_absent = set()
    closed_index = _ClosedDirectoryIndex()
    source_files = {name for source in sources.values() for name in source['files']}
    jdk_identity = None
    for entry in entries:
        ident = identity(entry); directory = canonical(Path(entry.directory)); manifest = read(directory/'manifest.json')
        require(manifest['schema'] == SCHEMA and manifest['identity'] == ident and
                manifest['proofAuthority'] is False and manifest['performanceAcceptance'] is False,
                'chain checkpoint identity')
        require(manifest['sources'] == sources and manifest['python'] == str(Path(sys.executable).resolve()) and
                same_runner_image(manifest['runnerImage']),
                'chain exact source/interpreter/runner')
        require(upstreams(entry, ident, authority) == manifest['upstream'], 'chain exact upstream binding')
        check_system_jdks(manifest)
        current_jdk = {key: manifest[key] for key in ('jdkRoots', 'systemJdkRoots', 'systemJdkLinks', 'systemJdkRootModes')}
        require(jdk_identity is None or current_jdk == jdk_identity, 'chain exact JDK identity')
        jdk_identity = current_jdk
        require(len(manifest['payload']) == len(set(manifest['payload'])) and
                set(manifest['payload']) <= set(manifest['files']), 'chain unique known payload files')
        require(not (set(manifest['payload']) & source_files), 'chain payload overwrites source input')
        # The explicit JVM final profile joins self-contained complete core to
        # its already restored preparation. Shared payload is never rewritten;
        # exact facts below and restore's byte check must match both inventories.
        duplicate_core = args.stage == 'jvm-final' and entry.stage == 'core'
        require(duplicate_core or not (set(manifest['payload']) & set(prior_files)), 'chain payload overwrites frozen input')
        kinds = {'file': set(manifest['files']), 'directory': set(manifest['directories']), 'link': set(manifest['links'])}
        require(not (kinds['file'] & kinds['directory'] or kinds['file'] & kinds['link'] or kinds['directory'] & kinds['link']),
                'chain conflicting entry types')
        for kind, names in kinds.items():
            for name in names:
                path = canonical(Path(name)) if kind != 'link' else Path(name)
                require(path.is_absolute() and '..' not in path.parts, 'chain canonical entry path')
                require(not path.is_relative_to(root), 'chain evidence overlaps checkpoint transport directory')
                require(name not in prior_absent and not any(str(parent) in prior_absent for parent in path.parents),
                        'chain writes previously absent dependency')
                require(not any(str(parent) in prior_files or str(parent) in prior_links for parent in path.parents),
                        'chain destination below frozen file or link')
                require(not (kind != 'file' and name in prior_files or kind != 'link' and name in prior_links or
                             kind != 'directory' and name in prior_directories), 'chain changes frozen entry type')
                closed_index.check(path)
        for name, facts in manifest['files'].items():
            require(name not in prior_files or prior_files[name] == facts, 'chain conflicting file attributes')
        for name, target in manifest['links'].items():
            require(name not in prior_links or prior_links[name] == target, 'chain conflicting frozen link')
        for name, mode in manifest['directoryModes'].items():
            require(name not in prior_directories or prior_directories[name] == mode, 'chain conflicting directory mode')
        for name, members in manifest['closedDirectories'].items():
            require(name not in prior_closed or prior_closed[name] == members, 'chain conflicting closed inventory')
        require(not (set(manifest['absent']) & (set(prior_files) | set(prior_links) | set(prior_directories))),
                'chain absent dependency conflicts with earlier output')
        prior_files.update(manifest['files']); prior_links.update(manifest['links'])
        prior_directories.update(manifest['directoryModes']); prior_closed.update(manifest['closedDirectories'])
        closed_index.extend(manifest['closedDirectories'])
        prior_absent.update(manifest['absent'])
        authority.manifests[str(directory)] = manifest
    authority.preflight = False
    _chain_progress('preflight', 'complete', checkpoints=len(entries))
    for entry in entries:
        # Free workspace bytes travel with the events: a chain that outgrows the hosted runner's disk
        # (run 911 restored a 93 GB closure) must say so at the entry where it did, not only by an ENOSPC.
        _chain_progress('restore', 'start', stage=entry.stage, shardIndex=entry.shard_index,
                        freeBytes=shutil.disk_usage(workspace).free)
        restore(entry, authority)
        _chain_progress('restore', 'complete', stage=entry.stage, shardIndex=entry.shard_index,
                        freeBytes=shutil.disk_usage(workspace).free)
    # Validate each inventory while reading each unique file exactly once at the
    # final boundary. A fresh authority cannot reuse an intermediate digest.
    _chain_progress('final-verification', 'start', uniqueFiles=len(prior_files))
    final = _RestoreChainAuthority(sources); final.preflight = False
    for entry in entries:
        directory = Path(entry.directory); manifest = read(directory/'manifest.json')
        authority.check_manifest(directory, manifest)
        check_files(manifest, final)
        ident = identity(entry)
        if entry.stage == 'core-shard': check_shard_transport(workspace, ident, manifest['files'])
    require(git_sources(workspace, args.base_sha, args.head_sha) == sources, 'chain final source changed')
    _chain_progress('final-verification', 'complete', uniqueFiles=len(final.verified))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('seal', 'restore', 'merge-producer', 'restore-core-chain', 'restore-worker-chain', 'restore-consumer-chain'))
    for name in ('stage', 'arm', 'directory', 'workspace', 'head-sha', 'base-sha', 'run-id', 'run-attempt'):
        parser.add_argument('--'+name, required=True)
    parser.add_argument('--upstream', action='append', default=[])
    parser.add_argument('--shard-count', type=int)
    parser.add_argument('--shard-index', type=int)
    args = parser.parse_args()
    value = (seal(args) if args.action == 'seal' else
             merge_producer(args) if args.action == 'merge-producer' else
             restore_core_chain(args) if args.action == 'restore-core-chain' else
             restore_worker_chain(args) if args.action == 'restore-worker-chain' else
             restore_consumer_chain(args) if args.action == 'restore-consumer-chain' else restore(args))
    print(json.dumps({'checkpointTransport': args.action, 'identity': value['identity'],
                      'proofAuthority': False, 'performanceAcceptance': False}))


if __name__ == '__main__':
    main()
