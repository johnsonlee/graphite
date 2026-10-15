#!/usr/bin/env python3
"""Finite continuous-c4 HTTP pressure on real multi-graph workloads.

Request latency includes complete body spooling and canonical validation. Four
persistent workers refill independently; there is no batch validation barrier.
The mechanisms follow the reviewed shared-native64/181 protocols, but this Linux
runner uses Linux /proc + GNU time or macOS ps + BSD time, with explicit bounds.
Import is inert. No existing sequential/single-graph result becomes acceptance.
"""
from __future__ import annotations

import argparse
import contextlib
import gzip
import hashlib
import http.client
import json
import math
import datetime
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import sys
import threading
import time

import ci_digest_cache as digest_cache

from native_legal_response import CompiledDataflowOracle

PLAN_SCHEMA = 'graphite.multigraph-pressure.plan.v1'
RESULT_SCHEMA = 'graphite.multigraph-pressure.result.v1'
JVM_RAW_UNIVERSE_DIGEST = 'raw-universe-bytes-sha256-v1'
# User-approved tail protocol (2026-10-13): after the c4 pressure stage, every case whose pressure
# p50 sits under isolatedThresholdNs is repeated isolatedPerCase times on one worker, so a cheap
# case's tail is measured by the engine and not by which heavy requests it collided with.
SCHEDULE = {'concurrency': 4, 'warmupPerCase': 2, 'measuredPerCase': 20,
            'isolatedConcurrency': 1, 'isolatedPerCase': 40, 'isolatedThresholdNs': 100_000_000}
HEX = re.compile(r'[0-9a-f]{64}\Z')
CLEARED = {'_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS',
           'JAVA_OPTS', 'GRADLE_OPTS', 'CLASSPATH', 'JAVA', 'RUSTFLAGS',
           'CARGO_ENCODED_RUSTFLAGS', 'RAYON_NUM_THREADS', 'GRAPHITE_NO_FASTPATH',
           'MALLOC_CONF', '_RJEM_MALLOC_CONF', 'LD_PRELOAD', 'LD_LIBRARY_PATH',
           'PYTHONOPTIMIZE', 'PYTHONPATH', 'PYTHONHOME', 'PYTHONSTARTUP', 'PYTHONINSPECT'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def isolated_tasks(plan, pressure):
    """The isolated tail stage's tasks, derived from the pressure stage the cell has just run: every
    case whose pressure p50 sits under the threshold, repeated isolatedPerCase times, case-major."""
    schedule = plan['schedule']
    quantiles = pressure.get('caseStatistics', {})
    return [case for case in plan['cases']
            if case['id'] in quantiles and quantiles[case['id']]['p50Ns'] < schedule['isolatedThresholdNs']
            for _ in range(schedule['isolatedPerCase'])]


DECLARED_TYPE_TABLE = 'graph.types'


def expectation_variant(fixture):
    """The oracle set a fixture's graphs answer, read off their shape rather than their revision:
    'B' when every graph carries the declared-type table the candidate oracles describe, 'C' when
    none does (the exact4f shape, which main keeps until the feature merges). A mixed inventory is
    no shape at all."""
    files = set(fixture['files'])
    present = [str(Path(graph['path']) / DECLARED_TYPE_TABLE) in files for graph in fixture['graphs']]
    require(present and (all(present) or not any(present)), 'uniform declared-type table presence')
    return 'B' if all(present) else 'C'


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate JSON key: ' + key)
        result[key] = value
    return result


def parse(raw):
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda x: (_ for _ in ()).throw(ValueError('nonfinite ' + x)))


def read(path):
    raw = Path(path).read_bytes()
    if raw[:2] == b'\x1f\x8b':
        raw = gzip.decompress(raw)
    return parse(raw)


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'),
                      allow_nan=False).encode('utf-8')


def digest_bytes(raw):
    return hashlib.sha256(raw).hexdigest()


def sha(path):
    # One hash per file per job (ci_digest_cache): a recorded digest is returned only while the
    # file's kernel identity is unchanged; otherwise the bytes are hashed.
    return digest_cache.sha(path)


