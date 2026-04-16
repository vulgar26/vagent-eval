#!/usr/bin/env bash
#
# CI：对 vagent + travel-ai 各跑一轮 smoke（默认 2 题），导出 report JSON。
# 前置：本机/CI 已启动 vagent-eval，且 eval.api.enabled=true；targets 已通过 overlay 指向真实 base-url。
#
# 环境变量：
#   EVAL_BASE          评测服务根 URL，默认 http://localhost:8099
#   CI_DATASET_FILE    JSONL 路径，默认 scripts/ci-datasets/p0-smoke.jsonl
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${EVAL_BASE:-http://localhost:8099}"
BASE="${BASE%/}"
DATASET_FILE="${CI_DATASET_FILE:-$ROOT/scripts/ci-datasets/p0-smoke.jsonl}"
OUT="${ROOT}/out"
mkdir -p "$OUT"

wait_finished() {
  local run_id="$1"
  local i=0
  while [[ $i -lt 900 ]]; do
    st="$(curl -fsS "$BASE/api/v1/eval/runs/$run_id" | jq -r .status)"
    if [[ "$st" == "FINISHED" ]]; then return 0; fi
    if [[ "$st" == "CANCELLED" ]]; then echo "run cancelled: $run_id" >&2; exit 1; fi
    sleep 0.3
    i=$((i + 1))
  done
  echo "timeout: $run_id" >&2
  exit 1
}

echo "EVAL_BASE=$BASE"
echo "CI_DATASET_FILE=$DATASET_FILE"

dataset_id="$(curl -fsS -X POST "$BASE/api/v1/eval/datasets" \
  -H 'Content-Type: application/json' \
  -d '{"name":"CI smoke","version":"1","description":"ci-full-eval"}' | jq -r .dataset_id)"
echo "dataset_id=$dataset_id"

curl -fsS -X POST "$BASE/api/v1/eval/datasets/$dataset_id/import" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary "@$DATASET_FILE" >/dev/null

for target in vagent travel-ai; do
  run_body="$(jq -nc --arg d "$dataset_id" --arg t "$target" '{dataset_id:$d,target_id:$t}')"
  run_id="$(curl -fsS -X POST "$BASE/api/v1/eval/runs" -H 'Content-Type: application/json' -d "$run_body" | jq -r .run_id)"
  echo "run_id($target)=$run_id"
  wait_finished "$run_id"
  curl -fsS "$BASE/api/v1/eval/runs/$run_id/report" | jq . >"$OUT/report-${target}.json"
  echo "wrote out/report-${target}.json"
done

echo "Done."
