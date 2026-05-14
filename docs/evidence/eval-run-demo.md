# Eval Run Demo

This document shows a sanitized end-to-end flow for `vagent-eval`. It uses placeholder IDs and a local `probe` target so the flow can be understood without real secrets or private service URLs.

The harness is rule based. It does not use LLM-as-judge scoring. During a real run it still calls the configured target service; the `probe` target is only a local demo and contract-test substitute.

## 1. Create a Dataset

```bash
curl -s -X POST http://localhost:8099/api/v1/eval/datasets \
  -H "Content-Type: application/json" \
  -d '{
    "name": "rag-smoke",
    "version": "demo-v1",
    "description": "Sanitized RAG regression smoke set"
  }'
```

Example response:

```json
{
  "dataset_id": "ds_demo_001",
  "name": "rag-smoke",
  "version": "demo-v1",
  "description": "Sanitized RAG regression smoke set",
  "case_count": 0
}
```

## 2. Import Cases

JSONL input shape:

```jsonl
{"case_id":"case_answer_citation","question":"What does the policy say about refunds?","expected_behavior":"answer","requires_citations":true,"tags":["rag/citation","smoke"]}
{"case_id":"case_clarify_empty","question":"Plan a trip for sometime soon.","expected_behavior":"clarify","requires_citations":false,"tags":["rag/empty","smoke"]}
```

Import command:

```bash
curl -s -X POST http://localhost:8099/api/v1/eval/datasets/ds_demo_001/import \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @docs/examples/day2-sample.jsonl
```

Example response:

```json
{
  "dataset_id": "ds_demo_001",
  "imported": 2,
  "case_count": 2
}
```

## 3. Create a Run

```bash
curl -s -X POST http://localhost:8099/api/v1/eval/runs \
  -H "Content-Type: application/json" \
  -d '{
    "dataset_id": "ds_demo_001",
    "target_id": "probe"
  }'
```

Example response:

```json
{
  "run_id": "run_demo_candidate",
  "dataset_id": "ds_demo_001",
  "target_id": "probe",
  "status": "PENDING",
  "total_cases": 2,
  "finished_cases": 0
}
```

## 4. Target Call

For each case, the runner calls the configured target:

```text
POST {target.base_url}/api/v1/eval/chat
```

The request includes evaluation headers such as:

```text
X-Eval-Run-Id: run_demo_candidate
X-Eval-Dataset-Id: ds_demo_001
X-Eval-Case-Id: case_answer_citation
X-Eval-Target-Id: probe
X-Eval-Membership-Top-N: 8
```

Secrets such as `X-Eval-Token` or gateway keys are supplied through local configuration or environment variables and are not included in this evidence.

## 5. Result Storage

After each target response, the harness validates the response contract and applies deterministic rules. A result row records verdict, error code, latency, sanitized debug fields, and selected target metadata.

```bash
curl -s http://localhost:8099/api/v1/eval/runs/run_demo_candidate/results
```

Example response:

```json
{
  "run_id": "run_demo_candidate",
  "offset": 0,
  "limit": 50,
  "results": [
    {
      "case_id": "case_answer_citation",
      "target_id": "probe",
      "verdict": "PASS",
      "error_code": null,
      "latency_ms": 128
    },
    {
      "case_id": "case_clarify_empty",
      "target_id": "probe",
      "verdict": "PASS",
      "error_code": null,
      "latency_ms": 96
    }
  ]
}
```

## 6. Generate a Report

```bash
curl -s http://localhost:8099/api/v1/eval/runs/run_demo_candidate/report
```

Example summary:

```json
{
  "run_id": "run_demo_candidate",
  "report_version": "run.report.v1",
  "total_cases": 2,
  "finished_cases": 2,
  "pass_rate": 1.0,
  "skipped_rate": 0.0,
  "p95_latency_ms": 128,
  "error_code_counts": {}
}
```

## 7. Compare Base and Candidate

```bash
curl -s "http://localhost:8099/api/v1/eval/compare?base_run_id=run_demo_base&cand_run_id=run_demo_candidate"
```

Example summary:

```json
{
  "compare_version": "compare.v1",
  "base_run_id": "run_demo_base",
  "cand_run_id": "run_demo_candidate",
  "base_pass_rate": 0.5,
  "cand_pass_rate": 1.0,
  "pass_rate_delta": 0.5,
  "regressions": [],
  "improvements": [
    {
      "case_id": "case_answer_citation",
      "base_verdict": "FAIL",
      "cand_verdict": "PASS"
    }
  ]
}
```

## Notes

- Use `docs/examples/day2-sample.jsonl` or `docs/examples/day2-sample.csv` for small local imports.
- Use `scripts/day10-export-demo.sh` or `scripts/day10-export-demo.ps1` for local demo exports.
- Generated report/compare files should stay in `out/` or `local-artifacts/`, not in Git.
- Redis queue/quota support is implemented at a foundational level, but full pressure-test and acceptance evidence is still pending.
