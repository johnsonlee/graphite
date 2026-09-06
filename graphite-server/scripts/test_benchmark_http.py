#!/usr/bin/env python3
"""Control-flow tests only: fake transport, no graph queries or performance results."""
import contextlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

import benchmark_http as benchmark


class FailureEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.output = self.root / 'output'
        ids = [f'graph-{i:02d}' for i in range(64)]
        for graph_id in ids:
            (self.root / graph_id).mkdir()
        (self.root / 'graphs.tsv').write_text('transport control fixture only\n')
        (self.root / 'fixture-provenance.tsv').write_text(
            'graphId\tnodeCount\tcallSiteCount\n' + ''.join(f'{g}\t0\t0\n' for g in ids))
        self.cases = [{'name': f'query-{i}', 'path': '/api/cypher', 'phase': 'query'} for i in range(42)]
        manifest = self.root / 'manifest.json'
        manifest.write_text(json.dumps({'cases': self.cases, 'graphCount': 64,
            'requiredGraphIds': ids, 'fixtureManifestSha256': benchmark.digest(self.root / 'graphs.tsv'),
            'fixtureProvenanceSha256': benchmark.digest(self.root / 'fixture-provenance.tsv')}))
        artifact = self.root / 'artifact'
        artifact.write_bytes(b'not a runtime')
        self.catalog = {'status': 200, 'body': '', 'json': {'count': 64,
            'graphs': [{'id': g, 'path': str(self.root / g), 'nodes': 0, 'callSites': 0} for g in ids]}}
        self.args = ['benchmark_http.py', '--baseline-url', 'http://unused',
            '--baseline-revision', benchmark.MAIN, '--baseline-artifact', str(artifact),
            '--baseline-pid', '1', '--fixture-root', str(self.root),
            '--baseline-fixture-root', str(self.root), '--manifest', str(manifest),
            '--output', str(self.output), '--concurrency', '1']

    def invoke(self, fail_at, failure):
        count = 0
        def fetch(url, case, unused):
            nonlocal count
            if case['path'] == '/api/graphs':
                return self.catalog
            count += 1
            if count == fail_at:
                if isinstance(failure, Exception):
                    raise failure
                return failure
            return {'status': 200, 'body': '{"rows":[]}', 'json': {'rows': []}}
        with mock.patch.object(sys, 'argv', self.args), \
                mock.patch.object(benchmark, 'fetch', side_effect=fetch), \
                mock.patch.object(benchmark, 'process_sample', return_value='live control process'), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises((RuntimeError, OSError)):
                benchmark.main()
        self.assertFalse((self.output / 'samples.jsonl').exists())
        self.assertFalse((self.output / 'results.json').exists())
        return json.loads((self.output / 'correctness-failure.json').read_text())

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        receipt = self.output / 'samples.jsonl'
        receipt.write_bytes(b'original evidence\n')
        with mock.patch.object(sys, 'argv', self.args), mock.patch.object(benchmark, 'fetch') as fetch:
            with self.assertRaises(FileExistsError):
                benchmark.main()
            fetch.assert_not_called()
        self.assertEqual(receipt.read_bytes(), b'original evidence\n')

    def test_initial_http_failure_keeps_prior_and_failed_responses(self):
        response = {'status': 504, 'body': '{"error":"timeout"}'}
        failure = self.invoke(2, response)
        self.assertEqual(failure['phase'], 'initial')
        observations = json.loads((self.output / 'initial-replay.partial.json').read_text())
        self.assertEqual(len(observations), 2)
        self.assertEqual(observations[0]['baseline']['response']['status'], 200)
        self.assertEqual(observations[1]['baseline']['response'], response)

    def test_initial_transport_failure_is_preserved(self):
        failure = self.invoke(1, OSError('connection lost'))
        self.assertIn('connection lost', failure['record']['baseline']['transportError'])

    def test_warmup_http_failure_is_preserved(self):
        response = {'status': 400, 'body': '{"error":"index closed"}'}
        failure = self.invoke(44, response)
        self.assertEqual(failure['phase'], 'warmup')
        self.assertEqual(failure['completedBefore'], 1)
        self.assertEqual(failure['response'], response)
        self.assertEqual(json.loads((self.output / 'warmup-progress.json').read_text())['completed'], 1)
        self.assertEqual(len(json.loads((self.output / 'initial-replay.json').read_text())), 42)

    def test_warmup_transport_failure_is_preserved(self):
        failure = self.invoke(43, OSError('warmup disconnected'))
        self.assertEqual(failure['phase'], 'warmup')
        self.assertIn('warmup disconnected', failure['transportError'])

    def test_warmup_body_mismatch_is_preserved(self):
        response = {'status': 200, 'body': '{"rows":[1]}', 'json': {'rows': [1]}}
        failure = self.invoke(43, response)
        self.assertEqual(failure['response'], response)
        self.assertEqual(failure['completedBefore'], 0)


if __name__ == '__main__':
    unittest.main()