def save(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def typed(value):
    if value is None:
        return ['null']
    if type(value) in (bool, int, str):
        return [type(value).__name__, value]
    if type(value) is float:
        require(math.isfinite(value), 'nonfinite scalar')
        return ['float', value]
    if type(value) is list:
        return ['array', [typed(v) for v in value]]
    require(type(value) is dict, 'unexpected JSON value')
    return ['object', [[key, typed(value[key])] for key in sorted(value)]]


def typed_envelope(value, row_order):
    require(type(value) is dict and set(value) ==
            {'mode', 'graphs', 'graphCount', 'columns', 'rows', 'rowCount', 'limit'}, 'JVM envelope')
    require(value['mode'] == 'cross-graph', 'JVM mode')
    require(type(value['graphs']) is list and len(value['graphs']) >= 2 and
            all(type(g) is str for g in value['graphs']) and
            len(set(value['graphs'])) == len(value['graphs']), 'multi-graph envelope')
    require(type(value['graphCount']) is int and value['graphCount'] == len(value['graphs']), 'graphCount')
    require(type(value['columns']) is list and all(type(c) is str for c in value['columns']), 'columns')
    require(type(value['rows']) is list and all(type(r) is dict for r in value['rows']), 'rows')
    require(type(value['rowCount']) is int and value['rowCount'] == len(value['rows']), 'rowCount')
    require(type(value['limit']) is int and value['limit'] > 0 and value['rowCount'] <= value['limit'], 'limit')
    result = dict(value)
    require(row_order in ('exact', 'outer-multiset'), 'row ordering policy')
    if row_order == 'outer-multiset':
        result['rows'] = sorted(value['rows'], key=lambda row: canonical(typed(row)))
    return typed(result)


def validate_response(raw, case, arm, compiled_legal=None):
    value = parse(raw)
    oracle = case['oracleByArm'][arm]
    if oracle['kind'] == 'native-full-json-sha256-v1':
        require(type(value) is dict and type(value.get('columns')) is list and
                all(type(c) is str for c in value['columns']), 'native columns')
        rows = value.get('rows')
        require(type(rows) is list and all(type(r) is dict for r in rows), 'native rows')
        require('rowCount' not in value or (type(value['rowCount']) is int and
                                          value['rowCount'] == len(rows)), 'native row count')
        require(len(rows) == oracle['rows'], 'oracle row count')
        actual = digest_bytes(canonical(value))
        require(actual == oracle['digest'], 'complete native oracle mismatch')
    elif oracle['kind'] == 'native-complete-legal-limit-multiset-v1':
        require(isinstance(compiled_legal, CompiledDataflowOracle), 'legal oracle must be compiled before requests')
        compiled_legal.validate(value)
        actual = digest_bytes(canonical(value))
    elif oracle['kind'] == 'jvm-complete-legal-limit-multiset-v1':
        from jvm_pressure_oracles import CompiledLegalLimitOracle
        require(isinstance(compiled_legal, CompiledLegalLimitOracle), 'JVM legal oracle must be compiled before requests')
        compiled_legal.validate(value)
        # This is the observed body identity, not the typed universe digest.
        actual = digest_bytes(canonical(value))
    elif oracle['kind'] == 'jvm-full-typed-envelope-v1':
        actual = digest_bytes(canonical(typed_envelope(value, oracle['rowOrder'])))
        expected = typed_envelope(oracle['value'], oracle['rowOrder'])
        require(value['graphs'] == case['targetGraphIds'], 'actual JVM query scope')
        require(actual == oracle['digest'] == digest_bytes(canonical(expected)), 'complete JVM oracle mismatch')
    else:
        raise ValueError('unknown response oracle kind')
    return {'canonicalSha256': actual, 'rows': len(value['rows'])}


def _validate_jvm_raw_json(value):
    """Keep typed-digest scalar rejection without constructing a tagged tree."""
    if value is None or type(value) in (bool, int):
        return
    if type(value) is str:
        for offset in range(0, len(value), 65536):
            value[offset:offset + 65536].encode('utf-8', errors='strict')
        return
    if type(value) is float:
        require(math.isfinite(value), 'nonfinite JVM JSON scalar needs separate serializer authority')
        return
    if type(value) is list:
        for item in value: _validate_jvm_raw_json(item)
        return
    require(type(value) is dict and all(type(k) is str for k in value), 'JSON object with string keys')
    for name, item in value.items():
        _validate_jvm_raw_json(name)
        _validate_jvm_raw_json(item)


def jvm_oracle_value(oracle, *, pins=None):
    """Read one bound JVM universe; never retain its JSON in the pressure plan."""
    require(oracle['kind'] == 'jvm-complete-legal-limit-multiset-v1', 'JVM legal oracle kind')
    require(('value' in oracle) != ('valueRef' in oracle), 'exactly one JVM universe representation')
    raw_identity = 'digestKind' in oracle
    if raw_identity:
        require(oracle['digestKind'] == JVM_RAW_UNIVERSE_DIGEST, 'known JVM universe digest kind')
        require('valueRef' in oracle, 'raw JVM universe identity requires a reference')
    if 'value' in oracle:
        return oracle['value']
    ref = oracle['valueRef']
    require(type(ref) is dict and set(ref) == {'path', 'sha256'} and
            type(ref['path']) is str and valid_digest(ref['sha256']), 'exact JVM universe reference')
    if raw_identity:
        require(oracle.get('digest') == ref['sha256'], 'raw JVM universe digest matches exact reference')
    path = Path(ref['path'])
    require(path.is_absolute() and str(path.resolve()) == ref['path'] and
            path.is_file() and not path.is_symlink(), 'canonical regular JVM universe reference')
    require(pins is not None and pins.get(ref['path']) == ref['sha256'], 'bound JVM universe reference')
    if 'valueProof' in oracle:
        require(oracle['valueProof'] == ref, 'JVM universe reference differs from raw proof')
    raw = path.read_bytes()
    require(digest_bytes(raw) == ref['sha256'], 'JVM universe bytes changed')
    value = parse(raw)
    if raw_identity: _validate_jvm_raw_json(value)
    return value


def _compile_jvm_response_oracle(case, oracle, pins):
    # This frame releases the raw tree before the next case is loaded. Only the
    # immutable compiled index survives; no file IO occurs during requests.
    import jvm_pressure_oracles as jvm
    definitions = {item['id']: item for item in jvm.cases(case['request']['body']['graphs'])}
    require(case['id'] in definitions, 'known complete35 JVM request definition')
    definition = definitions[case['id']]
    for field in ('request', 'targetGraphIds'):
        require(jvm.typed(case[field]) == jvm.typed(definition[field]), 'exact JVM request/scope binding')
    for field in ('family', 'registeredGraphIds', 'requestedGraphIds', 'columns', 'effectiveLimit',
                  'requestSha256', 'querySha256'):
        if field in case:
            require(jvm.typed(case[field]) == jvm.typed(definition[field]), 'exact supplied JVM definition field: ' + field)
    value = jvm_oracle_value(oracle, pins=pins)
    if 'digestKind' not in oracle:
        require(jvm.digest(value) == oracle['digest'], 'complete typed JVM universe digest')
    validator = jvm.CompiledLegalLimitOracle(value, definition)
    require(type(oracle['rows']) is int and oracle['rows'] == min(validator.total, validator.limit),
            'JVM legal oracle row count')
    return validator


def compile_response_validator(cases, arm, *, pins=None):
    """Prepare immutable legal row counters once, outside every request boundary."""
    compiled = {}
    require(len({case['id'] for case in cases}) == len(cases), 'unique compiled cases')
    for case in cases:
        oracle = case['oracleByArm'][arm]
        if oracle['kind'] == 'jvm-complete-legal-limit-multiset-v1':
            compiled[case['id']] = _compile_jvm_response_oracle(case, oracle, pins)
            continue
        if oracle['kind'] != 'native-complete-legal-limit-multiset-v1':
            continue
        require(digest_bytes(canonical(oracle['value'])) == oracle['digest'], 'complete legal oracle digest')
        query_case = dict(case, family='full-slow-shape-catalog',
                          querySha256=digest_bytes(case['request']['body']['query'].encode()))
        validator = CompiledDataflowOracle(oracle['value'], query_case)
        require(type(oracle['rows']) is int and oracle['rows'] == min(validator.total, 50), 'legal oracle row count')
        compiled[case['id']] = validator
    return lambda raw, case: validate_response(raw, case, arm, compiled.get(case['id']))


def valid_digest(value):
    return type(value) is str and HEX.fullmatch(value) is not None


def pinned_authority_metadata(packet, ref):
    require(packet['pins'].get(ref['path']) == ref['sha256'] == sha(ref['path']),
            'actual authority metadata changed')
    return read(ref['path'])


def candidate_authority(packet):
    """Join original partial B artifacts to the independently completed persisted-wire oracle."""
    refs = packet['candidateAuthority']
    producer, artifact, schema = [pinned_authority_metadata(packet, refs[k]) for k in
                                  ('producer', 'artifactAudit', 'schemaAudit')]
    arm = packet['arms']['B']
    require(producer['schema'] == 'graphite.current-b-producer.v1' and
            producer['status'] == 'ARTIFACTS_COMPLETE_ORACLE_AUTHORITY_PENDING' and
            artifact['schema'] == 'graphite.current-b-independent-artifact-audit.v1' and
            artifact['status'] == 'VERIFIED_ARTIFACTS_SCHEMA_ORACLE_PENDING' and
            producer['revision'] == artifact['revision'] == arm['revision'] and
            producer['acceptanceEligible'] is False and artifact['acceptanceEligible'] is False,
            'original partial B producer and independent artifact statuses')
    require(refs['artifactAudit'] == packet['buildAudits']['B'] == packet['fixtureAudits']['B'] and
            artifact['producerPacket'] == refs['producer'] and
            artifact['sourceManifest'] == arm['sourceManifest'] and
            artifact['runtimeManifest'] == arm['runtimeManifest'] and
            artifact['graphs'] == arm['graphs'] and
            artifact['completeAssertions'] == 5 and artifact['pendingSchemaOracle'] is True and
            artifact['allCorrectnessPassed'] is False,
            'exact B artifact/fixture/partial correctness links')
    require(producer['runtimeManifest'] == pinned_authority_metadata(packet, arm['runtimeManifest']) and
            producer['correctness'] == artifact['correctness'] and
            producer['finalIdentity'] == artifact['finalIdentity'] and
            set(producer['finalIdentity']) == {'source', 'inputs', 'runtime', 'fixtures', 'originalArtifacts', 'configuration'} and
            all(v == 'PASS' for v in producer['finalIdentity'].values()), 'completed B artifact closure')
    require(schema['status'] == 'PASS_INDEPENDENT_COMPLETED_SCHEMA_OUTPUT_AUDIT' and
            schema['graphs'] == 64 and schema['completeOrderedResponseAndProvenanceEqual'] is True and
            schema['metadataSourceBeforeAfterStable'] is True and
            schema['producerOriginalPartialStatusPreserved'] is True, 'completed independent B schema audit')
    for audit, key in [(artifact, 'artifactAuditor'), (schema, 'schemaAuditor')]:
        require(audit['auditor'] == refs[key], 'explicit reviewed independent auditor identity')
        ref = refs[key]
        require(packet['pins'].get(ref['path']) == ref['sha256'] == sha(ref['path']),
                'independent auditor source pin')
    for ref in [refs['producer'], refs['artifactAudit'], arm['sourceManifest'], arm['runtimeManifest'],
                artifact['fixtureManifest'], schema['plan'], schema['actualDerivationAudit']]:
        require(schema['checkedMetadataAndSourcePins'].get(ref['path']) == ref['sha256'],
                'schema audit must bind the exact producer and derivation')
    plan = pinned_authority_metadata(packet, schema['plan'])
    derived = pinned_authority_metadata(packet, schema['actualDerivationAudit'])
    require(plan['revision'] == arm['revision'] and plan['performanceClaim'] is False and
            [{'id': g['id'], 'path': g['path']} for g in plan['graphs']] == arm['graphs'],
            'schema authority actual complete graph scope')
    require(all(packet['pins'].get(path) == digest for path, digest in plan['pins'].items()),
            'complete schema derivation input closure')
    require(derived['status'] == 'PASS_INDEPENDENT_PERSISTED_NODE_SCHEMA_ORACLE' and
            derived['planSha256'] == schema['plan']['sha256'] and derived['errors'] == [] and
            derived['graphs'] == 64 and derived['expectedCanonicalSha256'] == schema['expectedCanonicalSha256'],
            'independent complete persisted-node derivation')
    cases = artifact['caseEvidence']
    require(len(cases) == 6 and len({c['id'] for c in cases}) == 6 and
            sum(c['status'] == 'PASS' for c in cases) == 5, 'six original B captured cases')
    selected = [c for c in cases if c['id'] == 'schema-key-histogram']
    require(len(selected) == 1 and selected[0]['status'] == 'CAPTURED_UNVERIFIED' and
            selected[0]['bodySha256'] == plan['capturedBody']['sha256'] and
            selected[0]['validation']['capturedCanonicalSha256'] == schema['expectedCanonicalSha256'] and
            selected[0]['validation']['capturedRows'] == schema['completeRows'] and
            plan['capturedBody']['path'] == str(Path(refs['producer']['path']).parent /
                                              'native-correctness/schema-key-histogram.body'),
            'schema proof applies to the original captured full response')
    return {'digest': schema['expectedCanonicalSha256'], 'rows': schema['completeRows'],
            'proof': refs['schemaAudit'], 'query': plan['query']}


def same_accepted_artifacts(plan, arm_id):
    left, right = plan['arms'][arm_id], plan['arms']['C']
    required = ('revision', 'sourceManifest', 'runtimeManifest', 'runtimeFiles',
                'graphs', 'readiness', 'serverArgv')
    return (all(k in left and k in right and left[k] == right[k] for k in required) and
            left.get('patchSha256') == right.get('patchSha256'))


def native_oracle_expected(plan, source, arm_id, candidate=None):
    if arm_id == 'C' or (arm_id == 'A' and same_accepted_artifacts(plan, 'A')):
        return source['expected']['base']
    binding = plan['nativeOracleBindings'][arm_id]
    arm = plan['arms'][arm_id]
    require(binding['revision'] == arm['revision'] and binding['graphs'] == arm['graphs'] and
            binding['sourceManifestSha256'] == arm['sourceManifest']['sha256'] and
            binding['runtimeManifestSha256'] == arm['runtimeManifest']['sha256'],
            'revision-specific native oracle identity')
    expected = binding['cases'][source['id']]
    require(set(expected) == {'digest', 'rows'} and valid_digest(expected['digest']) and
            type(expected['rows']) is int and expected['rows'] >= 0, 'complete explicit native oracle')
    proof = [p for p in plan['proofs'] if p.get('role') == 'independent-correctness' and p.get('arm') == arm_id]
    require(len(proof) == 1 and proof[0]['bindings']['oracleDigests'][source['id']] == expected['digest'],
            'explicit arm oracle independent audit binding')
    if arm_id == 'B':
        trusted = source['expected']['candidate']
        if candidate is not None and source['id'] == 'schema-key-histogram':
            require(candidate['query'] == source['request']['body']['query'], 'exact schema query authority')
            trusted = {k: candidate[k] for k in ('digest', 'rows')}
        require(expected == trusted, 'revision-specific candidate oracle authority')
    return expected


def declared_query_scope(source, loaded_graph_ids):
    """A loaded registry may be broader than an independently proven query scope."""
    targets = source.get('targetGraphIds', loaded_graph_ids)
    require(type(targets) is list and all(type(g) is str for g in targets), 'declared query graph IDs')
    require(len(targets) >= 2 and len(targets) == len(set(targets)), 'actual unique multi-graph query scope')
    require(set(targets) <= set(loaded_graph_ids), 'query scope outside loaded registry')
    return targets


def validate_plan(plan, catalog=None):
    require(__debug__, 'Python optimization forbidden')
    require(plan.get('schema') == PLAN_SCHEMA and plan.get('operation') == 'query', 'plan schema/operation')
    require(plan.get('engine') in ('native', 'jvm'), 'engine')
    if 'loadingMeasurement' in plan:
        from native_loading import validate_declaration
        validate_declaration(plan)
    if 'pendingComparabilityAuthority' in plan:
        require(plan['engine'] == 'native' and plan.get('comparabilityPending') is True and
                not any(k in plan for k in ('producerAuthority', 'correctedProducerAuthority', 'jvmProducerAuthority')),
                'explicit separate pending native comparability mode')
    elif 'pendingJvmComparabilityAuthority' in plan:
        require(plan['engine']=='jvm' and plan.get('comparabilityPending') is True and
                not any(k in plan for k in ('producerAuthority','correctedProducerAuthority','jvmProducerAuthority')),
                'explicit separate pending JVM comparability mode')
    else:
        require('comparabilityPending' not in plan, 'pending scope requires explicit producer authority')
    require(type(plan.get('pins')) is dict and plan['pins'], 'closed input pins required')
    for path, digest in plan['pins'].items():
        require(Path(path).is_absolute() and valid_digest(digest), 'absolute input pin')
    control = plan['catalog']
    require(plan['pins'].get(control['path']) == control['sha256'], 'catalog binding')
    if catalog is None:
        require(sha(control['path']) == control['sha256'], 'catalog drift')
        catalog = read(control['path'])
    require(catalog['schema'] == 'graphite.multigraph-pressure.cases.v1', 'catalog schema')
    declared = catalog['engines'][plan['engine']]
    require(plan['schedule'] == SCHEDULE, 'fixed c4/2/20 schedule with the c1/40 isolated tail stage')
    require(set(plan['arms']) == {'C', 'A', 'B'}, 'accepted baseline, parent and candidate required')
    require([c['arm'] for c in plan['cells']] == list('CABBAC'), 'fixed paired order')
    require(len({c['id'] for c in plan['cells']}) == 6 and
            len({c['port'] for c in plan['cells']}) == 6, 'unique cell identities/ports')
    require(all(type(c['port']) is int and 1024 <= c['port'] <= 65535 for c in plan['cells']), 'port')
    require(plan['comparisonPairs'] == {'parent': [[1, 2], [4, 3]], 'acceptedBaseline': [[0, 2], [5, 3]]}, 'fixed comparisons')
    roles = {'C': 'accepted-baseline', 'A': 'parent', 'B': 'candidate'}
    for name, arm in plan['arms'].items():
        require(arm['role'] == roles[name] and re.fullmatch('[0-9a-f]{40}', arm['revision']), 'exact source revision')
        require(name != 'C' or arm['revision'] == '4f2ccf33b969e684972e56b5e810034e6e67c1b3', 'accepted pre-regression baseline')
        require(arm.get('patchSha256') is None or valid_digest(arm['patchSha256']), 'source patch')
        for field in ('runtimeManifest', 'sourceManifest'):
            ref = arm[field]
            require(plan['pins'].get(ref['path']) == ref['sha256'], 'source/runtime manifest pin')
        require(arm['runtimeFiles'] and all(plan['pins'].get(p) == h for p, h in arm['runtimeFiles'].items()), 'complete runtime file pins')
        require(type(arm['runtimeRoots']) is list, 'runtime closed-directory inventory')
        require(arm['graphs'] and [g['id'] for g in arm['graphs']] == declared['graphIds'], 'exact real graph scope')
        require(len(declared['graphIds']) >= 2 and len(set(declared['graphIds'])) == len(declared['graphIds']), 'singleton forbidden')
        for graph in arm['graphs']:
            require(Path(graph['path']).is_absolute(), 'absolute graph root')
            require(any(Path(p).is_relative_to(Path(graph['path'])) for p in plan['pins']), 'graph inventory missing')
        expected_ready = arm['readiness']['expected']
        require(arm['readiness']['path'] == '/api/graphs' and expected_ready['count'] == len(arm['graphs']), 'readiness scope')
        require({g['id']: g['path'] for g in expected_ready['graphs']} == {g['id']: str(Path(g['path']).resolve()) for g in arm['graphs']} and len(expected_ready['graphs']) == len(arm['graphs']), 'readiness exact graph inventory')
        argv = arm['serverArgv']
        require(type(argv) is list and argv and all(type(a) is str and a for a in argv), 'direct server argv')
        require(Path(argv[0]).is_absolute() and argv[0] in plan['pins'], 'pinned direct executable')
        require(not any(a in argv for a in ('--graph', '--data', '--port', '--max-concurrent-cypher')), 'runner owns server flags')
        if plan['engine'] == 'jvm':
            require(Path(argv[0]).name == 'java', 'direct JVM only')
            heaps = [a for a in argv if a.startswith('-Xmx')]
            require(len(heaps) == 1 and re.fullmatch(r'-Xmx[1-9][0-9]*[mgMG]', heaps[0]), 'one explicit JVM heap')
            heap = int(heaps[0][4:-1]) * (1024 ** (3 if heaps[0][-1].lower() == 'g' else 2))
            require(heap <= 8 * 1024 ** 3 and heap == arm['maxHeapBytes'], 'JVM heap ceiling')
            require(not any(a.startswith(('-XX:MaxRAM', '-XX:MaxHeapSize', '@', '-javaagent', '-agentlib')) for a in argv), 'JVM override')
            require(argv.count('-cp') == 1, 'one direct classpath')
            # The query distribution retains MainKt/ServeCommand; its minimized
            # JAR does not retain the standalone explorer's ExploreMainKt.
            entrypoint = argv[argv.index('-cp') + 2:]
            require(entrypoint in (['io.johnsonlee.graphite.cli.ExploreMainKt'],
                                   ['io.johnsonlee.graphite.cli.MainKt', 'serve']),
                    'reviewed JVM entrypoint')
            for entry in argv[argv.index('-cp') + 1].split(os.pathsep):
                require(Path(entry).is_absolute() and (entry in arm['runtimeFiles'] or entry in arm['runtimeRoots']), 'closed classpath entry')
        else:
            require(len(argv) == 2 and argv[1] == 'serve', 'direct native serve')
    if plan['engine'] == 'jvm':
        require(len({(str(Path(arm['serverArgv'][0]).resolve()), plan['pins'][arm['serverArgv'][0]])
                     for arm in plan['arms'].values()}) == 1, 'matched resolved JDK executable and hash')
        require(len({arm['maxHeapBytes'] for arm in plan['arms'].values()}) == 1, 'matched heaps')
        flags = [[a for a in arm['serverArgv'][1:] if a.startswith('-') and a != '-cp'] for arm in plan['arms'].values()]
        require(flags[0] == flags[1] == flags[2], 'matched JVM flags')
    require([c['id'] for c in plan['cases']] == [c['id'] for c in declared['cases']], 'complete declared case inventory')
    candidate = None
    jvm_packet = None
    if 'jvmProducerAuthority' in plan or 'pendingJvmComparabilityAuthority' in plan:
        require(plan['engine'] == 'jvm' and 'correctedProducerAuthority' not in plan and
                'producerAuthority' not in plan, 'explicit separate JVM producer proof mode')
        from prepare_jvm_pressure_plan import verify_bundle, pressure_arms
        packet = pinned_authority_metadata(plan, plan['pendingJvmComparabilityAuthority' if 'pendingJvmComparabilityAuthority' in plan else 'jvmProducerAuthority'])
        jvm_packet = verify_bundle(packet, plan['arms']['A']['revision'], plan['arms']['B']['revision'])
        require(pressure_arms(jvm_packet) == plan['arms'] and
                all(plan['pins'].get(p) == h for p, h in jvm_packet['pins'].items()),
                'actual JVM source runtime fixture and oracle closure')
    if plan['engine'] == 'native' and 'producerAuthority' in plan:
        packet = pinned_authority_metadata(plan, plan['producerAuthority'])
        require(all(packet['arms'][k] == plan['arms'][k] for k in 'CAB') and
                all(plan['pins'].get(p) == h for p, h in packet['pins'].items()),
                'original producer packet and full input closure')
        candidate = candidate_authority(packet)
    for case, source in zip(plan['cases'], declared['cases']):
        require(typed(case['request']) == typed(source['request']) and
                case['targetGraphIds'] == declared_query_scope(source, declared['graphIds']), 'trusted request/scope')
        require(case['catalogId'] == source['id'], 'catalog case ID')
        require(set(case['oracleByArm']) == {'C', 'A', 'B'}, 'all arm oracles')
        for name, oracle in case['oracleByArm'].items():
            require(oracle['kind'] == source['oracleKind'] and valid_digest(oracle['digest']), 'oracle kind/digest')
            ref = oracle['proof']
            require(plan['pins'].get(ref['path']) == ref['sha256'], 'independent oracle proof pin')
            if plan['engine'] == 'native':
                expected = native_oracle_expected(plan, source, name, candidate)
                require({'digest': oracle['digest'], 'rows': oracle['rows']} == expected, 'authoritative native oracle')
                if oracle['kind'] == 'native-complete-legal-limit-multiset-v1':
                    value_ref = oracle['valueProof']
                    require(plan['pins'].get(value_ref['path']) == value_ref['sha256'] == sha(value_ref['path']), 'legal expected payload pin')
                    require(typed(read(value_ref['path'])) == typed(oracle['value']) and
                            digest_bytes(canonical(oracle['value'])) == oracle['digest'], 'independent complete legal payload')
                    compile_response_validator([case], name)
                if candidate is not None and name == 'B' and source['id'] == 'schema-key-histogram':
                    require(ref == candidate['proof'], 'current B oracle must retain completed schema authority')
            elif oracle['kind'] == 'jvm-complete-legal-limit-multiset-v1':
                require(jvm_packet is not None, 'complete JVM legal universe requires actual producer authority')
                observed = jvm_packet['arms'][name]['cases'][plan['cases'].index(case)]
                require(observed['id'] == case['id'] and observed['request'] == case['request'] and
                        observed['targetGraphIds'] == case['targetGraphIds'] and
                        typed(observed['oracle']) == typed(oracle), 'actual independently audited JVM oracle')
                value_ref = oracle['valueProof']
                require(plan['pins'].get(value_ref['path']) == value_ref['sha256'], 'complete JVM universe payload pin')
                if 'valueRef' in oracle:
                    require(oracle['valueRef'] == value_ref, 'complete JVM universe reference matches raw proof')
                else:
                    require(value_ref['sha256'] == sha(value_ref['path']) and
                            typed(read(value_ref['path'])) == typed(oracle['value']), 'complete JVM universe payload pin')
                compile_response_validator([case], name, pins=plan['pins'])
            else:
                require(oracle['rowOrder'] == source['rowOrder'], 'fixed row ordering')
                require(oracle['value'] == source['expected'], 'independent JVM expected envelope')
                require(digest_bytes(canonical(typed_envelope(oracle['value'], oracle['rowOrder']))) == oracle['digest'], 'JVM typed expected digest')
    require(plan['proofs'] and all((p['status'] == 'PASS' or p['status'].startswith('PASS_')) and plan['pins'].get(p['path']) == p['sha256'] for p in plan['proofs']), 'required fixture/build/oracle proofs')
    validate_proof_claims(plan, jvm_packet)
    coverage = plan['coverage']
    require(coverage['coveredFamilies'] == declared['coveredFamilies'], 'coverage must come from catalog')
    require(set(coverage['requiredFamilies']) >= set(declared['requiredFamilies']), 'required families cannot shrink')
    require(coverage['unavailableFamilies'] == sorted(set(coverage['requiredFamilies']) - set(coverage['coveredFamilies'])), 'missing coverage must be explicit')
    require(coverage['unavailableOperations'] == ['construction', 'loading'], 'query cannot pass other operations')
    limits = plan['limits']
    require(set(limits) == {'requestSeconds', 'stageSeconds', 'readinessSeconds', 'bodyBytes', 'rssIntervalSeconds'}, 'limits fields')
    require(all(type(v) in (int, float) and math.isfinite(v) and v > 0 for v in limits.values()), 'finite limits')
    require(limits['requestSeconds'] <= 900 and limits['stageSeconds'] <= 14400 and limits['readinessSeconds'] <= 600, 'bounded deadlines')
    require(type(limits['bodyBytes']) is int and limits['bodyBytes'] <= 256 * 1024 ** 2 and
            .005 <= limits['rssIntervalSeconds'] <= .2, 'bounded body/sampler')
    return plan


def validate_proof_claims(plan, jvm_packet=None):
    """A PASS receipt alone is not source/fixture/oracle authority for any arm.

    The trusted binder supplies receipts with these explicit claims, retaining
    the hashes of upstream producer audits. Missing claims never become defaults.
    """
    for arm_id, arm in plan['arms'].items():
        runtime_claims = {
            'revision': arm['revision'], 'patchSha256': arm.get('patchSha256'),
            'sourceManifestSha256': arm['sourceManifest']['sha256'],
            'runtimeManifestSha256': arm['runtimeManifest']['sha256'],
            'runtimeFiles': arm['runtimeFiles']}
        oracle_claims = {
            'graphIds': [g['id'] for g in arm['graphs']],
            'requestDigests': {c['id']: digest_bytes(canonical(c['request'])) for c in plan['cases']},
            'oracleDigests': {c['id']: c['oracleByArm'][arm_id]['digest'] for c in plan['cases']}}
        digest_kinds = {}
        for case in plan['cases']:
            oracle = case['oracleByArm'][arm_id]
            if 'digestKind' in oracle:
                require(plan['engine'] == 'jvm' and
                        oracle['kind'] == 'jvm-complete-legal-limit-multiset-v1' and
                        oracle['digestKind'] == JVM_RAW_UNIVERSE_DIGEST, 'known JVM oracle digest claim')
                digest_kinds[case['id']] = oracle['digestKind']
        if digest_kinds: oracle_claims['oracleDigestKinds'] = digest_kinds
        for role, claims in [('source-runtime', runtime_claims), ('independent-correctness', oracle_claims)]:
            matches = [proof for proof in plan['proofs'] if proof['role'] == role and proof.get('arm') == arm_id]
            require(len(matches) == 1, 'one actual ' + role + ' proof per arm')
            proof = matches[0]
            require(typed(proof.get('bindings')) == typed(claims), 'incomplete ' + role + ' claims')
            require(proof.get('upstream') and all(plan['pins'].get(path) == digest for path, digest in proof['upstream'].items()), 'producer audit pins')
            if role == 'source-runtime':
                for ref in [arm['sourceManifest'], arm['runtimeManifest']]:
                    require(proof['upstream'].get(ref['path']) == ref['sha256'], 'source/runtime upstream link')
            else:
                for case in plan['cases']:
                    ref = case['oracleByArm'][arm_id]['proof']
                    require(proof['upstream'].get(ref['path']) == ref['sha256'], 'independent full-body oracle upstream')
    pending = 'pendingComparabilityAuthority' in plan or 'pendingJvmComparabilityAuthority' in plan
    fixture_role = 'fixture-inventory' if pending else 'fixture-equivalence'
    matches = [proof for proof in plan['proofs'] if proof['role'] == fixture_role]
    require(len(matches) == 1 and not any(p['role'] ==
            ('fixture-equivalence' if pending else 'fixture-inventory') for p in plan['proofs']),
            'one complete ' + fixture_role + ' proof')
    roots = {Path(g['path']) for arm in plan['arms'].values() for g in arm['graphs']}
    expected = {'graphsByArm': {key: arm['graphs'] for key, arm in plan['arms'].items()},
                'fixtureFiles': {path: digest for path, digest in plan['pins'].items() if any(Path(path).is_relative_to(root) for root in roots)},
                'realPersistedGraphs': True, 'completeSemanticEquivalence': True}
    if 'pendingComparabilityAuthority' in plan:
        from assemble_native_pressure_producers import (verify_measurement_bundle,
            measurement_fixture_bindings, pressure_arms)
        ref = plan['pendingComparabilityAuthority']
        packet = verify_measurement_bundle(pinned_authority_metadata(plan, ref),
                                          plan['arms']['A']['revision'], plan['arms']['B']['revision'])
        require(pressure_arms(packet) == plan['arms'] and
                all(plan['pins'].get(p) == h for p, h in packet['pins'].items()),
                'pending actual source runtime fixture and39 response closure')
        inventory = measurement_fixture_bindings(packet)
        require(inventory['fixtureFiles'] == expected['fixtureFiles'], 'complete pending graph inventory')
        for arm_id, arm in packet['arms'].items():
            require(len(arm['cases']) == len(plan['cases']) == 39, 'complete pending39 query inventory')
            for source, case in zip(arm['cases'], plan['cases']):
                require(source['id'] == case['id'] and source['request'] == case['request'] and
                        source['targetGraphIds'] == case['targetGraphIds'] and
                        typed(source['oracle']) == typed(case['oracleByArm'][arm_id]),
                        'pending measurement retains independent complete response authority')
        expected = inventory
        require(matches[0]['upstream'].get(ref['path']) == ref['sha256'], 'pending producer upstream')
    if 'correctedProducerAuthority' in plan:
        require(plan['engine'] == 'native' and 'producerAuthority' not in plan,
                'corrected comparison is an explicit native proof mode')
        from assemble_native_pressure_producers import verify_bundle, corrected_fixture_bindings, pressure_arms
        ref = plan['correctedProducerAuthority']
        packet = pinned_authority_metadata(plan, ref)
        packet = verify_bundle(packet, plan['arms']['A']['revision'], plan['arms']['B']['revision'])
        require(pressure_arms(packet) == plan['arms'] and
                all(plan['pins'].get(p) == h for p, h in packet['pins'].items()),
                'corrected actual source runtime fixture and proof closure')
        corrected = corrected_fixture_bindings(packet)
        require(corrected['fixtureFiles'] == expected['fixtureFiles'], 'complete corrected graph inventory')
        for arm_id, arm in packet['arms'].items():
            require(len(arm['cases']) == len(plan['cases']), 'complete corrected query inventory')
            for source, case in zip(arm['cases'], plan['cases']):
                require(source['id'] == case['id'] and source['request'] == case['request'] and
                        source['targetGraphIds'] == case['targetGraphIds'] and
                        typed(source['oracle']) == typed(case['oracleByArm'][arm_id]),
                        'corrected oracles retain actual complete response authority')
        expected = corrected
        require(matches[0]['upstream'].get(ref['path']) == ref['sha256'], 'corrected producer upstream')
    if 'jvmProducerAuthority' in plan or 'pendingJvmComparabilityAuthority' in plan:
        require(plan['engine'] == 'jvm' and 'correctedProducerAuthority' not in plan and
                'producerAuthority' not in plan, 'explicit separate JVM fixture proof mode')
        from prepare_jvm_pressure_plan import verify_bundle, fixture_bindings, pressure_arms
        ref = plan['pendingJvmComparabilityAuthority' if 'pendingJvmComparabilityAuthority' in plan else 'jvmProducerAuthority']
        if jvm_packet is None:
            packet = pinned_authority_metadata(plan, ref)
            jvm_packet = verify_bundle(packet, plan['arms']['A']['revision'], plan['arms']['B']['revision'])
        require(pressure_arms(jvm_packet) == plan['arms'] and
                all(plan['pins'].get(p) == h for p, h in jvm_packet['pins'].items()),
                'complete actual JVM producer closure')
        corrected = fixture_bindings(jvm_packet)
        require(corrected['fixtureFiles'] == expected['fixtureFiles'], 'complete JVM corrected graph inventory')
        for arm_id, arm in jvm_packet['arms'].items():
            require(len(arm['cases']) == len(plan['cases']), 'complete JVM query inventory')
            for source, case in zip(arm['cases'], plan['cases']):
                require(source['id'] == case['id'] and source['request'] == case['request'] and
                        source['targetGraphIds'] == case['targetGraphIds'] and
                        typed(source['oracle']) == typed(case['oracleByArm'][arm_id]),
                        'JVM complete independent oracle authority')
        expected = corrected
        require(matches[0]['upstream'].get(ref['path']) == ref['sha256'], 'JVM producer upstream')
    require(typed(matches[0].get('bindings')) == typed(expected), 'complete actual fixture equivalence claims')
    require(matches[0].get('upstream') and all(plan['pins'].get(path) == digest for path, digest in matches[0]['upstream'].items()), 'full fixture proof producer pins')


def _expected_graph_inputs(plan):
    # Path/is_relative_to are lexical: retain '..', include the path itself,
    # and preserve original pin strings even when Path normalizes '.' or '//'.
    expected = {Path(graph['path']): set()
                for arm in plan['arms'].values() for graph in arm['graphs']}
    for pin in plan['pins']:
        path = Path(pin)
        for ancestor in (path, *path.parents):
            if ancestor in expected:
                expected[ancestor].add(pin)
    return expected


def verify_inputs(plan):
    hashes = {}
    for path, expected in plan['pins'].items():
        require(not Path(path).is_symlink(), 'input symlink')
        hashes[path] = sha(path)
        require(hashes[path] == expected, 'input changed: ' + path)
    for proof in plan['proofs']:
        value = read(proof['path'])
        require(value['status'] == proof['status'], 'proof status')
        require(typed(value.get('upstream')) == typed(proof['upstream']), 'proof upstream audit links')
        for key, expected in proof.get('bindings', {}).items():
            require(typed(value.get(key)) == typed(expected), 'proof binding: ' + key)
    graph_inputs = _expected_graph_inputs(plan)
    for arm in plan['arms'].values():
        for directory in arm['runtimeRoots']:
            root = Path(directory)
            require(root.is_dir() and not root.is_symlink(), 'runtime root symlink/missing')
            found = set()
            for file in root.rglob('*'):
                require(not file.is_symlink(), 'runtime symlink')
                if file.is_file():
                    found.add(str(file))
            require(found == {p for p in arm['runtimeFiles'] if Path(p).is_relative_to(root)}, 'closed runtime inventory')
        for graph in arm['graphs']:
            root = Path(graph['path'])
            require(root.is_dir() and not root.is_symlink(), 'graph root')
            files = set()
            for path in root.rglob('*'):
                require(not path.is_symlink(), 'graph symlink')
                if path.is_file():
                    files.add(str(path))
            require(files == graph_inputs[root], 'closed graph inventory')
    return {'status': 'PASS', 'pins': hashes}


def _execution_closure(plan):
    """Filesystem invariants of the two reviewed corrected-producer audit chains.

    Called before AND after the full semantic replay, then around every reused
    operation. Paths come from pinned original manifests, never a saved cache.
    Unknown/legacy proof modes do not use this optimization.
    """
    import audit_native_pressure_artifacts as artifacts
    key = ('pendingJvmComparabilityAuthority' if 'pendingJvmComparabilityAuthority' in plan else
           'jvmProducerAuthority' if 'jvmProducerAuthority' in plan else
           'pendingComparabilityAuthority' if 'pendingComparabilityAuthority' in plan else 'correctedProducerAuthority')
    packet = pinned_authority_metadata(plan, plan[key])
    roots, sources, configurations = set(), {}, {}

    def metadata(path):
        path = str(Path(path))
        require(path in plan['pins'], 'reuse metadata outside complete pins: ' + path)
        return pinned_authority_metadata(plan, {'path': path, 'sha256': plan['pins'][path]})

    def add(path):
        path = Path(path)
        require(path.is_absolute() and str(path.resolve()) == str(path), 'reuse root must be canonical')
        roots.add(path)

    for arm in packet['arms'].values():
        artifact = metadata(arm['artifactAudit']['path'])
        source = metadata(artifact['sourceManifest']['path'])
        checkout = Path(source['root'])
        producer = metadata(artifact['producerPacket']['path'])
        producer_root = Path(artifact['producerPacket']['path']).parent
        if str(checkout) not in sources:
            sources[str(checkout)] = artifacts.source_inventory(checkout, arm['revision'])
            configurations[str(checkout)] = artifacts.configs(checkout, producer['buildEnvironment'])
        require(sources[str(checkout)] == source['files'], 'reuse tracked source inventory')
        require(configurations[str(checkout)] == producer['configurationBefore'] == producer['configurationAfter'],
                'reuse original build configuration')
        # The artifact auditor requires exactly one matching source-built writer;
        # ignored build output additions are invisible to Git status/ls-files.
        add(checkout/'frontend/jvm/webgraph/build/libs')
        add(producer_root/'runtime'); add(producer_root/'graphs')
        for phase in producer['phaseReceipts']:
            metadata(phase); add(Path(phase).parent)
        query = metadata(arm['queryAudit']['path'])
        query_path = Path(query['plan']['path'])
        query_plan = metadata(query_path); add(query_path.parent)
        if key in ('jvmProducerAuthority','pendingJvmComparabilityAuthority'):
            raw = Path(query_plan['rawDerivationRoot'])
            raw_plan=metadata(raw/'plan.json'); add(raw)
            if key=='pendingJvmComparabilityAuthority':
                add(raw_plan['rawExportRoot'])
                add(producer_root/'core-string-exports')

    for comparison in ({} if key in ('pendingComparabilityAuthority','pendingJvmComparabilityAuthority') else packet['correctedComparisons']).values():
        core_root = Path(comparison['audit']['path']).parent
        core_plan = metadata(core_root/'plan.json'); add(core_root)
        require(set(core_plan['upstream']) == {'CStrings', 'BStrings', 'marker', 'formatterTests', 'formatterSource'},
                'known complete core upstream closure required for reuse')
        for ref in core_plan['upstream'].values():
            metadata(ref['path']); add(Path(ref['path']).parent)
        tests = metadata(core_plan['upstream']['formatterTests']['path'])
        tests_root = Path(core_plan['upstream']['formatterTests']['path']).parent
        # Production test classpaths may contain external directories or absent
        # roots. Pins alone cannot detect an added class or an appeared root.
        # A flat parent arm ran no production formatter tests and has none (Attempt290).
        for module in ('sootup', 'webgraph') if tests['productionFormatterTestsApplicable'] else ():
            classpath = metadata(tests_root/f'results/classpath-{module}-before.json')
            for item in classpath['classpath']:
                require(item['kind'] in ('directory', 'file', 'absent'), 'known formatter classpath kind')
                add(item['path'])

    trees = {}
    # Skip nested scans, but preserve every directory (including empty ones),
    # entry type and unpinned byte. Pinned bytes are checked by verify_inputs.
    for root in sorted(roots):
        if any(root != other and root.is_relative_to(other) for other in roots):
            continue
        entries = {}
        def visit(path):
            require(not path.is_symlink(), 'reuse closure symlink: ' + str(path))
            if not path.exists():
                entries[str(path)] = 'absent'
                return
            mode = path.stat().st_mode
            if stat.S_ISDIR(mode):
                entries[str(path)] = 'directory'
                for child in sorted(path.iterdir()): visit(child)
            else:
                require(stat.S_ISREG(mode), 'reuse closure special file: ' + str(path))
                entries[str(path)] = ('file', None if str(path) in plan['pins'] else sha(path))
        visit(root)
        trees[str(root)] = entries
    return {'sources': sources, 'configurations': configurations, 'trees': trees}


class _VerifiedPressureExecution:
    """Private, one-execute lifetime; not a serialized authority or CLI option."""
    def __init__(self):
        raise TypeError('use the full-audit factory')

    def __reduce_ex__(self, protocol):
        raise TypeError('execution authority cannot be serialized')

    @classmethod
    def open(cls, plan_path):
        value = object.__new__(cls)
        path = Path(plan_path).absolute()
        require(not path.is_symlink() and path == path.resolve(), 'canonical regular execution plan path')
        value._path = str(path)
        value._sha = sha(value._path)
        candidate = read(value._path)
        value._reuse = any(k in candidate for k in
                           ('jvmProducerAuthority', 'correctedProducerAuthority', 'pendingComparabilityAuthority','pendingJvmComparabilityAuthority'))
        value._valid = False
        # Capture before replay as well: a mutation during the full audit must
        # not become the new trusted inventory merely by being captured later.
        verify_inputs(candidate)
        before = _execution_closure(candidate) if value._reuse else None
        value._plan = validate_plan(candidate)
        value._canonical = canonical(value._plan)
        verify_inputs(value._plan)
        value._closure = _execution_closure(value._plan) if value._reuse else None
        require(before == value._closure and sha(value._path) == value._sha and
                canonical(read(value._path)) == value._canonical, 'inputs changed during complete authority replay')
        value._valid = True
        return value

    @property
    def plan(self):
        # The orchestrator may inspect cells; it never gets a mutable reference
        # to the object whose authority was established by the full replay.
        return parse(self._canonical)

    def _check_unchanged(self):
        try:
            return self._verify_unchanged()
        except BaseException:
            self._valid = False
            raise

    def _verify_unchanged(self):
        require(self._valid, 'execution authority invalidated')
        require(not Path(self._path).is_symlink() and str(Path(self._path).resolve()) == self._path,
                'execution plan path changed')
        require(sha(self._path) == self._sha and canonical(read(self._path)) == self._canonical and
                canonical(self._plan) == self._canonical, 'execution plan changed')
        identities = verify_inputs(self._plan)
        if self._reuse:
            require(_execution_closure(self._plan) == self._closure, 'execution upstream closure changed')
        return identities

    def _call(self, operation, output, cell_id=None):
        combined = operation == 'run-and-audit'
        started = time.monotonic()
        def progress(phase):
            if combined:
                print(f'pressure cell {cell_id}: {phase} elapsed={time.monotonic() - started:.3f}s', flush=True)
        try:
            progress('verifying execution boundary')
            merge_before = combined and self._reuse and self._plan.get('engine') == 'native'
            if not merge_before:
                self._check_unchanged()
            target = Path(output).resolve()
            if self._reuse:
                require(not any(target.is_relative_to(Path(root)) or Path(root).is_relative_to(target)
                                for root in self._closure['trees']), 'pressure output overlaps frozen evidence')
            if combined:
                # One owned operation retains the inner before-launch and
                # after-cleanup identities, then independently audits raw data.
                # No successful combined result is returned until the terminal
                # closure check passes; persisted failure evidence remains.
                progress('running cell including launch and cleanup verification')
                consumed = False
                def before_inputs():
                    nonlocal consumed
                    require(not consumed, 'combined before-launch boundary already consumed')
                    identities = self._check_unchanged()
                    consumed = True
                    return identities
                result = (_run_cell(self._path, cell_id, output, self._plan,
                                    _before_inputs=before_inputs) if merge_before else
                          _run_cell(self._path, cell_id, output, self._plan) if self._reuse else
                          run_cell(self._path, cell_id, output))
                require(not merge_before or consumed, 'combined before-launch boundary not consumed')
                require(result['status'] == 'PASS', 'cell execution/audit failed')
                progress('auditing completed raw evidence')
                audited = (_audit(self._path, output, self._plan) if self._reuse
                           else audit(self._path, output))
                require(audited['status'] == 'PASS', 'cell execution/audit failed')
            elif self._reuse:
                result = (_run_cell(self._path, cell_id, output, self._plan) if operation == 'run'
                          else _audit(self._path, output, self._plan))
            else:
                # Legacy modes retain their original complete public audit path.
                result = (run_cell(self._path, cell_id, output) if operation == 'run'
                          else audit(self._path, output))
            progress('verifying terminal execution boundary')
            self._check_unchanged()
            require(result['status'] == 'PASS', 'cell execution/audit failed')
            progress('run and independent audit complete')
            return (result, audited) if combined else result
        except BaseException:
            self._valid = False
            raise

    def _complete_jvm_core(self, artifacts_root):
        """Join core once using this invocation's independently audited pending packet."""
        try:
            self._check_unchanged()
            require(not getattr(self, '_jvm_core_consumed', False), 'JVM core join already consumed')
            self._jvm_core_consumed = True
            require(self._plan.get('engine') == 'jvm' and self._plan.get('comparabilityPending') is True and
                    'pendingJvmComparabilityAuthority' in self._plan, 'verified pending JVM authority required')
            pending = pinned_authority_metadata(self._plan, self._plan['pendingJvmComparabilityAuthority'])
            root = Path(artifacts_root).resolve()
            require(all(Path(arm['artifactAudit']['path']).parent.parent == root
                        for arm in pending['arms'].values()), 'exact original JVM artifact root')
            import jvm_pressure_join
            corrected = jvm_pressure_join._complete_verified(pending)
            self._check_unchanged()
            return corrected, pending
        except BaseException:
            self._valid = False
            raise

    def run_and_audit_cell(self, cell_id, output):
        return self._call('run-and-audit', output, cell_id)

    def run_cell(self, cell_id, output):
        return self._call('run', output, cell_id)

    def audit_cell(self, output):
        return self._call('audit', output)


def nearest_rank(samples, fraction):
    require(samples and 0 < fraction <= 1, 'percentile inputs')
    return sorted(samples)[math.ceil(len(samples) * fraction) - 1]


def summarize_interval(samples, start_ns, end_ns):
    require(start_ns <= end_ns, 'reversed stage')
    require(all(r['readStartNs'] <= r['readEndNs'] for r in samples), 'reversed sample read')
    inside = [r for r in samples if start_ns <= r['readStartNs'] and r['readEndNs'] <= end_ns]
    require(inside, 'no complete RSS read inside stage')
    return {'lowerBoundBytes': max(r['rssBytes'] for r in inside), 'eligible': len(inside),
            'excluded': len(samples) - len(inside), 'scope': 'whole-read sampled lower bound'}


class HTTPTransport:
    """A fresh connection per request, bounded whole-request deadline and body cap."""
    def __init__(self, port, limits):
        self.port, self.limits = port, limits
        self.lock = threading.Lock()
        self.connections = {}

    def close_all(self):
        with self.lock:
            connections = list(self.connections.items())
        for connection, owned_socket in connections:
            try:
                target = owned_socket[0] if owned_socket else connection.sock
                if target:
                    target.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()

    def fetch(self, case, path, record):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=self.limits['requestSeconds'])
        expired = threading.Event()
        owned_socket = []
        def abort():
            expired.set()
            try:
                target = owned_socket[0] if owned_socket else connection.sock
                if target:
                    target.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        watchdog = threading.Timer(self.limits['requestSeconds'], abort)
        watchdog.daemon = True
        size = 0
        with self.lock:
            self.connections[connection] = owned_socket
        watchdog.start()
        try:
            request = case['request']
            raw_request = canonical(request['body'])
            record.update(requestBodySha256=digest_bytes(raw_request), endpoint=request['endpoint'])
            connection.request('POST', request['endpoint'], raw_request,
                               {'Content-Type': 'application/json', 'Connection': 'close'})
            if connection.sock is not None:
                owned_socket.append(connection.sock)
            response = connection.getresponse()
            record.update(httpStatus=response.status, headers=response.getheaders())
            with path.open('xb') as body:
                while True:
                    try:
                        chunk = response.read1(min(65536, self.limits['bodyBytes'] - size + 1))
                    except BaseException as error:
                        partial = getattr(error, 'partial', b'')
                        if isinstance(partial, bytes):
                            body.write(partial)
                        raise
                    if not chunk:
                        break
                    body.write(chunk)
                    size += len(chunk)
                    require(size <= self.limits['bodyBytes'], 'response byte cap')
                require(response.length in (None, 0), 'truncated Content-Length')
            record['wireCompleteNs'] = time.perf_counter_ns()
            require(not expired.is_set(), 'whole request deadline')
            record['completeBody'] = True
            require(response.status == 200, 'HTTP ' + str(response.status))
        finally:
            watchdog.cancel()
            watchdog.join(timeout=5)
            connection.close()
            with self.lock:
                self.connections.pop(connection, None)
            record['deadlineExpired'] = expired.is_set()
            require(not watchdog.is_alive(), 'request watchdog remains alive')


