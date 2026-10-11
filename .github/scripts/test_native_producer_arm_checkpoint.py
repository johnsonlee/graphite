"""Prospective single-arm transport correctness; no performance or workflow switch."""
import argparse
import contextlib
import io
import json
from pathlib import Path
import shutil
import sys
import subprocess
import unittest
from unittest.mock import patch
import zipfile

import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as fixtures


class ProducerArmTransportTests(unittest.TestCase):
    def setUp(self):
        self.f=fixtures.CheckpointTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.packet=self.f.root/'B/packet.json'
        value=checkpoint.read(self.packet);value.update(revision=self.f.revision,role='candidate')
        self.f.json(self.packet,value)
        self.args=self.f.args('producer-arm','B',self.f.workspace/'sealed/producer-arm-B')
        # A sibling exists but must not be selected or have any identity rewritten.
        self.foreign=self.f.root/'C/runtime/graphite';self.f.write(self.foreign,'untouched foreign writer')

    def cli(self,action):
        argv=['checkpoint',action]
        for name,value in vars(self.args).items():
            if name=='upstream':
                for item in value:argv.extend(['--upstream',item])
            else:argv.extend(['--'+name.replace('_','-'),str(value)])
        output=io.StringIO()
        with patch.object(sys,'argv',argv),contextlib.redirect_stdout(output):checkpoint.main()
        result=json.loads(output.getvalue())
        self.assertFalse(result['proofAuthority']);self.assertFalse(result['performanceAcceptance'])
        return result

    def test_cli_zip_roundtrip_preserves_original_arm_and_dependency_bytes(self):
        self.cli('seal');directory=Path(self.args.directory);manifest=checkpoint.read(directory/'manifest.json')
        self.assertEqual('producer-arm',manifest['identity']['stage'])
        self.assertEqual('B',manifest['identity']['arm']);self.assertEqual([],manifest['upstream'])
        self.assertNotIn(str(self.foreign),manifest['files'])
        self.assertIn(str(self.f.original),manifest['payload'])
        before={name:Path(name).read_bytes() for name in manifest['payload']}
        archive=directory.parent/'arm.zip'
        with zipfile.ZipFile(archive,'w') as target:
            for name in ('manifest.json','payload.tar.gz'):target.write(directory/name,name)
        self.f.remove_payload(manifest);shutil.rmtree(directory)
        with zipfile.ZipFile(archive) as source:source.extractall(directory)
        self.cli('restore')
        self.assertEqual(before,{name:Path(name).read_bytes() for name in manifest['payload']})
        self.assertEqual('untouched foreign writer',self.foreign.read_text())
        self.assertEqual(0o755,self.f.binary.stat().st_mode & 0o777)
        self.assertFalse(self.f.absent.exists())
        self.assertEqual(manifest,checkpoint.read(directory/'manifest.json'))

    def test_accepted_and_distinct_parent_arms_roundtrip_with_original_roles(self):
        for arm,role in (('C','accepted-baseline'),('A','parent')):
            with self.subTest(arm=arm):
                if arm=='A':
                    subprocess.run(['git','clone','-q',str(self.f.workspace/'candidate'),str(self.f.workspace/'accepted')],check=True)
                    self.f.write(self.f.workspace/'base/tracked','distinct parent source')
                    subprocess.run(['git','-C',str(self.f.workspace/'base'),'-c','user.name=Fixture',
                                    '-c','user.email=fixture@example.invalid','commit','-qam','parent'],check=True)
                    self.f.base_revision=subprocess.check_output(['git','-C',str(self.f.workspace/'base'),'rev-parse','HEAD'],text=True).strip()
                revision=self.f.revision if arm=='C' else self.f.base_revision
                root=self.f.root/arm;graph=root/'graphs/graph.nodes';self.f.write(graph,arm+' graph')
                self.f.json(root/'packet.json',{'revision':revision,'role':role,'pins':{str(graph):checkpoint.sha(graph)}})
                self.args=self.f.args('producer-arm',arm,self.f.workspace/('sealed/producer-arm-'+arm))
                self.cli('seal');manifest=checkpoint.read(Path(self.args.directory)/'manifest.json')
                self.assertNotIn(str(self.f.binary),manifest['files'])
                before={name:Path(name).read_bytes() for name in manifest['payload']}
                self.f.remove_payload(manifest);self.cli('restore')
                self.assertEqual(before,{name:Path(name).read_bytes() for name in manifest['payload']})
                packet=checkpoint.read(root/'packet.json')
                self.assertEqual((revision,role),(packet['revision'],packet['role']))

    def test_wrong_role_and_foreign_pinned_arm_cannot_be_sealed(self):
        value=checkpoint.read(self.packet);value['role']='accepted-baseline';self.f.json(self.packet,value)
        with self.assertRaisesRegex(ValueError,'exact revision and role'):checkpoint.seal(self.args)
        self.assertFalse(Path(self.args.directory).exists())
        value['role']='candidate';value['pins'][str(self.foreign)]=checkpoint.sha(self.foreign)
        self.f.json(self.packet,value)
        with self.assertRaisesRegex(ValueError,'another arm'):checkpoint.seal(self.args)
        self.assertFalse(Path(self.args.directory).exists())

    def test_forged_foreign_payload_rejected_before_restore_writes(self):
        manifest=checkpoint.seal(self.args);self.f.remove_payload(manifest)
        manifest['directories'].append(str(self.foreign.parent))
        manifest['directoryModes'][str(self.foreign.parent)]=0o755
        checkpoint.save(Path(self.args.directory)/'manifest.json',manifest)
        with self.assertRaisesRegex(ValueError,'another arm'):checkpoint.restore(self.args)
        self.assertFalse(self.f.binary.exists())
        self.assertEqual('untouched foreign writer',self.foreign.read_text())

    def test_wrong_run_and_source_or_payload_drift_are_rejected(self):
        manifest=checkpoint.seal(self.args)
        bad=argparse.Namespace(**vars(self.args));bad.run_id='9876'
        with self.assertRaisesRegex(ValueError,'identity'):checkpoint.restore(bad)
        self.f.write(self.f.binary,'same writer?')
        with self.assertRaisesRegex(ValueError,'different file'):checkpoint.restore(self.args)
        self.f.write(self.f.binary,'tiny binary');self.f.binary.chmod(0o755)
        self.f.write(self.f.workspace/'candidate/tracked','changed source')
        with self.assertRaisesRegex(ValueError,'clean checkout'):checkpoint.restore(self.args)
        self.assertEqual(manifest,checkpoint.read(Path(self.args.directory)/'manifest.json'))

    def test_aliased_parent_arm_and_complete_consumer_reject_arm_only_packet(self):
        bad=argparse.Namespace(**vars(self.args));bad.arm='A'
        with self.assertRaisesRegex(ValueError,'aliased arm'):checkpoint.seal(bad)
        manifest=checkpoint.seal(self.args)
        bad=argparse.Namespace(**vars(self.args));bad.stage='producer';bad.arm='producer'
        with self.assertRaisesRegex(ValueError,'identity'):checkpoint.restore(bad)
        self.assertFalse(manifest['performanceAcceptance'])


if __name__=='__main__':unittest.main()
