# Day10：稳定性与演示（A 线 P0 收口）

> **本文档是什么**：Day10 带教与验收说明（预习、一键导出步骤、PASS 清单、二次考察）。  
> **相关文件**：一键脚本 [`../scripts/day10-export-demo.ps1`](../scripts/day10-export-demo.ps1) / [`../scripts/day10-export-demo.sh`](../scripts/day10-export-demo.sh)；脱敏样例 [`evidence/`](evidence/README.md)；风险清单 [`day10-known-limitations.md`](day10-known-limitations.md)；CI 回归 [`../src/test/java/com/vagent/eval/run/Day10DemoIntegrationTest.java`](../src/test/java/com/vagent/eval/run/Day10DemoIntegrationTest.java)。

**TODAY_TOKEN**：`2026-04-10-A-D10`（按当日替换 `<DATE>`）

## 今日目标（组长）

- **稳定性 + 可演示**：本地一键完成「建集 → 导入 → 跑两轮（probe）→ 导出 `report` + `compare`」。
- **证据**：各 1 份脱敏样例见 [`evidence/`](evidence/README.md)；你本地跑脚本产物进 `out/`（已 `.gitignore`）。
- **门控**：`PASS Day10` 即 **A 线 P0 结束**；需附「已知限制 / P0 未覆盖」清单 → [`day10-known-limitations.md`](day10-known-limitations.md)。

## 预习（先讲清再考）

1. **`GET .../runs/{id}/report`**：聚合单次 run，版本字段 `report_version: run.report.v1`；分母口径见 `rate_denominator`（`total_cases`）。
2. **`GET /api/v1/eval/compare`**：离线差分，**不重新执行**；`base_run_id` 与 `cand_run_id` 须存在且 **`dataset_id` 相同**。
3. **一键依赖**：`eval.probe.enabled=true`（探针模拟被测）、`eval.api.enabled=true`（管理 API 不被 Day9 Filter 404）、`probe` target 的 `base-url` 指向本服务（默认 `application.yml` 已示例）。

## 一键导出

1. 启动（**务必打开管理 API**，与仓库根 `application.yml` 默认 `eval.api.enabled: false` 区分）：

   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--eval.api.enabled=true
   ```

2. **PowerShell**（Windows）：

   ```powershell
   cd D:\Projects\vagent-eval
   powershell -ExecutionPolicy Bypass -File .\scripts\day10-export-demo.ps1
   ```

3. **Bash**（需 `curl`、`jq`）：

   ```bash
   cd "$(dirname "$0")/.."
   bash scripts/day10-export-demo.sh
   ```

产物：`out/day10-report.json`、`out/day10-compare.json`。

## 验收清单（PASS Day10）

- [ ] `mvn test` 全绿（含 `Day10DemoIntegrationTest`，需 **8099 端口空闲**）。
- [ ] 脚本或手搓 curl：两轮 run 均为 `FINISHED`，`report` 与 `compare` JSON 可保存。
- [ ] 能口头说明：compare 的 `pass_rate_delta` 是谁减谁；`regressions` 与 `missing_in_cand` 差在哪。
- [ ] 已读 [`day10-known-limitations.md`](day10-known-limitations.md)，能指出至少 2 条 P0 风险。

## 二次考察（简答）

1. 为何演示脚本要求 **`--eval.api.enabled=true`**？  
2. `compare` 为何要求两 run **同 dataset**？  
3. `out/` 为何不提交仓库？

## 下一步（P1 方向，不写代码也行）

持久化 run 结果、生产关闭 probe、CI 中随机端口 + 动态 `probe` base-url、并发跑题、`eval.run.inter_case_sleep_ms` 可配置等 —— 详见限制清单。