def run_stage(tasks, transport, validator, clock, stop_event, out, *, deadline_seconds=900, resource=None, concurrency=4):
    """No futures coordinator: every worker validates and immediately takes its next task."""
    require(type(concurrency) is int and concurrency >= 1, 'stage concurrency')
    out = Path(out)
    out.mkdir(exist_ok=False)
    (out / 'bodies').mkdir()
    lock = threading.Lock()
    records = []
    next_index = 0
    start_ns = None
    abort_errors = []
    ready = threading.Barrier(concurrency + 1)
    report = {'status': 'RUNNING', 'expectedRequests': len(tasks), 'concurrency': concurrency}
    before = resource() if resource else None
    deadline = time.monotonic() + deadline_seconds

    def worker(worker_id):
        nonlocal next_index, start_ns
        with (out / f'worker-{worker_id}.jsonl').open('x') as journal:
            ready.wait(timeout=10)
            while True:
                with lock:
                    if stop_event.is_set() or next_index == len(tasks):
                        return
                    index = next_index
                    next_index += 1
                    task = tasks[index]
                    start = clock()
                    if start_ns is None:
                        start_ns = start
                    rec = {'sequence': index, 'caseId': task['id'], 'worker': worker_id,
                           'startNs': start, 'status': 'RUNNING', 'bodyFile': f'bodies/{index:06}.body'}
                body_path = out / rec['bodyFile']
                try:
                    transport.fetch(task, body_path, rec)
                    raw = body_path.read_bytes()
                    rec.update(bodyBytes=len(raw), bodySha256=digest_bytes(raw))
                    rec.update(validator(raw, task))
                    rec['status'] = 'PASS'
                except BaseException as error:
                    rec.update(status='FAIL', error=repr(error))
                    stop_event.set()
                finally:
                    rec['validationCompleteNs'] = clock()
                    rec['latencyNs'] = rec['validationCompleteNs'] - start
                    rec['bodyAvailable'] = body_path.exists()
                    if body_path.exists():
                        if 'bodyBytes' not in rec:
                            rec['bodyBytes'] = body_path.stat().st_size
                        if 'bodySha256' not in rec:
                            rec['bodySha256'] = sha(body_path)
                    with lock:
                        records.append(rec)
                    try:
                        journal.write(json.dumps(rec, allow_nan=False) + '\n')
                        journal.flush()
                    except BaseException as error:
                        stop_event.set()
                        with lock:
                            abort_errors.append('request journal: ' + repr(error))
                        return

    def guarded_worker(worker_id):
        try:
            worker(worker_id)
        except BaseException as exc:
            stop_event.set()
            ready.abort()
            with lock:
                abort_errors.append('worker: ' + repr(exc))

    workers = [threading.Thread(target=guarded_worker, args=(i,), name=f'pressure-{i}', daemon=True) for i in range(concurrency)]
    error = None
    try:
        for thread in workers:
            thread.start()
        ready.wait(timeout=10)
        while any(thread.is_alive() for thread in workers):
            if time.monotonic() > deadline:
                stop_event.set()
                transport.close_all()
                error = 'stage deadline exceeded'
                break
            for thread in workers:
                thread.join(timeout=.01)
    except BaseException as exc:
        error = repr(exc)
        stop_event.set()
        transport.close_all()
    finally:
        if error:
            ready.abort()
            transport.close_all()
        for thread in workers:
            thread.join(timeout=10)
        if any(thread.is_alive() for thread in workers):
            error = error or 'workers did not drain'
        end_ns = max((r['validationCompleteNs'] for r in records), default=clock())
        after = None
        try:
            after = resource() if resource else None
        except BaseException as exc:
            error = error or 'resource endpoint: ' + repr(exc)
        records.sort(key=lambda r: r['sequence'])
        report.update(startNs=start_ns, endNs=end_ns, wallNs=None if start_ns is None else end_ns-start_ns,
                      requests=records, issued=next_index, unissued=list(range(next_index, len(tasks))),
                      cpuStart=before, cpuEnd=after, journalErrors=abort_errors,
                      allWorkersStopped=not any(t.is_alive() for t in workers))
        if error:
            report['error'] = error
        report['status'] = 'PASS' if (not error and not abort_errors and len(records) == len(tasks) and
                                      all(r['status'] == 'PASS' for r in records)) else 'FAIL'
        report['caseStatistics'] = statistics(records) if report['status'] == 'PASS' else {}
        if report['status'] == 'PASS':
            report['observedMaxWorkerConcurrency'] = peak_concurrency(records)
            report['observedMaxWireConcurrency'] = peak_concurrency(records, 'wireCompleteNs')
        save(out / 'stage.json', report)
    return report


