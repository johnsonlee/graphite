"""Explicit source-rule inputs bound to the actual per-pair writer manifests.

This only supplies provenance to the retained correction policies. It does not
assert independent bytecode inference or complete semantic equivalence.
"""
import hashlib
import json
from pathlib import Path
import re
from .legacy_wire import need


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_rule(ref, producer_spec):
    need(isinstance(ref, dict) and set(ref) == {'path', 'sha256'}, 'explicit pinned source rule required')
    pins = {str(Path(__file__)): sha(__file__)}

    def pinned(path, digest):
        path = Path(path)
        need(path.is_absolute() and not path.is_symlink(), 'absolute regular source authority path')
        need(isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest) and sha(path) == digest,
             'source authority pin changed')
        key = str(path)
        need(key not in pins or pins[key] == digest, 'conflicting source authority pin')
        pins[key] = digest
        return path

    rule_path = pinned(ref['path'], ref['sha256'])
    rule = json.loads(rule_path.read_text())
    need(rule['schema'] == 'graphite.local-array-source-rule.v1' and set(rule['arms']) == {'C', 'B'},
         'Local source rule schema and pair')
    need(producer_spec['schema'] == 'graphite.classfile-field-authority.v1' and
         set(producer_spec['arms']) == {'C', 'B'}, 'actual writer pair')
    for arm in ('C', 'B'):
        item = rule['arms'][arm]
        need(isinstance(item['revision'], str) and re.fullmatch('[0-9a-f]{40}', item['revision']),
             'full source rule revision')
        fixture_ref = producer_spec['arms'][arm]['fixtureManifest']
        fixture_path = pinned(fixture_ref['path'], fixture_ref['sha256'])
        fixture = json.loads(fixture_path.read_text())
        manifest_path = pinned(item['manifest'], item['manifestSha256'])
        manifest = json.loads(manifest_path.read_text())
        need(fixture['writerRevision'] == manifest['revision'] == item['revision'],
             'source rule actual writer revision')
        need(fixture['sourceManifestSha256'] == item['manifestSha256'],
             'source rule actual writer source manifest')
        root = Path(manifest['root'])
        need(root.is_absolute(), 'absolute writer source root')
        for field in ('folding', 'adapter'):
            path = pinned(item[field], item[field + 'Sha256'])
            relative = str(path.resolve().relative_to(root.resolve()))
            need(manifest['files'].get(str(path), manifest['files'].get(relative)) == item[field + 'Sha256'],
                 'Local rule source not in actual writer manifest')
    for path, digest in rule['diagnosis'].items():
        pinned(path, digest)
    return rule, pins
