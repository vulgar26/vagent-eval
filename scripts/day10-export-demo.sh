#!/usr/bin/env bash
#
# Day10 一键演示（Bash）：依赖 curl、jq。逻辑与 day10-export-demo.ps1 相同。
#
# 做什么：
#   创建数据集 → 导入 2 题 → probe 跑两轮 → 写 out/day10-report.json 与 out/day10-compare.json
#
# 怎么用：
#   1) 先启动服务并打开管理 API：
#        mvn spring-boot:run -Dspring-boot.run.arguments=--eval.api.enabled=true
#   2) 在项目根执行：
#        bash scripts/day10-export-demo.sh
#
# 环境变量：
#   EVAL_BASE — 服务根 URL，默认 http://localhost:8099
#
# 详见：docs/day10-guide.md
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${EVAL_BASE:-http://localhost:8099}"
BASE="${BASE%/}"
OUT="$ROOT/out"
mkdir -p "$OUT"

# 轮询直到 run 为 FINISHED
wait_finished() {
  local run_id="$1"
  local i=0
  while [[ $i -lt 450 ]]; do
    st="$(curl -fsS "$BASE/api/v1/eval/runs/$run_id" | jq -r .status)"
    if [[ "$st" == "FINISHED" ]]; then return 0; fi
    if [[ "$st" == "CANCELLED" ]]; then echo "run cancelled: $run_id" >&2; exit 1; fi
    sleep 0.2
    i=$((i + 1))
  done
  echo "timeout: $run_id" >&2
  exit 1
}

echo "EVAL_BASE=$BASE"

# --- 创建数据集 ---
dataset_id="$(curl -fsS -X POST "$BASE/api/v1/eval/datasets" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Day10 demo","version":"1","description":"day10-export-demo"}' | jq -r .dataset_id)"
echo "dataset_id=$dataset_id"

# --- 导入 JSONL ---
jsonl=$'{"case_id":"d10_ok","question":"plain smoke","expected_behavior":"answer","requires_citations":false,"tags":["day10"]}\n{"case_id":"d10_bad","question":"BAD_CONTRACT","expected_behavior":"answer","requires_citations":false,"tags":["day10"]}\n'
curl -fsS -X POST "$BASE/api/v1/eval/datasets/$dataset_id/import" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary "$jsonl" >/dev/null

# --- 第一轮 run → report ---
run_body="$(jq -nc --arg d "$dataset_id" '{dataset_id:$d,target_id:"probe"}')"
base_run_id="$(curl -fsS -X POST "$BASE/api/v1/eval/runs" -H 'Content-Type: application/json' -d "$run_body" | jq -r .run_id)"
echo "base_run_id=$base_run_id"
wait_finished "$base_run_id"

curl -fsS "$BASE/api/v1/eval/runs/$base_run_id/report" | jq . >"$OUT/day10-report.json"
echo "wrote out/day10-report.json"

# --- 第二轮 run → compare(base, cand) ---
cand_run_id="$(curl -fsS -X POST "$BASE/api/v1/eval/runs" -H 'Content-Type: application/json' -d "$run_body" | jq -r .run_id)"
echo "cand_run_id=$cand_run_id"
wait_finished "$cand_run_id"

curl -fsS "$BASE/api/v1/eval/compare?base_run_id=${base_run_id}&cand_run_id=${cand_run_id}" | jq . >"$OUT/day10-compare.json"
echo "wrote out/day10-compare.json"
echo "Done."