def peak_concurrency(records, end_field='validationCompleteNs'):
    events = [(r['startNs'], 1) for r in records] + [(r[end_field], -1) for r in records]
    active = peak = 0
    for _, delta in sorted(events, key=lambda event: (event[0], event[1])):
        active += delta
        require(active >= 0, 'request interval ordering')
        peak = max(peak, active)
    require(active == 0, 'undrained request intervals')
    return peak


def statistics(records):
    result = {}
    for record in records:
        result.setdefault(record['caseId'], []).append(record)
    return {name: {'n': len(rows), 'sampleIds': [r['sequence'] for r in rows],
                   'p50Ns': nearest_rank([r['latencyNs'] for r in rows], .5),
                   'p95Ns': nearest_rank([r['latencyNs'] for r in rows], .95)}
            for name, rows in result.items()}


def clean_env():
    return {**{k: v for k, v in os.environ.items() if k not in CLEARED and not k.startswith('DYLD_')},
            'LC_ALL': 'C', 'LANG': 'C'}


@contextlib.contextmanager
def deferred_signals():
    pending = []
    old = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        for sig in old:
            signal.signal(sig, lambda signum, frame: pending.append(signum))
        yield
    finally:
        for sig, handler in old.items():
            signal.signal(sig, handler)
        if pending:
            raise InterruptedError('signal during owned launch: ' + str(pending[0]))


