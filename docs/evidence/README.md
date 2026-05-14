# Evidence Samples

This directory contains sanitized evidence for the evaluation harness. These files are safe to keep in Git and are intended to show the shape of reports and comparisons without exposing local run IDs, private targets, prompts, tokens, or gateway keys.

| File | Purpose |
| --- | --- |
| [`eval-run-demo.md`](eval-run-demo.md) | End-to-end walkthrough: dataset import, run creation, target call, result storage, report, and compare. |
| [`day10-report.sample.json`](day10-report.sample.json) | Sanitized example of `GET /api/v1/eval/runs/{run_id}/report`. |
| [`day10-compare.sample.json`](day10-compare.sample.json) | Sanitized example of `GET /api/v1/eval/compare?base_run_id=...&cand_run_id=...`. |

Generated local exports belong in `out/` or `local-artifacts/`; both are ignored by Git.
