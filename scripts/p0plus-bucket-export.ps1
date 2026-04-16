<#
.SYNOPSIS
    P0+ S3：导出单次 run 的汇总报表 + 按 tags 前缀分桶子报表（JSON 文件）。

.DESCRIPTION
    依赖本机已启动 vagent-eval，且管理 API 已打开：
      mvn spring-boot:run "-Dspring-boot.run.arguments=--eval.api.enabled=true"

    产物：
      - out/p0plus-run-{runId}-report.json
      - out/p0plus-run-{runId}-buckets.json

    环境变量：
      - EVAL_BASE：评测服务根 URL，默认 http://localhost:8099

    参数：
      -RunId 必填
      -TagPrefix 可选；可传多次。不传则服务端使用默认三桶（attack/、rag/empty、rag/low_conf）

    示例：
      powershell -ExecutionPolicy Bypass -File .\scripts\p0plus-bucket-export.ps1 -RunId run_abcd
      powershell -ExecutionPolicy Bypass -File .\scripts\p0plus-bucket-export.ps1 -RunId run_abcd -TagPrefix "attack/" -TagPrefix "rag/empty"
#>
param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [string[]]$TagPrefix = @(),
    [int]$ErrorCodeTopN = 5
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Base = if ($env:EVAL_BASE) { $env:EVAL_BASE.TrimEnd('/') } else { 'http://localhost:8099' }
$OutDir = Join-Path $Root 'out'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "EVAL_BASE=$Base run_id=$RunId"

$reportUri = "$Base/api/v1/eval/runs/$RunId/report?error_code_top_n=$ErrorCodeTopN"
$report = Invoke-RestMethod -Uri $reportUri -Method Get
$report | ConvertTo-Json -Depth 30 | Set-Content -Path (Join-Path $OutDir "p0plus-run-$RunId-report.json") -Encoding utf8
Write-Host "wrote out/p0plus-run-$RunId-report.json"

function Encode-QueryValue([string]$s) {
    if ($null -eq $s) { return '' }
    return [System.Uri]::EscapeDataString($s)
}

$queryParts = @("error_code_top_n=$(Encode-QueryValue("$ErrorCodeTopN"))")
foreach ($p in $TagPrefix) {
    if ($null -ne $p -and "$p".Trim().Length -gt 0) {
        $queryParts += ("tag_prefix=" + (Encode-QueryValue($p.Trim())))
    }
}
$bucketsUri = "$Base/api/v1/eval/runs/$RunId/report/buckets?" + ($queryParts -join '&')

$buckets = Invoke-RestMethod -Uri $bucketsUri -Method Get
$buckets | ConvertTo-Json -Depth 30 | Set-Content -Path (Join-Path $OutDir "p0plus-run-$RunId-buckets.json") -Encoding utf8
Write-Host "wrote out/p0plus-run-$RunId-buckets.json"
