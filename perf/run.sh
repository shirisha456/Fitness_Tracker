#!/bin/bash
# Runs the k6 load test against a backend on the Compose network.
#   ./perf/run.sh java|python [label]
set -e
cd "$(dirname "$0")/.."
export PATH="$HOME/.docker/bin:$PATH"
TARGET=${1:-java}; LABEL=${2:-$TARGET}
case "$TARGET" in
  java)   BASE=http://api-java:8000/api/v1 ;;
  python) BASE=http://api:8000/api/v1 ;;
  *) echo "usage: run.sh java|python [label]"; exit 1 ;;
esac
OUT=perf/results/$LABEL; mkdir -p "$OUT"
# Environment is recorded with every run. A latency number without the machine, the commit
# and the dataset it was measured on is not a result, it is a rumour.
{
  echo "label:        $LABEL"
  echo "target:       $BASE"
  echo "commit:       $(git rev-parse HEAD)"
  echo "dirty:        $(test -n "$(git status --porcelain)" && echo yes || echo no)"
  echo "date:         $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host:         $(uname -sm)"
  echo "cpus:         $(sysctl -n hw.ncpu 2>/dev/null || nproc)"
  echo "memory:       $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 )) GB"
  echo "docker cpus:  $(docker info --format '{{.NCPU}}')"
  echo "docker mem:   $(( $(docker info --format '{{.MemTotal}}') / 1073741824 )) GB"
  echo "k6:           grafana/k6:0.53.0"
} > "$OUT/environment.txt"
docker run --rm --network fitness_tracker_default \
  -v "$PWD/perf/k6:/scripts:ro" -v "$PWD/$OUT:/results" \
  -e BASE_URL="$BASE" -e STRESS="${STRESS:-0}" --cpus "${K6_CPUS:-4}" \
  grafana/k6:0.53.0 run "/scripts/${K6_SCRIPT:-load-test.js}" 2>&1 | tee "$OUT/run.log"
