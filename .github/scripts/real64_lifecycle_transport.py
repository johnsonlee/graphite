#!/usr/bin/env python3
"""Lossless same-run lifecycle transport; content sharing is not proof authority.

Only regular files under two fixed measurement roots can be restored. Archive
members use opaque numeric names; links target verified producer graph bytes or
an earlier restored member, never a path supplied to tar extraction.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import tarfile
import sys
sys.dont_write_bytecode = True
from native_pressure_checkpoint import require, sha, read, save, canonical

SCHEMA = 'graphite.real64-lifecycle.transport.v1'


def roots(workspace, operation):
    require(operation in ('construction', 'loading'), 'lifecycle transport operation')
    return [workspace/f'benchmark-results/real64-{operation}', workspace/f'native-pressure-producers-{operation}-measurement']


def identity(workspace, operation, head, base, run, attempt):
    import re
    require(all(re.fullmatch('[0-9a-f]{40}', x) for x in (head, base)) and
            str(run).isdigit() and str(attempt).isdigit(), 'exact lifecycle transport identity')
    return dict(workspace=str(workspace), operation=operation, head=head, base=base, run=str(run), attempt=str(attempt))


class _LiveDigests:
    """One read per unchanged regular inode inside one explicit boundary only."""
    def __init__(self): self.values = {}

    def facts(self, path):
        before = path.lstat()
        require(stat.S_ISREG(before.st_mode) and not stat.S_IMODE(before.st_mode) & ~0o777, 'regular safe lifecycle file')
        def identity(info):
            return (info.st_dev, info.st_ino, info.st_mode, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        token = identity(before)
        digest = self.values.get(token)
        if digest is None: digest = sha(path)
        require(identity(path.lstat()) == token, 'lifecycle inode drift during digest')
        self.values[token] = digest
        return {'sha256': digest, 'size': before.st_size, 'mode': stat.S_IMODE(before.st_mode)}


def graph_pool(packet):
    packet = read(packet)
    graph_roots = [Path(g['path']) for arm in packet['arms'].values() for g in arm['graphs']]
    result = {}; boundary = _LiveDigests()
    for name, digest in packet['pins'].items():
        if any(Path(name).is_relative_to(root) for root in graph_roots):
            p = canonical(Path(name)); facts = boundary.facts(p)
            require(facts['sha256'] == digest, 'producer graph identity drift')
            result[name] = facts
    return result


def key(facts):
    return facts['sha256'], facts['size'], facts['mode']


def inventory(selected):
    files, directories = {}, {}; boundary = _LiveDigests()
    def walk(p):
        # pathlib.rglob may suppress PermissionError and silently omit outputs.
        # Explicit enumeration must fail closed for every unreadable directory.
        require(not p.is_symlink(), 'no lifecycle symlinks')
        yield p
        if p.is_dir():
            with os.scandir(p) as entries:
                children = sorted((Path(entry.path) for entry in entries), key=str)
            for child in children: yield from walk(child)
    for root in selected:
        require(root.is_dir() and not root.is_symlink(), 'missing lifecycle root')
        for p in walk(root):
            info = p.lstat(); require(not p.is_symlink(), 'no lifecycle symlinks')
            if stat.S_ISDIR(info.st_mode):
                require(not stat.S_IMODE(info.st_mode) & ~0o777, 'safe lifecycle directory mode')
                directories[str(p)] = stat.S_IMODE(info.st_mode)
            else:
                require(stat.S_ISREG(info.st_mode) and not stat.S_IMODE(info.st_mode) & ~0o777, 'regular safe lifecycle file')
                files[str(p)] = boundary.facts(p)
    return files, directories


def seal(workspace, operation, packet, directory, ident):
    require(not directory.exists(), 'fresh lifecycle transport output')
    selected = roots(workspace, operation)
    require(not any(directory.is_relative_to(r) for r in selected), 'transport outside measurement')
    pool = graph_pool(packet); files, directories = inventory(selected)
    candidates = {key(f): p for p, f in pool.items()}
    payload, links = [], {}
    for p, facts in files.items():
        existing = candidates.get(key(facts))
        if existing: links[p] = existing
        else: payload.append(p); candidates[key(facts)] = p
    directory.mkdir(parents=True)
    archive = directory/'payload.tar.gz'
    with tarfile.open(archive, 'w:gz', compresslevel=1, dereference=True) as tar:
        for i, p in enumerate(payload):
            require(shutil.disk_usage(directory).free >= 64*1024**2, 'transport disk headroom')
            tar.add(p, arcname=f'files/{i:08d}', recursive=False)
    require(inventory(selected) == (files, directories) and graph_pool(packet) == pool, 'measurement drift while sealing')
    value = {'schema': SCHEMA, 'identity': ident, 'files': files, 'directories': directories,
             'payload': payload, 'links': links, 'archiveSha256': sha(archive),
             'logicalBytes': sum(f['size'] for f in files.values()),
             'uniquePayloadBytes': sum(files[p]['size'] for p in payload),
             'performanceAcceptance': False, 'proofAuthority': False}
    save(directory/'manifest.json', value)
    return value


def restore(workspace, operation, packet, directory, ident):
    value = read(directory/'manifest.json')
    require(value['schema'] == SCHEMA and value['identity'] == ident and
            value['performanceAcceptance'] is False and value['proofAuthority'] is False, 'lifecycle transport identity')
    selected = roots(workspace, operation)
    require(not any(p.exists() or p.is_symlink() for p in selected), 'fresh measurement restore roots')
    def destination(name):
        p = canonical(Path(name))
        require(any(p == r or p.is_relative_to(r) for r in selected) and '.git' not in p.parts,
                'lifecycle destination outside fixed roots')
        return p
    files = value['files']; payload = value['payload']; links = value['links']
    require(len(payload) == len(set(payload)) and set(payload).isdisjoint(links) and
            set(payload) | set(links) == set(files), 'exact lifecycle payload partition')
    for p in [*files, *value['directories']]: destination(p)
    for mode in value['directories'].values():
        require(type(mode) is int and 0 <= mode <= 0o777, 'safe lifecycle directory mode')
    for f in files.values():
        require(type(f['size']) is int and f['size'] >= 0 and type(f['mode']) is int and
                0 <= f['mode'] <= 0o777 and len(f['sha256']) == 64, 'safe lifecycle file metadata')
    pool = graph_pool(packet)
    for p, source in links.items():
        require(source in pool or source in payload, 'unknown or cyclic lifecycle content source')
        require(files[p] == (pool.get(source) or files.get(source)), 'linked byte identity')
    require(sha(directory/'payload.tar.gz') == value['archiveSha256'], 'lifecycle archive digest')
    require(shutil.disk_usage(workspace).free >= sum(files[p]['size'] for p in payload)+64*1024**2,
            'insufficient lifecycle unique-payload disk headroom')
    with tarfile.open(directory/'payload.tar.gz', 'r:gz') as tar:
        members = tar.getmembers()
        require([m.name for m in members] == [f'files/{i:08d}' for i in range(len(payload))] and
                all(m.isfile() and not m.issym() and not m.islnk() for m in members), 'unsafe lifecycle archive members')
        for m, name in zip(members, payload):
            p = destination(name); facts = files[name]
            require(m.size == facts['size'] and m.mode == facts['mode'], 'lifecycle member metadata')
            p.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(m) as src, p.open('xb') as out:
                shutil.copyfileobj(src, out, 1024*1024)
            p.chmod(facts['mode']); require(sha(p) == facts['sha256'], 'restored lifecycle digest')
    # Validate all link sources before changing any inode link count. The final
    # independent inventory below freshly hashes each resulting unique inode.
    link_boundary = _LiveDigests()
    for name, source in links.items():
        require(link_boundary.facts(canonical(Path(source))) == files[name], 'lifecycle link source drift')
    del link_boundary
    for name, source in links.items():
        p = destination(name); src = canonical(Path(source))
        require(src.is_file() and not src.is_symlink(), 'regular lifecycle link source')
        p.parent.mkdir(parents=True, exist_ok=True); os.link(src, p, follow_symlinks=False)
    for name in value['directories']: destination(name).mkdir(parents=True, exist_ok=True)
    for name in sorted(value['directories'], key=lambda p: len(Path(p).parts), reverse=True):
        destination(name).chmod(value['directories'][name])
    require(inventory(selected) == (files, value['directories']), 'restored lifecycle full byte inventory')
    return value


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('seal', 'restore'))
    for n in ('workspace', 'operation', 'packet', 'directory', 'head', 'base', 'run', 'attempt'):
        parser.add_argument('--'+n, required=True)
    a = parser.parse_args(); workspace = canonical(Path(a.workspace).absolute())
    ident = identity(workspace, a.operation, a.head, a.base, a.run, a.attempt)
    value = globals()[a.action](workspace, a.operation, a.packet, Path(a.directory).absolute(), ident)
    print(json.dumps({k: value[k] for k in ('logicalBytes', 'uniquePayloadBytes')}))
