"""Actual tiny common/arm ZIP handoff into the unchanged complete producer consumer."""
import argparse
import contextlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch
import zipfile

import native_pressure_checkpoint as checkpoint
import test_native_pressure_checkpoint as fixtures


class ProducerMergeSmokeTests(unittest.TestCase):
    def setUp(self):
        self.f=fixtures.CheckpointTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.f.system_jdk_fixture('adoptium')
        self.root=self.f.workspace/'pressure-checkpoints'
        self.f.json(self.f.root/'source-inputs.json',{'jars':[],'fixture':'tiny exact corpus'})
        self.f.json(self.f.root/'portable-expectations.json',{'fixture':'tiny original oracle'})
        self.f.json(self.f.root/'producer-common.json',{'schema':'graphite.producer-common-inputs.v1','toolchainIdentity':self.f.system_tools})

    def cli(self,action,args):
        argv=['checkpoint',action]
        for name,value in vars(args).items():
            if name=='upstream':
                for item in value:argv.extend(['--upstream',item])
            else:argv.extend(['--'+name.replace('_','-'),str(value)])
        stream=io.StringIO()
        with patch.object(sys,'argv',argv),contextlib.redirect_stdout(stream):checkpoint.main()
        result=json.loads(stream.getvalue())
        self.assertFalse(result['proofAuthority']);self.assertFalse(result['performanceAcceptance'])
        return result

    def transfer(self,args):
        directory=Path(args.directory);manifest=checkpoint.read(directory/'manifest.json')
        archive=self.f.workspace.parent/(directory.name+'.zip')
        with zipfile.ZipFile(archive,'w') as target:
            for name in ('manifest.json','payload.tar.gz'):target.write(directory/name,name)
        shutil.rmtree(directory)
        with zipfile.ZipFile(archive) as source:source.extractall(directory)
        self.assertEqual(manifest,checkpoint.read(directory/'manifest.json'))
        return manifest

    def prepare(self,separate=False):
        if separate:
            subprocess.run(['git','clone','-q',str(self.f.workspace/'base'),str(self.f.workspace/'accepted')],check=True)
            self.f.write(self.f.workspace/'base/tracked','distinct parent')
            subprocess.run(['git','-C',str(self.f.workspace/'base'),'-c','user.name=Fixture',
                            '-c','user.email=fixture@example.invalid','commit','-qam','parent'],check=True)
            self.f.base_revision=subprocess.check_output(['git','-C',str(self.f.workspace/'base'),'rev-parse','HEAD'],text=True).strip()
        common=self.f.args('producer-common','common',self.root/'producer-common-common')
        self.cli('seal',common);manifests=[self.transfer(common)]
        self.arms=('C','A','B') if separate else ('C','B')
        for arm in self.arms:
            root=self.f.root/arm
            self.f.write(root/'graphs/own.graph',arm+' original own-writer bytes')
            self.f.write(root/'runtime/writer.jar',arm+' original writer')
            self.f.json(root/'runtime-manifest.json',{'toolchainIdentity':self.f.system_tools})
            packet=checkpoint.read(root/'packet.json') if (root/'packet.json').exists() else {}
            packet.update(revision=self.f.base_revision if arm=='A' else self.f.revision,
                          role={'C':'accepted-baseline','A':'parent','B':'candidate'}[arm])
            packet.setdefault('pins',{})[str(self.f.root/'source-inputs.json')]=checkpoint.sha(self.f.root/'source-inputs.json')
            self.f.json(root/'packet.json',packet)
            args=self.f.args('producer-arm',arm,self.root/('producer-arm-'+arm),[Path(common.directory)/'manifest.json'])
            self.cli('seal',args);manifest=self.transfer(args)
            self.assertNotIn(str(self.f.root/'source-inputs.json'),manifest['payload'])
            manifests.append(manifest)
        self.before={name:Path(name).read_bytes() for value in manifests for name in value['payload']}
        for name in self.before:Path(name).unlink()
        for name in sorted({name for value in manifests for name in value['directories']},key=len,reverse=True):
            p=Path(name)
            if p.exists() and not list(p.iterdir()):p.rmdir()
        self.args=self.f.args('producer','producer',self.root)
        return manifests

    def roundtrip(self,separate):
        self.prepare(separate);self.cli('merge-producer',self.args)
        directory=self.root/'producer-producer';manifest=checkpoint.read(directory/'manifest.json')
        self.assertEqual([],manifest['upstream'])
        self.assertEqual('producer',manifest['identity']['stage'])
        self.assertEqual(self.before,{name:Path(name).read_bytes() for name in self.before})
        aliases=checkpoint.read(self.f.root/'arm-directories.json')
        self.assertEqual({'C':'C','A':'A' if separate else 'C','B':'B'},aliases['directories'])
        before={name:Path(name).read_bytes() for name in manifest['payload']}
        self.f.remove_payload(manifest)
        args=self.f.args(directory=directory)
        self.transfer(args);self.cli('restore',args)
        self.assertEqual(before,{name:Path(name).read_bytes() for name in manifest['payload']})
        for arm in self.arms:self.assertEqual(arm+' original own-writer bytes',(self.f.root/arm/'graphs/own.graph').read_text())

    def test_common_two_arms_merge_zip_original_consumer(self):self.roundtrip(False)
    def test_common_three_distinct_arms_merge_original_consumer(self):self.roundtrip(True)

    def test_missing_extra_foreign_identity_and_conflicting_common_fail_closed(self):
        for kind in ('missing','extra','head','run','common','common-bytes','image'):
            with self.subTest(kind=kind):
                case=ProducerMergeSmokeTests();case.setUp()
                try:
                    case.prepare();path=case.root/'producer-arm-B/manifest.json'
                    if kind=='missing':shutil.rmtree(path.parent)
                    elif kind=='extra':(case.root/'producer-arm-A').mkdir()
                    else:
                        value=checkpoint.read(path)
                        if kind=='head':value['identity']['head']='a'*40
                        elif kind=='run':value['identity']['runId']='9876'
                        elif kind=='common-bytes':value['files'][str(case.f.root/'source-inputs.json')]['sha256']='0'*64
                        elif kind=='image':value['runnerImage']['ImageVersion']='foreign-image'
                        else:value['upstream'][0]['sha256']='0'*64
                        checkpoint.save(path,value)
                    with self.assertRaises(ValueError):checkpoint.merge_producer(case.args)
                    self.assertFalse((case.root/'producer-producer').exists())
                    self.assertFalse((case.f.root/'arm-directories.json').exists())
                    self.assertFalse(case.f.binary.exists())
                finally:case.doCleanups()


if __name__=='__main__':unittest.main()