def proc_sample(pid, expected_start=None):
    start = time.perf_counter_ns()
    if sys.platform == 'darwin':
        raw = subprocess.check_output(['ps', '-p', str(pid), '-o', 'lstart=', '-o', 'time=', '-o', 'rss='], text=True, timeout=5, env=clean_env()).strip()
        return parse_macos_sample(raw, pid, start, time.perf_counter_ns(), expected_start)
    raw = (Path('/proc') / str(pid) / 'stat').read_text()
    tail = raw[raw.rfind(')') + 2:].split()
    ticks = os.sysconf('SC_CLK_TCK')
    identity = int(tail[19])
    require(expected_start is None or identity == expected_start, 'server PID was reused')
    return {'readStartNs': start, 'readEndNs': time.perf_counter_ns(), 'pid': pid,
            'startTicks': identity, 'userTicks': int(tail[11]), 'systemTicks': int(tail[12]),
            'ticksPerSecond': ticks, 'cpuSeconds': (int(tail[11]) + int(tail[12])) / ticks,
            'rssBytes': int(tail[21]) * os.sysconf('SC_PAGE_SIZE'), 'raw': raw,
            'backend': 'linux-proc', 'pageSize': os.sysconf('SC_PAGE_SIZE')}


def parse_macos_sample(raw, pid, start, end, expected_start=None):
    fields = raw.split()
    require(len(fields) == 7, 'macOS ps identity/CPU/RSS columns')
    identity = ' '.join(fields[:5])
    datetime.datetime.strptime(identity, '%a %b %d %H:%M:%S %Y')
    require(expected_start is None or expected_start == identity, 'server PID was reused')
    value = fields[5]
    days = 0
    if '-' in value:
        day, value = value.split('-', 1)
        days = int(day)
    cpu = days * 86400 + sum(float(v) * 60 ** i for i, v in enumerate(reversed(value.split(':'))))
    return {'readStartNs': start, 'readEndNs': end, 'pid': pid, 'startTicks': identity,
            'cpuSeconds': cpu, 'ticksPerSecond': 100, 'rssBytes': int(fields[6]) * 1024,
            'raw': raw, 'backend': 'macOS-ps', 'cpuBoundaryUncertaintySeconds': .02}


