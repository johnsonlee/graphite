#!/bin/bash
# usage: sample.sh <pid> <count> <interval_s> <outfile>
PID=$1; N=$2; IV=$3; OUT=$4; : > "$OUT"
for i in $(seq 1 $N); do
  gdb -p $PID -batch -ex "set pagination off" -ex "thread apply all bt 30" 2>/dev/null >> "$OUT"
  echo "=== SAMPLE END ===" >> "$OUT"
  sleep $IV
done
