"""Archive a fixed integration input tree, including module-external test references.
Usage: python3 archive_inputs.py REPOSITORY REVISION EMPTY_DESTINATION
Production deltas must be applied afterwards and inventoried separately.
"""
from pathlib import Path
import sys, subprocess, tarfile, io, hashlib, json
repo, revision, destination = sys.argv[1:]
dest=Path(destination);dest.mkdir(parents=True, exist_ok=False)
revision=subprocess.check_output(['git','-C',repo,'rev-parse',revision+'^{commit}'],text=True).strip()
roots=['graphite-server','graphite-explore','CONVENTIONS.md']
data=subprocess.check_output(['git','-C',repo,'archive',revision,*roots]);(dest/'inputs.tar').write_bytes(data)
files=[]
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
 for member in archive:
  if not member.isfile(): continue
  payload=archive.extractfile(member).read();p=dest/'source'/member.name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(payload)
  files.append({'path':member.name,'sha256':hashlib.sha256(payload).hexdigest()})
(dest/'input-inventory.json').write_text(json.dumps({'revision':revision,'roots':roots,'archiveSHA256':hashlib.sha256(data).hexdigest(),'files':files},indent=2)+'\n')
