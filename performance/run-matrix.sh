#!/usr/bin/env bash
# Runs performance.js across all workload × contention combinations.
#
# Usage:
#   ./performance/run-matrix.sh
#   BASE_URL=http://localhost:7000 MAX_VUS=100 ./performance/run-matrix.sh
#
# Each run is 5 minutes. 4 workloads × 3 contention levels = 12 runs = 60 min total.
# Warmup (seed + JIT) is run once at the start (~2 min); reused for all runs.
#
# Between runs the script triggers a full GC inside the container via jcmd so
# that accumulated heap from the previous run doesn't distort the next run's
# p99 latency. Requires Docker and that the container was started with `make run`.

set -euo pipefail

BASE_URL=${BASE_URL:-http://localhost:7001}
MAX_VUS=${MAX_VUS:-30}
HOT_KEYS=${HOT_KEYS:-1}
COLD_KEYS=${COLD_KEYS:-10000}
THINK_MS=${THINK_MS:-5}
IMAGE=${IMAGE:-kv}

RESULTS_DIR="results/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RESULTS_DIR"

WORKLOADS=(balanced read-heavy write-heavy update-heavy)
CONTENTIONS=(0.1 0.5 0.9)

# ── Helpers ───────────────────────────────────────────────────────────────────

force_gc() {
  local cid
  cid=$(docker ps -q --filter "ancestor=${IMAGE}" 2>/dev/null | head -1)
  if [[ -z "$cid" ]]; then
    echo "[gc] no running ${IMAGE} container found — skipping GC"
    return
  fi
  echo "[gc] triggering full GC in container $cid..."
  # PID 1 is the JVM when using the exec form of ENTRYPOINT in Docker.
  docker exec "$cid" jcmd 1 GC.run 2>&1 || echo "[gc] jcmd not available — skipping GC"
  # GC.run is async; give the collector ~5 s to finish before the next run starts.
  sleep 60
}

# ── Run ───────────────────────────────────────────────────────────────────────

echo "============================================================"
echo " KV store performance matrix"
echo " BASE_URL=$BASE_URL  MAX_VUS=$MAX_VUS"
echo " ${#WORKLOADS[@]} workloads × ${#CONTENTIONS[@]} contention levels = $((${#WORKLOADS[@]} * ${#CONTENTIONS[@]})) runs × 5 min"
echo " Results → $RESULTS_DIR/"
echo "============================================================"
echo ""

echo "--> Running warmup (seed + JIT) — this runs once for all scenarios"
k6 run \
  -e BASE_URL="$BASE_URL" \
  -e HOT_KEYS="$HOT_KEYS" \
  -e COLD_KEYS="$COLD_KEYS" \
  -e MAX_VUS="$MAX_VUS" \
  performance/warmup.js
echo ""

RUN=0
TOTAL=$(( ${#WORKLOADS[@]} * ${#CONTENTIONS[@]} ))

# Summary tracking (parallel arrays, one entry per run).
S_WORKLOAD=(); S_CONTENTION=(); S_START=(); S_END=(); S_JSON=(); S_HTML=()

for workload in "${WORKLOADS[@]}"; do
  for contention in "${CONTENTIONS[@]}"; do
    RUN=$(( RUN + 1 ))
    echo "------------------------------------------------------------"
    echo " Run $RUN/$TOTAL: workload=$workload  contention=$contention"
    echo "------------------------------------------------------------"

    RUN_START=$(date '+%H:%M:%S')
    MARKER=$(mktemp)

    k6 run \
      -e BASE_URL="$BASE_URL" \
      -e WORKLOAD="$workload" \
      -e CONTENTION="$contention" \
      -e HOT_KEYS="$HOT_KEYS" \
      -e COLD_KEYS="$COLD_KEYS" \
      -e MAX_VUS="$MAX_VUS" \
      -e THINK_MS="$THINK_MS" \
      -e RESULT_PATH="$RESULTS_DIR" \
      performance/performance.js || true   # don't abort the matrix on a threshold breach

    RUN_END=$(date '+%H:%M:%S')

    JSON_FILE=$(find $RESULTS_DIR/ -maxdepth 1 -newer "$MARKER" -name "*.json" 2>/dev/null | sort | tail -1)
    HTML_FILE=$(find $RESULTS_DIR/ -maxdepth 1 -newer "$MARKER" -name "*.html" 2>/dev/null | sort | tail -1)
    rm -f "$MARKER"

    S_WORKLOAD+=("$workload"); S_CONTENTION+=("$contention")
    S_START+=("$RUN_START");   S_END+=("$RUN_END")
    S_JSON+=("${JSON_FILE:-—}"); S_HTML+=("${HTML_FILE:-—}")

    if [[ $RUN -lt $TOTAL ]]; then
      force_gc
    fi
    echo ""
  done
done

echo "============================================================"
echo " Matrix summary — $TOTAL runs"
echo ""
for i in "${!S_WORKLOAD[@]}"; do
  printf " #%-2d  %-13s  c=%-4s  %s → %s\n" \
    $(( i + 1 )) "${S_WORKLOAD[$i]}" "${S_CONTENTION[$i]}" "${S_START[$i]}" "${S_END[$i]}"
  printf "       json: %s\n" "${S_JSON[$i]}"
  printf "       html: %s\n" "${S_HTML[$i]}"
  echo ""
done
echo "============================================================"
