<#
.SYNOPSIS
    本机一条流：创建 dataset → 从 JSONL 导入 → 同一 target 跑两轮 run → 导出 report 与 compare。

.DESCRIPTION
    默认用仓库内 smoke JSONL + target「probe」（仅依赖本机已起的 vagent-eval，不要求 vagent）。
    换 `-TargetId vagent` 时需被测已监听 application.yml 里配置的 base-url。

    前置（复制即跑见 README「本机一条流」）：
      - 服务已启动且打开管理 API：--eval.api.enabled=true
      - 若启用 API token，设置环境变量 EVAL_API_TOKEN（对应 X-Eval-Api-Token）

.PARAMETER DatasetFile
    要导入的 JSONL 路径；不传则使用 scripts/ci-datasets/p0-smoke.jsonl

.PARAMETER TargetId
    评测 target，默认 probe

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\local-dataset-to-report.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\local-dataset-to-report.ps1 -DatasetFile 'D:\Projects\Vagent\plans\datasets\p0-dataset-v0.jsonl' -TargetId vagent
#>
param(
    [string] $DatasetFile = '',
    [string] $TargetId = 'probe'
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Base = if ($env:EVAL_BASE) { $env:EVAL_BASE.TrimEnd('/') } else { 'http://localhost:8099' }
if (-not $DatasetFile -or $DatasetFile.Trim() -eq '') {
    $DatasetFile = Join-Path $Root 'scripts\ci-datasets\p0-smoke.jsonl'
}
if (-not (Test-Path -LiteralPath $DatasetFile)) {
    throw "DatasetFile not found: $DatasetFile"
}

$OutDir = Join-Path $Root 'out'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-EvalJson {
    param(
        [string] $Method,
        [string] $Uri,
        [object] $Body = $null,
        [string] $ContentType = 'application/json; charset=utf-8'
    )
    $p = @{ Uri = $Uri; Method = $Method; ContentType = $ContentType }
    if ($null -ne $Body) {
        if ($Body -is [byte[]]) { $p['Body'] = $Body }
        else { $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes([string]$Body) }
    }
    if ($env:EVAL_API_TOKEN) {
        $p['Headers'] = @{ 'X-Eval-Api-Token' = $env:EVAL_API_TOKEN }
    }
    Invoke-RestMethod @p
}

function Wait-RunFinished {
    param([string] $RunId)
    $deadline = (Get-Date).AddSeconds(600)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-EvalJson -Method Get -Uri "$Base/api/v1/eval/runs/$RunId"
        if ($r.status -eq 'FINISHED' -or $r.status -eq 'CANCELLED') {
            if ($r.status -ne 'FINISHED') {
                throw "run $RunId ended with status $($r.status) cancel_reason=$($r.cancel_reason)"
            }
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "timeout waiting for run $RunId"
}

Write-Host "EVAL_BASE=$Base"
Write-Host "DatasetFile=$DatasetFile"
Write-Host "TargetId=$TargetId"

$createBody = (@{ name = 'local-flow'; version = '1'; description = 'local-dataset-to-report' } | ConvertTo-Json -Compress)
$ds = Invoke-EvalJson -Method Post -Uri "$Base/api/v1/eval/datasets" -Body $createBody
$datasetId = $ds.dataset_id
Write-Host "dataset_id=$datasetId"

$jsonl = [System.IO.File]::ReadAllText((Resolve-Path $DatasetFile), [System.Text.Encoding]::UTF8)
Invoke-EvalJson -Method Post -Uri "$Base/api/v1/eval/datasets/$datasetId/import" -Body $jsonl -ContentType 'application/x-ndjson; charset=utf-8' | Out-Null
Write-Host 'import ok'

$runBody = (@{ dataset_id = $datasetId; target_id = $TargetId } | ConvertTo-Json -Compress)
$r1 = Invoke-EvalJson -Method Post -Uri "$Base/api/v1/eval/runs" -Body $runBody
$baseRunId = $r1.run_id
Write-Host "base_run_id=$baseRunId"
Wait-RunFinished -RunId $baseRunId

$report = Invoke-EvalJson -Method Get -Uri "$Base/api/v1/eval/runs/$baseRunId/report"
$report | ConvertTo-Json -Depth 30 | Set-Content -Path (Join-Path $OutDir 'local-report.json') -Encoding utf8
Write-Host "wrote out/local-report.json"

$r2 = Invoke-EvalJson -Method Post -Uri "$Base/api/v1/eval/runs" -Body $runBody
$candRunId = $r2.run_id
Write-Host "cand_run_id=$candRunId"
Wait-RunFinished -RunId $candRunId

$compare = Invoke-EvalJson -Method Get -Uri "$Base/api/v1/eval/compare?base_run_id=$baseRunId&cand_run_id=$candRunId"
$compare | ConvertTo-Json -Depth 30 | Set-Content -Path (Join-Path $OutDir 'local-compare.json') -Encoding utf8
Write-Host "wrote out/local-compare.json"
Write-Host "Done. base_run_id=$baseRunId cand_run_id=$candRunId"