class Monitor:
    def __init__(self, sample, interval, path):
        self.sample, self.interval, self.path = sample, interval, Path(path)
        self.stop = threading.Event()
        self.samples, self.errors = [], []
        self.thread = threading.Thread(target=self.loop, name='resource-monitor', daemon=True)

    def loop(self):
        try:
            with self.path.open('x') as stream:
                while not self.stop.is_set():
                    row = self.sample()
                    self.samples.append(row)
                    stream.write(json.dumps(row) + '\n')
                    stream.flush()
                    self.stop.wait(self.interval)
        except BaseException as error:
            self.errors.append(repr(error))

    def finish(self):
        self.stop.set()
        self.thread.join(timeout=10)
        require(not self.thread.is_alive() and not self.errors, 'resource monitor: ' + repr(self.errors))


def group_members(group):
    require(type(group) is int and group > 1 and group != os.getpgrp(), 'unsafe group')
    raw = subprocess.check_output(['ps', '-eo', 'pid=,pgid=,stat='], text=True, timeout=5, env=clean_env())
    return [{'pid': int(p[0]), 'state': p[2]} for line in raw.splitlines()
            if len(p := line.split()) == 3 and int(p[1]) == group]


def stop_owned(proc, pid, expected_start):
    proof = {'group': None if proc is None else proc.pid, 'signals': [], 'errors': [], 'after': None}
    if proc is None:
        proof['after'] = []
        return proof
    group = proc.pid
    require(group > 1 and group != os.getpgrp(), 'unsafe owned process group')
    try:
        if proc.poll() is None:
            if pid is not None:
                require(os.getpgid(pid) == group, 'foreign server PID')
                proc_sample(pid, expected_start)
                os.kill(pid, signal.SIGTERM)
                proof['signals'].append({'target': 'server', 'signal': 'SIGTERM'})
            else:
                os.killpg(group, signal.SIGTERM)
                proof['signals'].append({'target': 'group', 'signal': 'SIGTERM'})
            proc.wait(timeout=30)
        else:
            proof['errors'].append('wrapper exited before requested shutdown')
    except BaseException as error:
        proof['errors'].append(repr(error))
    finally:
        try:
            for sig in (signal.SIGTERM, signal.SIGKILL):
                if not group_members(group):
                    break
                os.killpg(group, sig)
                proof['signals'].append({'target': 'group', 'signal': sig.name})
                if sig == signal.SIGKILL:
                    proof['errors'].append('cleanup required SIGKILL')
                deadline = time.monotonic() + 5
                while group_members(group) and time.monotonic() < deadline:
                    time.sleep(.05)
            proof['after'] = group_members(group)
            if proof['after']:
                proof['errors'].append('owned group remains')
        except BaseException as error:
            proof['errors'].append('group verification: ' + repr(error))
            try:
                os.killpg(group, signal.SIGKILL)
                proof['signals'].append({'target': 'group', 'signal': 'SIGKILL-emergency'})
            except ProcessLookupError:
                pass
        finally:
            try:
                proof['exit'] = proc.wait(timeout=5)
                if proof['exit'] not in (0, 143, -15):
                    proof['errors'].append('unexpected wrapper exit: ' + str(proof['exit']))
            except BaseException as error:
                proof['errors'].append('reap: ' + repr(error))
    return proof


def lifecycle_time(path):
    text = Path(path).read_text()
    if re.search(r'^[ \t]*[0-9]+[ \t]+maximum resident set size', text, re.M):
        match = re.search(r'([0-9.]+) real\s+([0-9.]+) user\s+([0-9.]+) sys', text)
        rss = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', text, re.M)
        require(match is not None and rss is not None, 'complete BSD time-l resources')
        return {'peakRssBytes': int(rss.group(1)), 'userSeconds': float(match.group(2)),
                'systemSeconds': float(match.group(3)), 'realSeconds': float(match.group(1)),
                'scope': 'entire owned fresh-server lifecycle including readiness and shutdown', 'rawSha256': sha(path)}
    def field(label):
        match = re.search(r'^\s*' + re.escape(label) + r':\s*([0-9.]+)\s*$', text, re.M)
        require(match is not None, 'missing GNU time: ' + label)
        return float(match.group(1))
    elapsed = re.search(r'^\s*Elapsed \(wall clock\) time \(h:mm:ss or m:ss\):\s*([0-9:.]+)\s*$', text, re.M)
    require(elapsed is not None, 'complete GNU time elapsed wall')
    real = sum(float(part) * 60 ** i for i, part in enumerate(reversed(elapsed.group(1).split(':'))))
    return {'realSeconds': real, 'peakRssBytes': int(field('Maximum resident set size (kbytes)')) * 1024,
            'userSeconds': field('User time (seconds)'), 'systemSeconds': field('System time (seconds)'),
            'scope': 'entire owned fresh-server lifecycle including readiness and shutdown', 'rawSha256': sha(path)}


def validate_readiness_body(raw, arm, data):
    value = parse(raw)
    require(Path(value['data']).resolve() == data.resolve(), 'readiness data')
    graphs = value['graphs']
    require(type(value['count']) is int and value['count'] == len(graphs) == len(arm['graphs']), 'full readiness count')
    actual = dict(value)
    actual.pop('data')
    actual['graphs'] = [dict(g) for g in graphs]
    for graph in actual['graphs']:
        require(type(graph['loadedAt']) is str, 'loadedAt type')
        datetime.datetime.fromisoformat(graph.pop('loadedAt'))
    require(typed(actual) == typed(arm['readiness']['expected']), 'complete readiness oracle')


def validate_resource_sample(row):
    require(row['readStartNs'] <= row['readEndNs'], 'sample read direction')
    if row.get('backend') == 'macOS-ps':
        require(row == parse_macos_sample(row['raw'], row['pid'], row['readStartNs'], row['readEndNs'], row['startTicks']), 'raw macOS sample')
    else:
        require(row.get('backend') == 'linux-proc', 'unknown resource backend')
        fields = row['raw'][row['raw'].rfind(')') + 2:].split()
        require(int(row['raw'].split(' ', 1)[0]) == row['pid'] and int(fields[19]) == row['startTicks'], 'raw Linux PID')
        require(row['userTicks'] == int(fields[11]) and row['systemTicks'] == int(fields[12]), 'raw Linux CPU ticks')
        require(row['cpuSeconds'] == (row['userTicks'] + row['systemTicks']) / row['ticksPerSecond'], 'raw Linux CPU seconds')
        require(row['rssBytes'] == int(fields[21]) * row['pageSize'], 'raw Linux RSS pages')


def readiness(port, arm, data, seconds, proc, evidence=None):
    deadline = time.monotonic() + seconds
    expired = threading.Event()
    active = []
    owned_socket = []
    def abort():
        expired.set()
        target = owned_socket[0] if owned_socket else (active[0].sock if active else None)
        try:
            if target is not None:
                target.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        if active:
            active[0].close()
    watchdog = threading.Timer(seconds, abort)
    watchdog.daemon = True
    watchdog.start()
    attempt = 0
    try:
        while time.monotonic() < deadline and not expired.is_set():
            require(proc.poll() is None, 'server exited before readiness')
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=min(2, max(.001, deadline-time.monotonic())))
            active[:] = [connection]
            owned_socket.clear()
            attempt += 1
            chunks = []
            count = 0
            error = None
            status = None
            try:
                connection.request('GET', '/api/graphs')
                if connection.sock is not None:
                    owned_socket.append(connection.sock)
                response = connection.getresponse()
                status = response.status
                while True:
                    try:
                        chunk = response.read1(min(65536, 4 * 1024 ** 2 - count + 1))
                    except BaseException as failure:
                        partial = getattr(failure, 'partial', b'')
                        if isinstance(partial, bytes):
                            chunks.append(partial)
                        raise
                    if not chunk:
                        break
                    chunks.append(chunk)
                    count += len(chunk)
                    require(count <= 4 * 1024 ** 2, 'readiness body cap')
                    if expired.is_set() or time.monotonic() >= deadline:
                        raise TimeoutError('readiness absolute deadline')
                raw = b''.join(chunks)
                require(response.length in (None, 0), 'incomplete readiness body')
                if expired.is_set() or time.monotonic() >= deadline:
                    raise TimeoutError('readiness absolute deadline')
                require(status == 200, 'readiness HTTP status ' + str(status))
                validate_readiness_body(raw, arm, data)
                if expired.is_set() or time.monotonic() >= deadline:
                    raise TimeoutError('readiness validation exceeded absolute deadline')
                return raw
            except (OSError, http.client.HTTPException) as failure:
                error = repr(failure)
                if expired.is_set() or time.monotonic() >= deadline:
                    raise TimeoutError('readiness absolute deadline') from failure
                time.sleep(min(.025, max(0, deadline-time.monotonic())))
            except BaseException as failure:
                error = repr(failure)
                raise
            finally:
                connection.close()
                active.clear()
                owned_socket.clear()
                if evidence is not None:
                    body = Path(evidence) / f'readiness-attempt-{attempt:04}.body'
                    body.write_bytes(b''.join(chunks))
                    save(body.with_suffix('.json'), {'attempt': attempt, 'bodySha256': sha(body),
                         'bodyBytes': body.stat().st_size, 'httpStatus': status, 'error': error, 'deadlineExpired': expired.is_set()})
        raise TimeoutError('readiness absolute deadline')
    finally:
        watchdog.cancel()
        watchdog.join(timeout=5)
        require(not watchdog.is_alive(), 'readiness watchdog remains alive')


def rss_boundary_samples(stages):
    return [stage[key] for stage in stages.values() for key in ('cpuStart', 'cpuEnd') if key in stage]


