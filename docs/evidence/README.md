# Day10 脱敏证据样例（`docs/evidence/`）

本目录放**可提交进仓库**的示例 JSON，用于给组长/文档看「长什么样」，**不含**你本机真实 `run_id`。

## 各文件是什么、怎么用

| 文件 | 是什么 | 怎么用 |
|------|--------|--------|
| [`day10-report.sample.json`](day10-report.sample.json) | 单次 `GET /api/v1/eval/runs/{run_id}/report` 响应的**形态示例**（`run_id`、时延等为占位/脱敏） | 写周报、贴 Confluence、对照字段含义；**不要**当真实评测结果引用 |
| [`day10-compare.sample.json`](day10-compare.sample.json) | 单次 `GET /api/v1/eval/compare?base_run_id=...&cand_run_id=...` 的**形态示例** | 同上；说明 compare 有哪些块（`regressions`、`missing_in_cand` 等） |

## 和你本机 `out/` 的区别

- **本目录 `*.sample.json`**：手写/固定的脱敏样例，**可 git 提交**。  
- **仓库根 `out/day10-*.json`**：运行 [`../scripts/day10-export-demo.ps1`](../scripts/day10-export-demo.ps1)（或 `.sh`）后生成，含真实 id，**已在 `.gitignore`，勿提交**。

## 怎么生成「真数据」文件

见仓库根 先 `mvn spring-boot:run` 并带上 `--eval.api.enabled=true`，再执行脚本，产物在 `out/`。
