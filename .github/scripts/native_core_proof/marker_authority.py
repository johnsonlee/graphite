"""One explicit raw bootstrap marker, never a generic missing-class waiver."""
from pathlib import Path
import hashlib,json
from .legacy_wire import need

def sha(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()

def marker_class(raw):
    from . import field_authority
    value=field_authority.parse(raw)
    need(value['owner']=='java.io.Serializable' and value['superName']=='java.lang.Object' and not value['interfaces'] and not value['fields'] and value['accessFlags']&0x0200,'exact empty Serializable marker declaration')
    return value

class Marker:
    def __init__(self,ref):
        need(set(ref)=={'path','sha256'},'explicit marker export reference')
        record_path=Path(ref['path']);need(sha(record_path)==ref['sha256'],'marker export record changed')
        record=json.loads(record_path.read_text());root=record_path.parent
        if record.get('schema') == 'graphite.native-core-marker-export-audit.v1':
            # Independently replay the exact bootstrap authority, without carrying
            # unrelated graph pins into this graph receipt. The outer pair runner
            # separately replays complete artifact audits. Historical path stays unchanged.
            import export_native_core_marker as exporter
            checked=exporter.field_evidence(root)
            need(record['rawClass']==checked['rawClass'],'portable marker audit differs from raw evidence')
            self.pins={**checked['pins'],str(record_path):ref['sha256']}
            self.raw=Path(checked['rawClass']['path']).read_bytes()
            self.digest=hashlib.sha256(self.raw).hexdigest()
            need(self.digest==checked['rawClass']['sha256'],'portable raw marker identity')
            self.parsed=marker_class(self.raw);self.record_ref=ref
            self.pins[str(Path(__file__))]=sha(Path(__file__))
            return
        need(record['status']=='PASS_EXACT_RUNTIME_MARKER_CLASS' and not record['errors'] and record['before']==record['after'],'completed and unchanged marker export')
        need([p['name'] for p in record['phases']]==['compile-bootstrap-marker','export-bootstrap-marker'],'exact owned marker phases')
        for phase in record['phases']:
            need(phase['exit']==phase['cleanupExit']==0 and not phase['cleanupErrors'] and not phase['groupProof']['after'] and not phase['groupProof']['errors'],'clean completed marker phase')
        self.pins={str(record_path):ref['sha256'],**record['pins'],**record['compiledHelper']}
        need(set(record['compiledHelper'])=={str(root/'classes/ExportSerializable.class')},'actual marker compiled helper')
        proof_path=root/'receipt.json';need(sha(proof_path)==record['receiptSha256'],'marker receipt binding')
        proof=json.loads(proof_path.read_text());raw_path=Path(record['rawClassFile'])
        need(raw_path.resolve()==(root/'Serializable.class').resolve() and Path(proof['rawClassFile']).resolve()==raw_path.resolve(),'exact archived raw marker path')
        self.pins[str(proof_path)]=record['receiptSha256'];self.pins[str(raw_path)]=record['classBytesSha256']
        need(proof['status']=='PASS_EXACT_BOOTSTRAP_SERIALIZABLE_EXPORT' and proof['className']=='java.io.Serializable' and proof['module']=='java.base' and proof['resource']=='java/io/Serializable.class' and proof['bootstrapClassLoader'] is True,'actual bootstrap resource')
        modules=str(Path(proof['javaHome'])/'lib/modules')
        need(proof['modulesPath']==modules and self.pins[modules]==proof['modulesSha256'],'bound module image')
        need(set(record['producerRefs'])=={'C','B'},'both real producers')
        for arm,item in record['producerRefs'].items():
            need(item['modulesSha256']==proof['modulesSha256'],'same producer module image')
            need(sha(item['packet'])==item['sha256'],'actual producer packet')
            packet=json.loads(Path(item['packet']).read_text());need(packet['revision']==item['revision'] and packet['finalIdentity']['inputs']=='PASS','producer final input verification')
            snap=item['inputsBefore'];need(sha(snap['path'])==snap['sha256'] and self.pins[snap['path']]==snap['sha256'],'producer before snapshot')
            need(json.loads(Path(snap['path']).read_text())[modules]==proof['modulesSha256'],'exact producer module authority')
        need(all(sha(p)==h for p,h in self.pins.items()),'marker authority input drift')
        self.raw=raw_path.read_bytes();self.digest=hashlib.sha256(self.raw).hexdigest()
        need(self.digest==record['classBytesSha256']==proof['classBytesSha256'],'raw marker identity')
        self.parsed=marker_class(self.raw);self.record_ref=ref
        self.pins[str(Path(__file__))]=sha(Path(__file__))
    def archive(self,out):
        path=Path(out)/'classfiles/java/io/Serializable.class';path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('xb') as f:f.write(self.raw)
        return dict(self.parsed,classBytesSha256=self.digest,rawClassFile=str(path),platformModule='java.base',platformExport=self.record_ref,
                    sourceKind='EXACT_BOOTSTRAP_MARKER_NOT_CORPUS_JAR')
