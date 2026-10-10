"""Transport correctness only: tiny persisted bytes, never graphs or performance."""
import argparse
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import native_pressure_checkpoint as checkpoint


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.workspace = Path(self.tmp.name).resolve()/'workspace'; self.workspace.mkdir()
        for name in ('base', 'candidate'):
            root = self.workspace/name; root.mkdir()
            self.write(root/'tracked', 'original source')
            self.write(root/'.gitignore', 'build/\n')
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
            subprocess.run(['git', '-C', str(root), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture'], check=True,
                           env={**os.environ, 'GIT_AUTHOR_DATE': '2020-01-01T00:00:00Z', 'GIT_COMMITTER_DATE': '2020-01-01T00:00:00Z'})
        self.revision = subprocess.check_output(['git', '-C', str(self.workspace/'base'), 'rev-parse', 'HEAD'], text=True).strip()
        self.assertEqual(self.revision, subprocess.check_output(['git', '-C', str(self.workspace/'candidate'), 'rev-parse', 'HEAD'], text=True).strip())
        p = patch.object(checkpoint, 'ACCEPTED', self.revision); p.start(); self.addCleanup(p.stop)
        self.root = self.workspace/'native-pressure-artifacts'
        self.binary = self.root/'B/runtime/graphite'; self.write(self.binary, 'tiny binary'); self.binary.chmod(0o755)
        self.writer = self.root/'B/runtime/writer.jar'; self.write(self.writer, 'tiny writer bytes')
        self.original = self.workspace/'candidate/build/libs/writer-jmh.jar'; self.write(self.original, 'tiny writer bytes')
        self.classes = self.workspace/'candidate/build/classes'; self.write(self.classes/'A.class', 'class bytes')
        self.jar = self.root/'B/gradle-home/caches/selected.jar'; self.write(self.jar, 'selected dependency')
        self.write(self.root/'B/gradle-home/caches/unrelated.secret', 'must not be transported')
        self.empty = self.root/'B/gradle-home/init.d'; self.empty.mkdir()
        self.absent = self.root/'B/gradle-home/init.gradle'
        cp = {'schema': 'graphite.formatter-test-classpath.v1', 'classpath': [
            {'kind': 'directory', 'path': str(self.classes), 'files': {'A.class': checkpoint.sha(self.classes/'A.class')}},
            {'kind': 'file', 'path': str(self.jar), 'sha256': checkpoint.sha(self.jar)},
            {'kind': 'absent', 'path': str(self.workspace/'candidate/build/absent-resources')}]}
        self.json(self.root/'B/formatter-tests/results/classpath-webgraph-before.json', cp)
        self.json(self.root/'B/packet.json', {'configurationBefore': {str(self.absent): None, str(self.empty): 'EMPTY_DIRECTORY'},
            'originalArtifacts': {str(self.original): checkpoint.sha(self.original)},
            'pins': {str(self.binary): checkpoint.sha(self.binary)}})
        self.write(self.root/'B/graphs/graph.nodes', 'tiny saved graph')
        self.json(self.workspace/'benchmark-results/rust-provenance/provenance.json', {'files': {}})
        self.write(self.workspace/'shared-fixture64/graphs/graphs.tsv', 'tiny source corpus')
        self.directory = self.workspace/'pressure-checkpoints/producer-producer'

    def write(self, p, value):
        p.parent.mkdir(parents=True, exist_ok=True); p.write_text(value)

    def json(self, p, value):
        self.write(p, json.dumps(value))

    def args(self, stage='producer', arm='producer', directory=None, upstream=()):
        return argparse.Namespace(stage=stage, arm=arm, directory=str(directory or self.directory),
            workspace=str(self.workspace), head_sha=self.revision, base_sha=getattr(self, 'base_revision', self.revision),
            run_id='1234', run_attempt='2', upstream=[str(p) for p in upstream])

    def seal(self):
        return checkpoint.seal(self.args())

    def remove_payload(self, manifest):
        for name in manifest['payload']:
            Path(name).unlink()
        for name in sorted(manifest['directories'], key=len, reverse=True):
            p = Path(name)
            if p.exists() and not list(p.iterdir()):
                p.rmdir()

    def mutate_manifest(self, update):
        p = self.directory/'manifest.json'; value = checkpoint.read(p); update(value); checkpoint.save(p, value)

    def test_actual_seal_restore_closure_modes_empty_dirs_without_unrelated_cache(self):
        manifest = self.seal()
        self.assertFalse(manifest['proofAuthority']); self.assertFalse(manifest['performanceAcceptance'])
        self.assertIn(str(self.original), manifest['payload']); self.assertIn(str(self.jar), manifest['payload'])
        self.assertNotIn(str(self.root/'B/gradle-home/caches/unrelated.secret'), manifest['files'])
        self.assertIn(str(self.classes), manifest['closedDirectories']); self.assertIn(str(self.empty), manifest['directories'])
        before = {name: Path(name).read_bytes() for name in manifest['payload']}
        self.remove_payload(manifest)
        checkpoint.restore(self.args())
        self.assertEqual(before, {name: Path(name).read_bytes() for name in manifest['payload']})
        self.assertEqual(0o755, self.binary.stat().st_mode & 0o777)
        self.assertEqual([], list(self.empty.iterdir())); self.assertFalse(self.absent.exists())

    def test_missing_original_artifact_fails_seal(self):
        self.original.unlink()
        with self.assertRaisesRegex(ValueError, 'missing artifact'): self.seal()

    def test_missing_classpath_member_fails_seal(self):
        (self.classes/'A.class').unlink()
        with self.assertRaisesRegex(ValueError, 'classpath member'): self.seal()

    def test_extra_classpath_member_rejected(self):
        self.write(self.classes/'Extra.class', 'foreign')
        with self.assertRaisesRegex(ValueError, 'classpath member'): self.seal()

    def test_absent_configuration_appearing_rejected_before_restore(self):
        self.seal(); self.write(self.absent, 'foreign configuration')
        with self.assertRaisesRegex(ValueError, 'absent path appeared'): checkpoint.restore(self.args())

    def test_absent_classpath_appearing_rejected(self):
        self.seal(); (self.workspace/'candidate/build/absent-resources').mkdir()
        with self.assertRaisesRegex(ValueError, 'absent path appeared'): checkpoint.restore(self.args())

    def test_python_path_and_hash_cannot_change(self):
        manifest = self.seal()
        self.mutate_manifest(lambda v: v.update(python='/different/python'))
        with self.assertRaisesRegex(ValueError, 'Python interpreter path'): checkpoint.restore(self.args())
        checkpoint.save(self.directory/'manifest.json', manifest)
        self.mutate_manifest(lambda v: v['files'][v['python']].update(sha256='0'*64))
        with self.assertRaisesRegex(ValueError, 'preexisting dependency'): checkpoint.restore(self.args())

    def test_existing_binary_lost_execute_bit_rejected(self):
        self.seal(); self.binary.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'different file'): checkpoint.restore(self.args())

    def test_source_revision_and_source_bytes_remain_required(self):
        self.seal(); self.write(self.workspace/'candidate/tracked', 'modified')
        with self.assertRaisesRegex(ValueError, 'exact clean checkout'): checkpoint.restore(self.args())

    def test_workspace_relocation_and_wrong_run_identity_rejected(self):
        self.seal(); args = self.args(); args.workspace = str(self.workspace.parent/'relocated')
        with self.assertRaisesRegex(ValueError, 'checkpoint identity'): checkpoint.restore(args)
        args = self.args(); args.run_attempt = '3'
        with self.assertRaisesRegex(ValueError, 'checkpoint identity'): checkpoint.restore(args)

    def test_runner_image_change_rejected(self):
        with patch.dict(os.environ, {'ImageVersion': 'first'}): self.seal()
        with patch.dict(os.environ, {'ImageVersion': 'second'}):
            with self.assertRaisesRegex(ValueError, 'runner image changed'): checkpoint.restore(self.args())

    def test_tar_traversal_and_links_rejected_even_with_resealed_digest(self):
        manifest = self.seal()
        for kind in ('traversal', 'symlink'):
            archive = self.directory/'payload.tar.gz'
            with tarfile.open(archive, 'w:gz') as tar:
                member = tarfile.TarInfo('../../escape' if kind == 'traversal' else 'files/00000000')
                if kind == 'symlink': member.type = tarfile.SYMTYPE; member.linkname = '/etc/passwd'
                tar.addfile(member, io.BytesIO(b''))
            self.mutate_manifest(lambda v: v.update(archiveSha256=checkpoint.sha(archive)))
            with self.assertRaisesRegex(ValueError, 'unsafe or incomplete archive'): checkpoint.restore(self.args())
        self.assertFalse((self.workspace.parent/'escape').exists())

    def test_external_payload_destination_rejected(self):
        self.seal(); outside = self.workspace.parent/'outside'
        def change(v):
            old = v['payload'][0]; v['payload'][0] = str(outside); v['files'][str(outside)] = v['files'].pop(old)
        self.mutate_manifest(change)
        with self.assertRaisesRegex(ValueError, 'outside restore roots'): checkpoint.restore(self.args())
        self.assertFalse(outside.exists())

    def test_archive_truncation_and_digest_mutation_rejected(self):
        self.seal(); archive = self.directory/'payload.tar.gz'; archive.write_bytes(archive.read_bytes()[:10])
        with self.assertRaisesRegex(ValueError, 'archive digest'): checkpoint.restore(self.args())

    def test_fresh_output_required(self):
        self.seal()
        with self.assertRaisesRegex(ValueError, 'fresh checkpoint'): self.seal()

    def test_core_delta_keeps_upstream_closed_graphs_but_allows_later_stage(self):
        producer = self.seal(); upstream = self.directory/'manifest.json'
        self.json(self.root/'B/core-equivalence/plan.json', {'pins': {str(self.binary): checkpoint.sha(self.binary)}})
        self.write(self.root/'B/core-equivalence/graphs/fixture-0/record.json', '{}')
        args = self.args('core', 'B', self.workspace/'pressure-checkpoints/core-B', [upstream])
        manifest = checkpoint.seal(args)
        self.assertNotIn(str(self.binary), manifest['payload'])
        self.remove_payload(manifest)
        # Producer manifest may be downloaded under a different transport name.
        downloaded = self.workspace/'pressure-checkpoints/native-checkpoint-producer/manifest.json'
        downloaded.parent.mkdir(); shutil.copyfile(upstream, downloaded)
        args.upstream = [str(downloaded)]; checkpoint.restore(args)
        checkpoint.check_files(producer)
        self.write(self.root/'B/graphs/extra', 'not allowed')
        with self.assertRaisesRegex(ValueError, 'closed directory'): checkpoint.restore(args)

    def test_jvm_requires_producer_and_all_actual_core_not_subset(self):
        self.seal(); producer = self.directory/'manifest.json'
        self.json(self.root/'B/core-equivalence/plan.json', {'pins': {str(self.binary): checkpoint.sha(self.binary)}})
        core_dir = self.workspace/'pressure-checkpoints/core-B'
        checkpoint.seal(self.args('core', 'B', core_dir, [producer]))
        for name in ('jvm-raw-derivation', 'jvm-query-correctness'):
            self.json(self.root/'C'/name/'plan.json', {'pins': {str(self.binary): checkpoint.sha(self.binary)}})
        args = self.args('jvm', 'C', self.workspace/'pressure-checkpoints/jvm-C', [producer])
        with self.assertRaisesRegex(ValueError, 'exact upstream'): checkpoint.seal(args)
        args.upstream.append(str(core_dir/'manifest.json'))
        manifest = checkpoint.seal(args)
        self.assertEqual([('core', 'B'), ('producer', 'producer')], [(v['stage'], v['arm']) for v in manifest['upstream']])

    def test_relative_legacy_binary_provenance_is_preserved(self):
        binary = self.workspace/'base/build/graphite'
        self.write(binary, 'original snapshot binary'); binary.chmod(0o755)
        provenance = self.workspace/'benchmark-results/rust-provenance/provenance.json'
        self.json(provenance, {'files': {'base/build/graphite': checkpoint.sha(binary)}})
        manifest = self.seal()
        self.assertIn(str(binary), manifest['payload'])
        self.remove_payload(manifest); checkpoint.restore(self.args())
        self.assertEqual('original snapshot binary', binary.read_text())

    def test_named_artifact_audit_pin_outside_standard_trees_is_collected(self):
        referenced = self.root/'B/gradle-home/caches/audited.jar'; self.write(referenced, 'audited dependency')
        self.json(self.root/'B/artifact-audit.json', {'pins': {str(referenced): checkpoint.sha(referenced)}})
        self.assertIn(str(referenced), self.seal()['payload'])

    def test_low_disk_seal_fails_without_publishing_manifest(self):
        with patch.object(checkpoint.shutil, 'disk_usage', return_value=argparse.Namespace(free=0)):
            with self.assertRaisesRegex(ValueError, 'disk headroom'): self.seal()
        self.assertFalse((self.directory/'manifest.json').exists())

    def test_delta_inherits_exact_external_tool_pins_without_repacking_jdk(self):
        jdk = self.workspace/'jdk/17/x64'
        for name in ('bin/java', 'bin/javac', 'release', 'lib/modules'):
            self.write(jdk/name, name)
        cargo = self.workspace.parent/'toolchain/bin/cargo'; self.write(cargo, 'cargo bytes')
        rustc = self.workspace.parent/'toolchain/bin/rustc'; self.write(rustc, 'rustc bytes')
        self.json(self.root/'B/runtime-manifest.json', {'toolchainIdentity': {'java': str(jdk/'bin/java'), 'cargo': str(cargo), 'rustc': str(rustc)}})
        producer = self.seal(); upstream = self.directory/'manifest.json'
        self.json(self.root/'B/core-equivalence/plan.json', {'pins': {str(p): checkpoint.sha(p) for p in (cargo, rustc, jdk/'bin/javac', jdk/'lib/modules')}})
        core = self.workspace/'pressure-checkpoints/core-B'; args = self.args('core', 'B', core, [upstream])
        value = checkpoint.seal(args)
        self.assertIn(str(cargo), value['files']); self.assertEqual([], [p for p in value['payload'] if str(jdk) in p])
        self.remove_payload(value); checkpoint.restore(args)
        self.write(cargo, 'changed external compiler')
        with self.assertRaisesRegex(ValueError, 'restored file identity'): checkpoint.restore(args)

    def test_regular_hardlinks_roundtrip_as_independent_regular_payloads(self):
        first = self.root/'B/graphs/a'; self.write(first, 'shared inode')
        second = self.root/'B/graphs/a-/child'; second.parent.mkdir(); os.link(first, second)
        manifest = self.seal()
        with tarfile.open(self.directory/'payload.tar.gz', 'r:gz') as tar:
            self.assertTrue(all(member.isfile() and not member.islnk() for member in tar.getmembers()))
        self.remove_payload(manifest); checkpoint.restore(self.args())
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(b'shared inode', first.read_bytes())

    def test_provenance_cargo_config_is_hash_only_never_payload(self):
        config = self.workspace/'.cargo/config.toml'; self.write(config, 'sensitive setting')
        self.json(self.workspace/'benchmark-results/rust-provenance/provenance.json',
                  {'files': {'.cargo/config.toml': checkpoint.sha(config)}})
        manifest = self.seal()
        self.assertIn(str(config), manifest['verificationOnly']); self.assertNotIn(str(config), manifest['payload'])
        with tarfile.open(self.directory/'payload.tar.gz', 'r:gz') as tar:
            self.assertNotIn(b'sensitive setting', b''.join(tar.extractfile(m).read() for m in tar.getmembers()))
        config.unlink()
        with self.assertRaisesRegex(ValueError, 'preexisting dependency'): checkpoint.restore(self.args())

    def test_nonalias_three_checkouts_two_cores_and_jvm_requires_whole_upstream_set(self):
        subprocess.run(['git', 'clone', '-q', str(self.workspace/'base'), str(self.workspace/'accepted')], check=True)
        self.write(self.workspace/'base/tracked', 'distinct actual parent')
        subprocess.run(['git', '-C', str(self.workspace/'base'), 'add', 'tracked'], check=True)
        subprocess.run(['git', '-C', str(self.workspace/'base'), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                        'commit', '-qm', 'distinct parent'], check=True)
        self.base_revision = subprocess.check_output(['git', '-C', str(self.workspace/'base'), 'rev-parse', 'HEAD'], text=True).strip()
        producer = self.seal(); producer_ref = self.directory/'manifest.json'
        self.assertEqual({'base', 'candidate', 'accepted'}, set(producer['sources']))
        refs = [producer_ref]
        for arm in ('A', 'B'):
            self.json(self.root/arm/'core-equivalence/plan.json', {'pins': {str(self.binary): checkpoint.sha(self.binary)}})
            directory = self.workspace/('pressure-checkpoints/core-'+arm)
            args = self.args('core', arm, directory, [producer_ref]); manifest = checkpoint.seal(args)
            self.remove_payload(manifest); checkpoint.restore(args)
            renamed = directory/'downloaded-manifest.json'; shutil.copyfile(directory/'manifest.json', renamed); refs.append(renamed)
        for name in ('jvm-raw-derivation', 'jvm-query-correctness'):
            self.json(self.root/'A'/name/'plan.json', {'pins': {str(self.binary): checkpoint.sha(self.binary)}})
        args = self.args('jvm', 'A', self.workspace/'pressure-checkpoints/jvm-A', refs[:2])
        with self.assertRaisesRegex(ValueError, 'exact upstream stages'): checkpoint.seal(args)
        args.upstream = [str(p) for p in refs]; value = checkpoint.seal(args)
        self.assertEqual([('core', 'A'), ('core', 'B'), ('producer', 'producer')], [(r['stage'], r['arm']) for r in value['upstream']])
        self.remove_payload(value); checkpoint.restore(args)

    def test_full_jdk_includes_nonpinned_runtime_files_and_internal_links(self):
        jdk = self.workspace/'jdk/17/x64'
        for name in ('bin/java', 'bin/javac', 'release', 'lib/modules', 'lib/server/libjvm.so'):
            self.write(jdk/name, name)
        (jdk/'bin/java').chmod(0o755); (jdk/'legal').mkdir(); (jdk/'legal/module').symlink_to('../release')
        self.json(self.root/'B/runtime-manifest.json', {'toolchainIdentity': {'java': str(jdk/'bin/java'), 'cargo': str(self.binary), 'rustc': str(self.binary)}})
        manifest = self.seal()
        self.assertIn(str(jdk/'lib/server/libjvm.so'), manifest['payload'])
        self.assertEqual('../release', manifest['links'][str(jdk/'legal/module')])
        (jdk/'legal/module').unlink(); self.remove_payload(manifest)
        checkpoint.restore(self.args())
        self.assertEqual('lib/server/libjvm.so', (jdk/'lib/server/libjvm.so').read_text())
        self.assertTrue((jdk/'legal/module').is_symlink())


if __name__ == '__main__':
    unittest.main()