def resource_summary(stage, samples, lifecycle, rss_samples=None):
    begin, end = stage['cpuStart'], stage['cpuEnd']
    require(begin['readEndNs'] <= stage['startNs'] <= stage['endNs'] <= end['readStartNs'], 'CPU enclosing interval')
    identity_fields = ['pid', 'startTicks', 'backend', 'ticksPerSecond', 'pageSize', 'cpuBoundaryUncertaintySeconds']
    require(all(begin.get(key) == end.get(key) for key in identity_fields), 'CPU identity')
    require(all(all(row.get(key) == begin.get(key) for key in identity_fields) for row in samples), 'sample process/units identity')
    delta = end['cpuSeconds'] - begin['cpuSeconds']
    require(delta >= 0, 'negative process CPU')
    # Four rounded components (user/system at two endpoints), conservative two ticks.
    uncertainty = begin.get('cpuBoundaryUncertaintySeconds', 2 / begin['ticksPerSecond'])
    inner = [r for r in samples if stage['startNs'] <= r['readStartNs'] and r['readEndNs'] <= stage['endNs']]
    require(inner, 'no inner CPU read for lower bound')
    inner.sort(key=lambda r: r['readStartNs'])
    cpu_lower = max(0, inner[-1]['cpuSeconds'] - inner[0]['cpuSeconds'] - uncertainty)
    rss = summarize_interval(samples, stage['startNs'], stage['endNs'])
    peak = lifecycle['peakRssBytes']
    if begin.get('backend') == 'linux-proc':
        # /proc RSS uses asynchronous counters; a sampled value can exceed
        # wait4/getrusage's separately maintained high-water mark. Keep both.
        # https://www.kernel.org/doc/html/v6.8/filesystems/proc.html
        require(all(type(begin[k]) is int and begin[k] > 0 for k in ('pid', 'ticksPerSecond', 'pageSize')) and
                type(begin['startTicks']) is int and begin['startTicks'] >= 0, 'valid Linux process units')
        extra = [] if rss_samples is None else rss_samples
        for row in extra:
            validate_resource_sample(row)
            require(math.isfinite(row['cpuSeconds']) and row['cpuSeconds'] >= 0 and
                    row['userTicks'] >= 0 and row['systemTicks'] >= 0, 'valid RSS boundary CPU')
        observed = [*samples, *extra]
        require(all(all(row.get(key) == begin.get(key) for key in identity_fields) for row in observed),
                'RSS sample process/units identity')
        require(type(peak) is int and peak > 0 and
                all(type(row['rssBytes']) is int and row['rssBytes'] >= 0 and
                    row['readStartNs'] <= row['readEndNs'] for row in observed), 'valid Linux RSS observations')
        sampled_peak = max(row['rssBytes'] for row in observed)
        require(sampled_peak > 0, 'positive Linux sampled RSS peak')
        rss.update(measurementSemantics='linux-rss-observed-envelope-v1',
                   lifecyclePeakBytes=peak, lifecycleSampledPeakBytes=sampled_peak,
                   sampledMinusLifecycleBytes=sampled_peak - peak,
                   scope='stage whole-read sampled RSS to lifecycle observed peak envelope; '
                         'asynchronous kernel counters, not mathematical physical-memory bounds')
        peak = max(peak, sampled_peak)
    else:
        require(peak >= rss['lowerBoundBytes'], 'lifecycle RSS below sampled RSS')
    return {'cpuSeconds': delta, 'cpuQuantizationSeconds': uncertainty,
            'cpuLowerBoundSeconds': cpu_lower, 'cpuUpperBoundSeconds': delta + uncertainty,
            'cpuStartLeadNs': stage['startNs'] - begin['readStartNs'],
            'cpuEndLagNs': end['readEndNs'] - stage['endNs'],
            'cpuScope': 'enclosing server CPU interval, client validation included in wall boundary',
            'rss': {**rss, 'upperBoundBytes': peak}}


def evidence_eligible(result, plan):
    require(result['coverage'] == plan['coverage'], 'result coverage drift')
    require(result['engine'] == plan['engine'] and result['operation'] == 'query', 'result operation/engine')
    require(result['otherOperationsEligible'] is False, 'query cannot authorize other operations')
    return ('pendingComparabilityAuthority' not in plan and 'pendingJvmComparabilityAuthority' not in plan and
            result['status'] == 'PASS' and not result['errors'] and
            set(result['stages']) == {'oracle', 'warmup', 'pressure', 'isolated'} and
            all(stage['status'] == 'PASS' and stage.get('resources', {}).get('status') != 'UNAVAILABLE'
                for stage in result['stages'].values()) and not plan['coverage']['unavailableFamilies'])


PAGE_CACHE_EVICTION = 'posix_fadvise(POSIX_FADV_DONTNEED) on every file of every graph after sync(), before each cell starts'
PAGE_CACHE_WARMING = ("every arm's graph files advised out of the page cache after sync(), then every file of the cell's own graphs "
                      "read in full, before each cell starts: the cache holds exactly the graphs the cell loads")
READ_CHUNK = 8 << 20


def graph_files(graphs):
    """Every regular file of every graph directory that exists, in path order; symlinks are skipped.

    An entry whose path is itself a regular file (a source JAR, a retained output) is yielded as is,
    so the same walk serves the construction cells, whose inputs are files and whose earlier
    outputs are directories.
    """
    for graph in graphs:
        root = Path(graph['path'])
        if root.is_file() and not root.is_symlink():
            yield root
            continue
        if not root.is_dir():
            continue
        for path in sorted(root.rglob('*')):
            if path.is_file() and not path.is_symlink():
                yield path


def evict_graph_pages(graphs):
    """Drop every file of `graphs` from the page cache.

    A loading cell's wall is the time to read its 64 graphs; what the kernel still holds of them from
    the checkpoint restore or from the previous cell decides it more than the engine does. Run
    38630296899 measured the same arm at 7.4 and 12.9 s (CPU 8.8 and 10.9 s) and failed one pair of
    the loading gate on it. Dirty pages are flushed first, then every graph file is advised out;
    the kernel honours the advice for clean pages. Returns what was evicted for the cell's record.
    A graph directory that does not exist contributes nothing.
    """
    os.sync()
    files = 0
    total = 0
    for path in graph_files(graphs):
        fd = os.open(path, os.O_RDONLY)
        try:
            os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
        finally:
            os.close(fd)
        files += 1
        total += path.stat().st_size
    return {'method': PAGE_CACHE_EVICTION, 'files': files, 'bytes': total}


def warm_graph_pages(all_graphs, graphs):
    """Leave the page cache holding exactly the cell's graphs: every arm's are evicted, the cell's read in full.

    Cold is not a usable state either: run 38647742862 evicted before every cell and each read 3.2 GB
    from the disk (6.3M file system inputs in `time -v`), so the walls were the runner's disk
    throughput, 14.2–18.2 s at a CPU of 11.7–12.1 s, and three pairs failed the 5% wall rule where
    the CPU agreed within 0.3 s. Before the digest cache (Attempt293), the input verification read
    every graph file in full before each launch and left it warm; this makes that state explicit
    and independent of the previous cell: every arm's graphs are advised out, then the cell's own
    graphs are read through, so the engine's loading work is what the wall measures, equally for
    every arm. The bytes counted are the bytes read, so the record proves the read completed.
    """
    evicted = evict_graph_pages(all_graphs)
    files = 0
    total = 0
    for path in graph_files(graphs):
        with path.open('rb') as handle:
            while True:
                chunk = handle.read(READ_CHUNK)
                if not chunk:
                    break
                total += len(chunk)
        files += 1
    return {'method': PAGE_CACHE_WARMING, 'evicted': evicted, 'files': files, 'bytes': total}


def graph_arguments(graphs):
    # Readiness validates resolved paths; pass those same paths to the server.
    return [value for graph in graphs
            for value in ('--graph', graph['id'] + ':' + str(Path(graph['path']).resolve()))]


def run_cell(plan_path, cell_id, output):
    return _run_cell(plan_path, cell_id, output, validate_plan(read(plan_path)))


def _run_cell(plan_path, cell_id, output, plan, *, _before_inputs=None):
    require(sys.platform in ('linux', 'darwin'), 'unsupported actual CPU/RSS resource backend')
    loading = 'loadingMeasurement' in plan
    if loading:
        import native_loading
        native_loading.validate_declaration(plan)
        require(sys.platform == 'linux', 'Native64 loading requires original Linux VmHWM evidence')
    cells = [c for c in plan['cells'] if c['id'] == cell_id]
    require(len(cells) == 1, 'unknown cell')
    cell = cells[0]
    arm = plan['arms'][cell['arm']]
    out = Path(output).resolve()
    # Only the private combined Native operation supplies this callback. Its
    # full input+closure check is also the before-launch byte verification.
    # Complete it before creating output, so a failed boundary leaves frozen
    # evidence untouched. There is no cached identity or public bypass.
    before_identities = _before_inputs() if _before_inputs is not None else None
    out.mkdir(parents=True, exist_ok=False)
    save(out / 'plan.json', plan)
    record = {'schema': RESULT_SCHEMA, 'status': 'RUNNING', 'planSha256': sha(plan_path),
              'cell': cell, 'engine': plan['engine'], 'operation': 'query', 'arm': arm,
              'coverage': plan['coverage'], 'stages': {}, 'errors': [], 'acceptanceEligible': False,
              'hostObservationScope': 'before/after loadavg only; no quiet-host, disk-cold or saturation claim'}
    started = time.perf_counter_ns()
    proc = pid = pid_start = monitor = transport = None
    previous = {}
    def interrupted(signum, frame):
        raise InterruptedError('signal ' + str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            previous[sig] = signal.signal(sig, interrupted)
        save(out / 'identities-before.json',
             before_identities if _before_inputs is not None else verify_inputs(plan))
        validator = compile_response_validator(plan['cases'], cell['arm'], pins=plan['pins'])
        save(out / 'host-before.json', {'loadavg': os.getloadavg(), 'cpuCount': os.cpu_count(), 'platform': sys.platform})
        data = out / 'data'
        args = [*arm['serverArgv'], '--data', str(data), '--port', str(cell['port']), '--load-mode', 'MAPPED',
                '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', str(int(plan['limits']['requestSeconds'] * 1000)), '--metrics']
        args.extend(graph_arguments(arm['graphs']))
        command = ['/usr/bin/time', '-l' if sys.platform == 'darwin' else '-v', '-o', str(out / 'time-v.log'), sys.executable,
                   str(Path(__file__).resolve()), '_exec-server', '--pid-file', str(out / 'server.pid'), '--', *args]
        save(out / 'command.json', command)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', cell['port']))
        launch_env = clean_env()
        # Every cell loads its graphs from a page cache that holds exactly them, not what the restore or
        # the previous cell left there (the JVM loading cells do the same).
        record['pageCache'] = warm_graph_pages([g for a in plan['arms'].values() for g in a['graphs']], arm['graphs'])
        with deferred_signals():
            with (out / 'stdout.log').open('x') as stdout, (out / 'stderr.log').open('x') as stderr:
                if loading:
                    record['loading'] = {'schema': 'graphite.native64-loading.cell.v1', 'graphCount': 64,
                                         'queriesIssuedDuringLoading': 0, 'pageCache': record['pageCache']}
                    record['loading']['startNs'] = time.perf_counter_ns()
                proc = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True, env=launch_env)
            save(out / 'launch-owner.json', {'runnerPid': os.getpid(), 'group': proc.pid})
        deadline = time.monotonic() + 10
        while not (out / 'server.pid').exists():
            require(proc.poll() is None and time.monotonic() < deadline, 'server PID publication')
            time.sleep(.01)
        pid = int((out / 'server.pid').read_text())
        require(os.getpgid(pid) == proc.pid, 'server outside owned group')
        initial_sample = proc_sample(pid)
        pid_start = initial_sample['startTicks']
        if loading: record['loading']['initialSample'] = initial_sample
        monitor = Monitor(lambda: proc_sample(pid, pid_start), plan['limits']['rssIntervalSeconds'], out / 'resources.jsonl')
        monitor.thread.start()
        raw = readiness(cell['port'], arm, data, plan['limits']['readinessSeconds'], proc, out)
        if loading:
            record['loading']['readyNs'] = time.perf_counter_ns()
            record['loading']['cpuAfterReady'] = proc_sample(pid, pid_start)
            record['loading']['readyWatermark'] = native_loading.ready_watermark(pid, pid_start)
        (out / 'readiness.body').write_bytes(raw)
        record['readyNs'] = time.perf_counter_ns()
        transport = HTTPTransport(cell['port'], plan['limits'])
        for stage, count in [('oracle', 1), ('warmup', 2), ('pressure', 20)]:
            tasks = [case for _ in range(count) for case in plan['cases']]
            report = run_stage(tasks, transport, validator, time.perf_counter_ns, threading.Event(), out / stage,
                               deadline_seconds=plan['limits']['stageSeconds'], resource=lambda: proc_sample(pid, pid_start))
            record['stages'][stage] = report
            require(report['status'] == 'PASS', 'stage failed: ' + stage)
        # The isolated tail stage follows the pressure stage it is derived from, on one worker.
        report = run_stage(isolated_tasks(plan, record['stages']['pressure']), transport, validator, time.perf_counter_ns,
                           threading.Event(), out / 'isolated', deadline_seconds=plan['limits']['stageSeconds'],
                           resource=lambda: proc_sample(pid, pid_start), concurrency=plan['schedule']['isolatedConcurrency'])
        record['stages']['isolated'] = report
        require(report['status'] == 'PASS', 'stage failed: isolated')
        require(proc.poll() is None, 'server exited during workload')
        record['status'] = 'PASS'
    except BaseException as error:
        record['errors'].append(repr(error))
        record['status'] = 'FAIL'
    finally:
        for sig in previous:
            signal.signal(sig, signal.SIG_IGN)
        try:
            if transport:
                transport.close_all()
            if monitor:
                try:
                    monitor.finish()
                except BaseException as error:
                    record['errors'].append('monitor: ' + repr(error))
        finally:
            try:
                cleanup = stop_owned(proc, pid, pid_start)
                record['cleanup'] = cleanup
                save(out / 'cleanup.json', cleanup)
                if cleanup['errors'] or cleanup['after']:
                    record['errors'].append('owned cleanup failed')
                record['lifecycle'] = lifecycle_time(out / 'time-v.log')
                if loading and 'readyWatermark' in record.get('loading', {}):
                    observed = record['loading']
                    observed['resources'] = native_loading.bounds(observed['startNs'], observed['readyNs'],
                        observed['cpuAfterReady'], monitor.samples, observed['readyWatermark'])
                for stage in record['stages'].values():
                    if stage['status'] == 'PASS':
                        # Oracle/warmups remain visible but are not repeated-request acceptance.
                        try:
                            stage['resources'] = resource_summary(stage, monitor.samples, record['lifecycle'],
                                                                   rss_boundary_samples(record['stages']))
                        except ValueError as error:
                            if stage is record['stages'].get('pressure'):
                                raise
                            stage['resources'] = {'status': 'UNAVAILABLE', 'reason': str(error)}
            except BaseException as error:
                record['errors'].append('cleanup/resources: ' + repr(error))
            finally:
                try:
                    save(out / 'identities-after.json', verify_inputs(plan))
                except BaseException as error:
                    record['errors'].append('final identities: ' + repr(error))
                try:
                    save(out / 'host-after.json', {'loadavg': os.getloadavg(), 'cpuCount': os.cpu_count(), 'platform': sys.platform})
                except BaseException as error:
                    record['errors'].append('host receipt: ' + repr(error))
                record['cellWallNsIncludingFinalVerification'] = time.perf_counter_ns() - started
                if record['errors']:
                    record['status'] = 'FAIL'
                record['otherOperationsEligible'] = False
                record['acceptanceEligible'] = evidence_eligible(record, plan)
                save(out / 'result.json', record)
                for sig, handler in previous.items():
                    signal.signal(sig, handler)
    return record


