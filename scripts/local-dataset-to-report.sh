#!/usr/bin/env bash
#
# 本机一条流：创建 dataset → 从 JSONL 导入 → 同一 target 跑两轮 run → 导出 report 与 compare。
# 默认：EVAL_BASE=http://localhost:8099，数据集 scripts/ci-datasets/p0-smoke.jsonl，target=probe
#
# 环境变量：
#   EVAL_BASE           评测根 URL
#   EVAL_DATASET_FILE   JSONL 路径（可选）
#   EVAL_TARGET_ID      默认 probe
#   EVAL_API_TOKEN      若启用管理 API token，则加入 X-Eval-Api-Token
#
# 用法：
#   chmod +x scripts/local-dataset-to-report.sh   # 首次
#   ./scripts/local-dataset-to-report.sh
#   EVAL_DATASET_FILE=/path/to/p0-dataset-v0.jsonl EVAL_TARGET_ID=vagent ./scripts/local-dataset-to-report.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${EVAL_BASE:-http://localhost:8099}"
BASE="${BASE%/}"
DATASET_FILE="${EVAL_DATASET_FILE:-$ROOT/scripts/ci-datasets/p0-smoke.jsonl}"
TARGET_ID="${EVAL_TARGET_ID:-probe}"
OUT="${ROOT}/out"
mkdir -p "$OUT"

hdr=()
if [[ -n "${EVAL_API_TOKEN:-}" ]]; then
  hdr=(-H "X-Eval-Api-Token: ${EVAL_API_TOKEN}")
fi

curl_json() {
  curl -fsS "${hdr[@]}" -H 'Content-Type: application/json' "$@"
}

wait_finished() {
  local run_id="$1"
  local i=0
  while [[ $i -lt 1200 ]]; do
    st="$(curl -fsS "${hdr[@]}" "$BASE/api/v1/eval/runs/$run_id" | jq -r .status)"
    if [[ "$st" == "FINISHED" ]]; then return 0; fi
    if [[ "$st" == "CANCELLED" ]]; then echo "run cancelled: $run_id" >&2; exit 1; fi
    sleep 2
    i=$((i + 1))
  done
  echo "timeout: $run_id" >&2
  exit 1
}

echo "EVAL_BASE=$BASE"
echo "EVAL_DATASET_FILE=$DATASET_FILE"
echo "EVAL_TARGET_ID=$TARGET_ID"

dataset_id="$(curl_json -X POST "$BASE/api/v1/eval/datasets" \
  -d '{"name":"local-flow","version":"1","description":"local-dataset-to-report"}' | jq -r .dataset_id)"
echo "dataset_id=$dataset_id"

curl -fsS "${hdr[@]}" -X POST "$BASE/api/v1/eval/datasets/$dataset_id/import" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary "@$DATASET_FILE" >/dev/null
echo "import ok"

run_body="$(jq -nc --arg d "$dataset_id" --arg t "$TARGET_ID" '{dataset_id:$d,target_id:$t}')"
base_run_id="$(curl_json -X POST "$BASE/api/v1/eval/runs" -d "$run_body" | jq -r .run_id)"
echo "base_run_id=$base_run_id"
wait_finished "$base_run_id"
curl -fsS "${hdr[@]}" "$BASE/api/v1/eval/runs/$base_run_id/report" | jq . >"$OUT/local-report.json"
echo "wrote out/local-report.json"

cand_run_id="$(curl_json -X POST "$BASE/api/v1/eval/runs" -d "$run_body" | jq -r .run_id)"
echo "cand_run_id=$cand_run_id"
wait_finished "$cand_run_id"
curl -fsS "${hdr[@]}" "$BASE/api/v1/eval/compare?base_run_id=$base_run_id&cand_run_id=$cand_run_id" | jq . >"$OUT/local-compare.json"
echo "wrote out/local-compare.json"
echo "Done."
