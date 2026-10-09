import copy
from pathlib import Path
import unittest
from . import raw_local_export as r


class RawExportCommandsTests(unittest.TestCase):
    def setUp(self):
        self.plan={'output':'/fresh/output','java':'/jdk-B/bin/java','maxOwnedPhases':129,'pins':{str(r.SOURCE.resolve()):'s'}}
        self.roots={a:Path('/actual-'+a) for a in ('C','B')}
        self.tools={a:('/jdk-'+a+'/bin/java','/jdk-'+a+'/bin/javac') for a in ('C','B')}
        self.fixtures={}
        for arm in ('C','B'):
            self.fixtures[arm]={'inputJars':[{'corpus':c,'path':'/sources/'+c+'.jar','sha256':c} for c in ('a','b','c','d')]}
            for p in (str(self.roots[arm]/'runtime/writer.jar'),str(self.roots[arm]/'graphs/fixture-provenance.tsv'),*self.tools[arm]):self.plan['pins'][p]='p'
            for row in self.fixtures[arm]['inputJars']:self.plan['pins'][row['path']]=row['sha256']

    def configure(self):return r.configure(self.plan,self.fixtures,self.roots,self.tools)

    def test_both_writer_compiles_all_eight_exports_exact_boundaries(self):
        plan=self.configure();commands=list(r.commands(plan))
        self.assertEqual(139,plan['maxOwnedPhases']);self.assertEqual(10,len(commands))
        self.assertEqual(['compile-raw-C','compile-raw-B'],[c[0] for c in commands[:2]])
        self.assertEqual(8,len({c[1][-1] for c in commands[2:]}))
        for name,argv,timeout in commands:
            arm='C' if name.startswith(('raw-C','compile-raw-C')) else 'B'
            self.assertEqual(self.tools[arm][1 if name.startswith('compile') else 0],argv[0])
            self.assertIn(str(self.roots[arm]/'runtime/writer.jar'),next(x for x in argv if x.endswith('writer.jar')))
            self.assertEqual(self.tools[arm][0],r.phase_java(plan,name))
            self.assertIn('-J-Xmx4g' if name.startswith('compile') else '-Xmx4g',argv)
            self.assertNotIn('graphTypeName',' '.join(argv))

    def test_missing_writer_or_jdk_pin_rejected(self):
        for key in ('/actual-C/runtime/writer.jar','/jdk-C/bin/javac'):
            plan=copy.deepcopy(self.plan);del plan['pins'][key]
            with self.assertRaises(ValueError):r.configure(plan,self.fixtures,self.roots,self.tools)

    def test_missing_helper_review_pin_rejected(self):
        del self.plan['pins'][str(r.SOURCE.resolve())]
        with self.assertRaises(ValueError):self.configure()

    def test_unbound_source_jar_rejected(self):
        self.fixtures['B']['inputJars'][0]['sha256']='changed'
        with self.assertRaises(ValueError):self.configure()

    def test_duplicate_corpus_rejected(self):
        self.fixtures['B']['inputJars'][1]=self.fixtures['B']['inputJars'][0]
        with self.assertRaises(ValueError):self.configure()

    def test_existing_phases_keep_original_java(self):
        self.assertEqual(self.plan['java'],r.phase_java(self.configure(),'fixture-a-00-topology'))


if __name__=='__main__':unittest.main()
