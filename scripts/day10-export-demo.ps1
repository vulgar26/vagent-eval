<#
.SYNOPSIS
    Day10 一键演示：在本机已启动的 vagent-eval 上走通「数据集 → 两轮 probe 评测 → 导出 report 与 compare」。

.DESCRIPTION
    做什么：
      1) POST 创建数据集并导入 2 条用例（一题正常、一题触发探针 BAD_CONTRACT）
      2) 对 target「probe」连续跑两次 run（作为 base / cand）
      3) 拉取第一次 run 的 GET .../report → 写入仓库根 out/day10-report.json
      4) 拉取 GET /api/v1/eval/compare → 写入 out/day10-compare.json

    怎么用：
      - 另开终端先启动服务，且必须打开管理 API（否则 Day9 Filter 会 404）：
          mvn spring-boot:run "-Dspring-boot.run.arguments=--eval.api.enabled=true"
      - 确认 eval.probe.enabled=true（仓库默认 true），且 probe 的 base-url 指向本服务端口。
      - 再执行（若脚本执行策略拦截，用 Bypass）：
          powershell -ExecutionPolicy Bypass -File .\scripts\day10-export-demo.ps1

    环境变量：
      - EVAL_BASE：评测服务根 URL，默认 http://localhost:8099

    产物：
      - out/day10-report.json、out/day10-compare.json（目录已在 .gitignore，勿当机密提交）

    详见：docs/day10-guide.md
#>
$ErrorActionPreference = 'Stop'

# 仓库根目录（脚本在 scripts/ 下）
$Root = Split-Path -Parent $PSScriptRoot
# 服务根地址，可用 $env:EVAL_BASE 覆盖
$Base = if ($env:EVAL_BASE) { $env:EVAL_BASE.TrimEnd('/') } else { 'http://localhost:8099' }
$OutDir = Join-Path $Root 'out'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 轮询 run 状态直到 FINISHED；CANCELLED 则抛错（便于发现配置/探针问题）
function Wait-RunFinished {
    param([string]$RunId)
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-RestMethod -Uri "$Base/api/v1/eval/runs/$RunId" -Method Get
        if ($r.status -eq 'FINISHED' -or $r.status -eq 'CANCELLED') {
            if ($r.status -ne 'FINISHED') {
                throw "run $RunId ended with status $($r.status) reason=$($r.cancel_reason)"
            }
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw "timeout waiting for run $RunId"
}

Write-Host "EVAL_BASE=$Base"

# --- 创建数据集 ---
$createBody = @{ name = 'Day10 demo'; version = '1'; description = 'day10-export-demo' } | ConvertTo-Json
$ds = Invoke-RestMethod -Uri "$Base/api/v1/eval/datasets" -Method Post -ContentType 'application/json' -Body $createBody
$datasetId = $ds.dataset_id
Write-Host "dataset_id=$datasetId"

# --- 导入 JSONL：与 Day10 集成测、Bash 版脚本保持一致 ---
$jsonl = @"
{"case_id":"d10_ok","question":"plain smoke","expected_behavior":"answer","requires_citations":false,"tags":["day10"]}
{"case_id":"d10_bad","question":"BAD_CONTRACT","expected_behavior":"answer","requires_citations":false,"tags":["day10"]}
"@
Invoke-RestMethod -Uri "$Base/api/v1/eval/datasets/$datasetId/import" -Method Post -ContentType 'application/x-ndjson' -Body $jsonl | Out-Null

# --- 第一轮 run → report ---
$runBody = (@{ dataset_id = $datasetId; target_id = 'probe' } | ConvertTo-Json -Compress)
$r1 = Invoke-RestMethod -Uri "$Base/api/v1/eval/runs" -Method Post -ContentType 'application/json' -Body $runBody
$baseRunId = $r1.run_id
Write-Host "base_run_id=$baseRunId"
Wait-RunFinished -RunId $baseRunId

$report = Invoke-RestMethod -Uri "$Base/api/v1/eval/runs/$baseRunId/report" -Method Get
$report | ConvertTo-Json -Depth 20 | Set-Content -Path (Join-Path $OutDir 'day10-report.json') -Encoding utf8
Write-Host "wrote out/day10-report.json"

# --- 第二轮 run（同数据集、同 target）→ 与第一轮做 compare ---
$r2 = Invoke-RestMethod -Uri "$Base/api/v1/eval/runs" -Method Post -ContentType 'application/json' -Body $runBody
$candRunId = $r2.run_id
Write-Host "cand_run_id=$candRunId"
Wait-RunFinished -RunId $candRunId

$compare = Invoke-RestMethod -Uri "$Base/api/v1/eval/compare?base_run_id=$baseRunId&cand_run_id=$candRunId" -Method Get
$compare | ConvertTo-Json -Depth 20 | Set-Content -Path (Join-Path $OutDir 'day10-compare.json') -Encoding utf8
Write-Host "wrote out/day10-compare.json"
Write-Host 'Done.'