def audit(plan_path, output):
    """Recompute completed raw request/body/resource evidence; no process launches."""
    return _audit(plan_path, output, validate_plan(read(plan_path)))


def _audit(plan_path, output, plan):
    root = Path(output)
    result = read(root / 'result.json')
    require(read(root / 'plan.json') == plan, 'archived plan')
    require(result['schema'] == RESULT_SCHEMA and result['planSha256'] == sha(plan_path), 'result/plan binding')
    require(result['status'] == 'PASS' and not result['errors'], 'not a completed successful cell')
    require(result['acceptanceEligible'] == evidence_eligible(result, plan), 'eligibility must match actual complete evidence')
    cell = result['cell']
    require(cell in plan['cells'] and result['arm'] == plan['arms'][cell['arm']], 'cell/arm identity')
    require(read(root / 'identities-before.json') == read(root / 'identities-after.json') ==
            {'status': 'PASS', 'pins': plan['pins']}, 'full identity receipts')
    validate_readiness_body((root / 'readiness.body').read_bytes(), result['arm'], (root / 'data').resolve())
    command = read(root / 'command.json')
    marker = command.index('--')
    expected = [*result['arm']['serverArgv'], '--data', str((root / 'data').resolve()), '--port', str(cell['port']),
                '--load-mode', 'MAPPED', '--max-concurrent-cypher', '4', '--cypher-max-timeout-ms', str(int(plan['limits']['requestSeconds'] * 1000)), '--metrics']
    expected.extend(graph_arguments(result['arm']['graphs']))
    require(command[marker+1:] == expected and command[0] == '/usr/bin/time' and command[1] in ('-v', '-l'), 'actual launch command')
    cleanup = read(root / 'cleanup.json')
    require(cleanup == result['cleanup'] and cleanup['after'] == [] and not cleanup['errors'] and cleanup.get('exit') in (0, 143, -15), 'cleanup proof')
    require(all('KILL' not in s['signal'] for s in cleanup['signals']), 'forced kill')
    owner = read(root / 'launch-owner.json')
    require(owner['group'] == cleanup['group'] and owner['group'] != owner['runnerPid'], 'owned group binding')
    samples = [parse(line) for line in (root / 'resources.jsonl').read_bytes().splitlines()]
    server_pid = int((root / 'server.pid').read_text())
    require(server_pid > 1, 'server PID proof')
    for sample in samples:
        validate_resource_sample(sample)
        require(sample['pid'] == server_pid, 'resource sample belongs to another process')
    lifecycle = lifecycle_time(root / 'time-v.log')
    require(lifecycle == result['lifecycle'], 'lifecycle raw proof')
    total = 0
    validator = compile_response_validator(plan['cases'], cell['arm'], pins=plan['pins'])
    stages = [(name, [case for _ in range(repeats) for case in plan['cases']], 4)
              for name, repeats in [('oracle', 1), ('warmup', 2), ('pressure', 20)]]
    stages.append(('isolated', isolated_tasks(plan, result['stages']['pressure']), plan['schedule']['isolatedConcurrency']))
    require(set(result['stages']) == {name for name, _, _ in stages}, 'exact stage inventory')
    for name, tasks, concurrency in stages:
        stage = result['stages'][name]
        require(stage['concurrency'] == concurrency, 'stage concurrency')
        require(stage['cpuStart']['pid'] == stage['cpuEnd']['pid'] == server_pid, 'stage CPU PID')
        validate_resource_sample(stage['cpuStart'])
        validate_resource_sample(stage['cpuEnd'])
        stored = read(root / name / 'stage.json')
        require({k: v for k, v in stage.items() if k != 'resources'} == stored, 'stage raw receipt')
        records = sorted([parse(line) for worker in range(concurrency)
                          for line in (root / name / f'worker-{worker}.jsonl').read_bytes().splitlines()], key=lambda r: r['sequence'])
        require(records == stage['requests'], 'all worker journals')
        require(stage['status'] == 'PASS' and len(records) == len(tasks) and not stage['unissued'] and
                stage['allWorkersStopped'] and not stage['journalErrors'], 'complete stage')
        require({p.name for p in (root / name / 'bodies').iterdir()} == {f'{i:06}.body' for i in range(len(tasks))}, 'body inventory')
        for i, (rec, case) in enumerate(zip(records, tasks)):
            require(type(rec['worker']) is int and 0 <= rec['worker'] < concurrency, 'worker identity')
            require(rec['sequence'] == i and rec['caseId'] == case['id'] and rec['status'] == 'PASS', 'schedule/result')
            require(rec['bodyFile'] == f'bodies/{i:06}.body' and rec['endpoint'] == case['request']['endpoint'] and
                    rec['requestBodySha256'] == digest_bytes(canonical(case['request']['body'])), 'request provenance')
            require(rec['httpStatus'] == 200 and rec['completeBody'] and not rec['deadlineExpired'], 'complete HTTP success')
            require(rec['startNs'] <= rec['wireCompleteNs'] <= rec['validationCompleteNs'] and
                    rec['latencyNs'] == rec['validationCompleteNs'] - rec['startNs'], 'validated boundary')
            raw = (root / name / rec['bodyFile']).read_bytes()
            require(len(raw) == rec['bodyBytes'] and digest_bytes(raw) == rec['bodySha256'], 'complete raw body')
            validation = validator(raw, case)
            require(all(rec[k] == v for k, v in validation.items()), 'canonical receipt')
        require(statistics(records) == stage['caseStatistics'], 'per-case raw quantiles')
        require(stage['observedMaxWorkerConcurrency'] == peak_concurrency(records) <= concurrency and
                stage['observedMaxWireConcurrency'] == peak_concurrency(records, 'wireCompleteNs') <= concurrency, 'observed concurrency')
        if records:
            require(stage['startNs'] == min(r['startNs'] for r in records) and
                    stage['endNs'] == max(r['validationCompleteNs'] for r in records), 'stage extrema')
        else:
            require(stage['startNs'] is None and stage['wallNs'] is None, 'empty stage extrema')
        for worker in range(concurrency):
            owned = [r for r in records if r['worker'] == worker]
            require(all(a['validationCompleteNs'] <= b['startNs'] for a, b in zip(owned, owned[1:])), 'worker sequential validation')
        try:
            resources = resource_summary(stage, samples, lifecycle, rss_boundary_samples(result['stages']))
        except ValueError as error:
            require(name != 'pressure', 'pressure resources unavailable')
            resources = {'status': 'UNAVAILABLE', 'reason': str(error)}
        require(resources == stage['resources'], 'raw resource parity')
        events = [(r['startNs'], 1) for r in records] + [(r['validationCompleteNs'], -1) for r in records]
        active = 0
        for _, delta in sorted(events, key=lambda event: (event[0], event[1])):
            active += delta
            require(0 <= active <= concurrency, 'actual request concurrency')
        require(active == 0, 'undrained request interval')
        total += len(records)
    loading_audit = {}
    if 'loadingMeasurement' in plan:
        from native_loading import audit as audit_loading
        loading_audit['loading'] = audit_loading(plan, result, root, samples, lifecycle, server_pid)
    return {'schema': 'graphite.multigraph-pressure.audit.v1', 'status': 'PASS', 'cell': cell['id'], **loading_audit,
            'completeBodies': total, 'resultSha256': sha(root / 'result.json'), 'planSha256': sha(plan_path),
            'coverage': plan['coverage'], 'queryEvidenceComplete': True,
            'acceptanceEligible': result['acceptanceEligible'], 'otherOperationsEligible': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('validate-plan', 'run', 'audit'):
        command = sub.add_parser(name)
        command.add_argument('--plan', required=True)
        if name in ('run', 'audit'):
            command.add_argument('--output', required=True)
        if name == 'run':
            command.add_argument('--cell', required=True)
    internal = sub.add_parser('_exec-server')
    internal.add_argument('--pid-file', required=True)
    internal.add_argument('argv', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if args.command == '_exec-server':
        command = args.argv[1:] if args.argv[:1] == ['--'] else args.argv
        require(command and Path(command[0]).is_absolute(), 'exec argv')
        with Path(args.pid_file).open('x') as stream:
            stream.write(str(os.getpid()))
        os.execve(command[0], command, clean_env())
    elif args.command == 'validate-plan':
        plan = validate_plan(read(args.plan))
        print(json.dumps({'status': 'VALID_PLAN_NOT_EXECUTED', 'cases': len(plan['cases']), 'coverage': plan['coverage']}))
    elif args.command == 'run':
        result = run_cell(args.plan, args.cell, args.output)
        print(json.dumps({'status': result['status'], 'cell': args.cell, 'output': args.output}))
        return 0 if result['status'] == 'PASS' else 1
    else:
        value = audit(args.plan, args.output)
        save(Path(args.output) / 'audit.json', value)
        print(json.dumps(value))
    return 0


if __name__ == '__main__':
    sys.exit(main())
