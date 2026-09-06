#!/bin/sh
# Fetch a fixed public release. Never execute the downloaded application.
set -eu
artifact_dir=${1:?usage: fetch-artifact.sh OUTPUT_DIRECTORY}
mkdir -p "$artifact_dir"
curl --fail --location --max-time 60 --dump-header "$artifact_dir/artifact.headers" https://repo.maven.apache.org/maven2/org/springframework/cloud/task/app/timestamp-task/2.1.1.RELEASE/timestamp-task-2.1.1.RELEASE.jar -o "$artifact_dir/timestamp-task-2.1.1.RELEASE.jar"
python3 - "$artifact_dir/timestamp-task-2.1.1.RELEASE.jar" <<'PY'
import hashlib,pathlib,sys,zipfile
p=pathlib.Path(sys.argv[1]);h=hashlib.sha256(p.read_bytes()).hexdigest()
assert h=='8076696d54b72f75640859b53a5c8be135955f79c1d7666222b187a68a8f07ca',h
z=zipfile.ZipFile(p);assert 'BOOT-INF/classes/org/springframework/cloud/task/app/timestamp/TimestampTaskApplication.class' in z.namelist()
assert len([n for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')])==52
print(h,p)
PY
