#!/bin/bash
# usage: profile-case.sh <name> <query> <samples>
NAME=$1; Q=$2; N=${3:-30}; S=${SCRATCH:-/tmp/graphite-headroom}
PID=$(pgrep -f 'target-prof/release/graphite serve')
body=$(python3 -c 'import json,sys;print(json.dumps({"query":sys.argv[1],"timeoutMs":60000}))' "$Q")
for i in 1 2 3; do curl -s -o /dev/null -H 'Content-Type: application/json' -d "$body" http://127.0.0.1:18080/api/cypher; done
( end=$((SECONDS+25)); while [ $SECONDS -lt $end ]; do curl -s -o /dev/null -H 'Content-Type: application/json' -d "$body" http://127.0.0.1:18080/api/cypher; done ) &
LOOP=$!
sleep 0.3
$S/scripts/sample.sh $PID $N 0.2 $S/results/prof-$NAME.txt
kill $LOOP 2>/dev/null; wait $LOOP 2>/dev/null
python3 -I $S/scripts/aggregate.py $S/results/prof-$NAME.txt > $S/results/prof-$NAME.agg.txt
echo "done $NAME"
