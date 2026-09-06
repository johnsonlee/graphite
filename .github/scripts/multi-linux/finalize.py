"""Always preserve source/output digests; never run Java or upgrade partial status."""
from common import *
import platform
out=Path('diagnostic-output');out.mkdir(exist_ok=True)
write(out/'multi-finalization.json',{'jobStatus':os.environ.get('MULTI_JOB_STATUS'),'platform':platform.platform(),'hostCpuCount':os.cpu_count(),'github':{k:os.environ.get(k) for k in ['GITHUB_SHA','GITHUB_REF','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_REPOSITORY']},'sourceHashes':identity([p for p in ROOT.rglob('*') if p.is_file() and '__pycache__' not in p.parts]),'outputHashes':identity([p for p in out.rglob('*') if p.is_file() and p.name!='multi-finalization.json' and 'classes' not in p.parts and p.suffix!='.jar']),'performanceAcceptance':False,'profilingCompleted':False})
