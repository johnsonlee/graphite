import copy,hashlib,unittest
from unittest.mock import patch
import native_legal_response as legal

class LegalResponseTest(unittest.TestCase):
    def fixture(self,count=60):
        query="MATCH (c)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS 'android.permission.INTERNET' RETURN id(c) AS source, id(n) AS target, type(r) AS relationship, c.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50"
        case={'id':'slow-dataflowSourceHit','family':'full-slow-shape-catalog','request':{'endpoint':'/api/cypher','body':{'query':query}},'querySha256':hashlib.sha256(query.encode()).hexdigest(),'targetGraphIds':['g1','g2']}
        rows=[{'source':0,'target':i,'relationship':'DATAFLOW','value':'android.permission.INTERNET','caller':'C','graphId':'g1','$metadata':{'graphIds':['g1']}} for i in range(count)]
        oracle={'schema':'graphite.dataflow-legal-oracle.v1','policy':legal.POLICY,'querySha256':case['querySha256'],'limit':50,'allGraphScansComplete':True,'exactEncounterOrderClaim':False,'graphCount':2,'graphIds':['g1','g2'],'columns':legal.COLUMNS,'totalMatches':count,'rows':[{'value':r,'multiplicity':1} for r in rows]}
        body={'columns':legal.COLUMNS[:],'rows':rows[-50:][::-1],'rowCount':min(count,50),'graphCount':2,'total':{'value':min(count,51),'relation':'gte' if count>50 else 'eq'}}
        return case,oracle,body

    def test_unordered_legal_subset_and_all_complete_rows(self):
        for count in (0,5,50,51,60):
            case,oracle,body=self.fixture(count);validator=legal.CompiledDataflowOracle(oracle,case)
            got=validator.validate(body);self.assertEqual(min(count,50),got['rows']);self.assertEqual(count,got['completeMatches']);self.assertFalse(got['exactEncounterOrderClaim'])

    def test_values_types_provenance_and_duplicates_cannot_pass(self):
        case,oracle,body=self.fixture();validator=legal.CompiledDataflowOracle(oracle,case)
        for key,value in [('source',False),('target',999),('value',None),('caller','Wrong'),('$metadata',{'graphIds':['g2']}),('graphId','g2')]:
            bad=copy.deepcopy(body);bad['rows'][0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validator.validate(bad)
        bad=copy.deepcopy(body);bad['rows'][1]=bad['rows'][0]
        with self.assertRaises(ValueError):validator.validate(bad)

    def test_envelope_and_probe_count_are_exact(self):
        case,oracle,body=self.fixture();validator=legal.CompiledDataflowOracle(oracle,case)
        for key,value in [('columns',list(reversed(legal.COLUMNS))),('rowCount',49),('rows',body['rows'][:-1]),('total',{'value':51,'relation':'eq'}),('graphCount',True)]:
            bad=copy.deepcopy(body);bad[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validator.validate(bad)
        bad=copy.deepcopy(body);bad['extra']='ignored?'
        with self.assertRaises(ValueError):validator.validate(bad)

    def test_query_scope_and_complete_oracle_required(self):
        case,oracle,_=self.fixture()
        for key,value in [('targetGraphIds',['g1']),('querySha256','a'*64),('family','wrong')]:
            bad=copy.deepcopy(case);bad[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):legal.CompiledDataflowOracle(oracle,bad)
        for key,value in [('totalMatches',59),('allGraphScansComplete',False),('exactEncounterOrderClaim',True),('graphIds',['g1']),('rows',oracle['rows']+[oracle['rows'][0]])]:
            bad=copy.deepcopy(oracle);bad[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):legal.CompiledDataflowOracle(bad,case)

    def test_compilation_snapshots_oracle_and_request_validation_never_rescans(self):
        case,oracle,body=self.fixture();validator=legal.CompiledDataflowOracle(oracle,case)
        oracle['rows'].clear();oracle['graphCount']=900;oracle['columns']=[]
        original_key=legal.key
        with patch.object(legal,'key',wraps=original_key) as key:
            for _ in range(3):self.assertEqual(60,validator.validate(body)['completeMatches'])
        self.assertEqual(150,key.call_count)
        with self.assertRaises(TypeError):validator.allowed['injected']=1

    def test_float_and_integer_are_distinct(self):
        case,oracle,body=self.fixture(1);oracle['rows'][0]['value']['value']=1.1;body['rows'][0]['value']=1.1
        validator=legal.CompiledDataflowOracle(oracle,case);validator.validate(body)
        body['rows'][0]['value']=1
        with self.assertRaises(ValueError):validator.validate(body)

if __name__=='__main__':unittest.main()
