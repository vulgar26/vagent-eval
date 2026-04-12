# Day10：已知限制与 P0 未覆盖 / 风险

> **本文档是什么**：当前 P0 实现里**已知**的短板、未覆盖能力与运维风险，供组长做 **PASS Day10 / A 线收口** 时的对照表，并给 P1 排期引用。  
> **怎么用**：验收汇报时可直接摘表格中的条目说明「我们承认哪些边界」；**不要**把本文当作已修复承诺。  
> **与谁配套**：与 [`day10-guide.md`](day10-guide.md) 的演示脚本、[`evidence/`](evidence/README.md) 的脱敏证据一起交付。

本文档与当前代码行为一致，**不承诺已修复**。

## P0 未覆盖或薄弱点

| 优先级 | 主题 | 说明 |
|--------|------|------|
| **P0** | **内存存储** | `DatasetStore` / `RunStore` 进程内内存；重启丢数据，无多副本一致性。 |
| **P0** | **探针与生产** | `eval.probe.enabled` 仅用于本地/演示；生产误开等于内置假被测端面。 |
| **P0** | **`token-hash` 为空** | 管理 API 跳过 token 校验；需依赖 CIDR、网关或必须配置哈希。 |
| **P0** | **`/internal/eval` 无 Filter** | 状态接口不在 Day9 管理面路径下；依赖网络隔离。 |
| **P0** | **串行 + 固定间隔** | `RunRunner` 逐题串行，`INTER_CASE_SLEEP_MS` 硬编码，大批量时耗时长且不可配置。 |
| **P0** | **compare 语义边界** | 仅按 `case_id` 对齐；重复 `case_id` 后者覆盖前者；不校验 `target_id` 是否「可比」。 |
| **P0** | **单测端口** | `DEFINED_PORT` 集成测绑定 `8099`，与本地已占用进程冲突时会失败。 |
| **P0** | **上游真实性** | probe 只验证契约与规则链；不替代真实 LLM / RAG 线上行为。 |

## 已部分缓解（仍须运维配置）

- **HTTPS / CIDR / token**：由 `eval.api.*` 与网关策略收口。
- **敏感 debug**：`EvalDebugResponseBodyAdvice` 在未带 `X-Eval-Debug` 时拦截部分 debug 键。

## 建议的下一步（P1）

- Run/Result 持久化与导出任务队列。
- `eval.run.*` 可配置（并发度、超时、inter-case sleep）。
- 集成测试改为 **随机端口** + 覆盖写入 `probe` base-url，去掉对固定 8099 的依赖。
