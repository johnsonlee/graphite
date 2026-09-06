"""Shared fail-closed identity and process helpers for the Linux draft."""
from pathlib import Path
import hashlib,json,os,subprocess,sys,signal
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'tools'))
from run import graph_identity
from verify_run import validate_catalog,verify_run,read_tsv

def require(ok,message):
    if not ok: raise ValueError(message)
def sha(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def write(p,v):Path(p).write_text(json.dumps(v,indent=2)+'\n')
def read(p):return json.loads(Path(p).read_text())
def identity(paths):return {str(Path(p).resolve()):sha(p) for p in paths}
def unchanged(d):
    for p,h in d.items():require(sha(p)==h,'Input changed: '+p)
def check_sources():
    pins=read(ROOT/'pins.json')
    for p,h in pins['sourceFiles'].items():require(sha(ROOT/p)==h,'Bundled frozen source differs: '+p)
    return pins
def require_linux():
    require(sys.platform=='linux','Linux-only execution; this draft must not capture on another platform')
    for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
        require(not os.environ.get(key),'Unexpected JVM injection through '+key)
def no_java():
    """Reject any visible leftover Java process; never kill unrelated processes."""
    found=[]
    for p in Path('/proc').glob('[0-9]*/comm'):
        try:
            if p.read_text().strip() in ('java','javac'):
                found.append({'pid':int(p.parent.name),'command':(p.parent/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')})
        except (FileNotFoundError,ProcessLookupError):pass
    require(not found,'Residual/concurrent Java processes: '+repr(found))
    return {'visibleJavaProcesses':found,'scope':'visible /proc at boundary; does not prove absence between checks'}
def run_command(cmd,log,timeout=7200,cwd=None):
    """One process group; reap it on failure/timeout rather than leaving a Java fork."""
    cmd=list(map(str,cmd))
    with Path(log).open('w') as f:
        p=subprocess.Popen(cmd,cwd=cwd,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=p.wait(timeout=timeout)
        except BaseException:
            try:os.killpg(p.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            p.wait();raise
    require(code==0,f'Command exit {code}: {cmd!r}; log {log}')
    return code

def compare_catalogs(actual,frozen):
    a,b=read(actual/'catalog.json'),read(frozen/'catalog.json')
    validate_catalog(a);validate_catalog(b)
    require(a['schema']==b['schema']=='graphite-wide-query-oracle-v4','Expected V4 all38 catalog')
    # Exact content, not merely counts/digests. Paths/raw archive hashes are separately bound to their own platform.
    fields=['schema','frozenRevision','inputGraphs','totalCallSites','perGraphCallSiteCounts','logicalCases','queries','semantics','unlabeledPropertyProof','zeroTermSelection','preservedV3CatalogSha256']
    for k in fields:require(a[k]==b[k],'Linux/local independent oracle differs: '+k)
    require((actual/'workloads.tsv').read_bytes()==(frozen/'workloads.tsv').read_bytes(),'All38 workload bytes differ')
    return {'passed':True,'exactFields':fields,'queryCount':38,'exactWorkloadsBytes':True,'metadataDifferences':{k:[b.get(k),a.get(k)] for k in sorted(set(a)|set(b)) if a.get(k)!=b.get(k)},'localSha256':sha(frozen/'catalog.json'),'linuxSha256':sha(actual/'catalog.json')}
