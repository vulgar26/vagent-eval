# vagent-eval

P0 统一评测服务（独立部署）。Day1：可启动、读取 `eval.targets`、Actuator 与 `/internal/eval/status`。

## 运行

需要 **JDK 21** 与 **Maven 3.9+**：

```bash
mvn spring-boot:run
```

- 健康检查：`GET http://localhost:8099/actuator/health`
- 进程与 target 配置摘要：`GET http://localhost:8099/internal/eval/status`（不含 token）
- 指标（Prometheus scrape 入口，阶段二）：`GET http://localhost:8099/actuator/prometheus`

配置见 `src/main/resources/application.yml`；生产示例见 `application-example.yml`（勿提交真实密钥）。

## 阶段 1~3（已落地）概要

- **阶段一：持久化（PostgreSQL）**：`eval_run` / `eval_result` 落库，重启不丢；支持 `DELETE /runs/{id}` 与自动 retention 清理（可配置）。
- **阶段二：可观测（Micrometer）**：暴露 `/actuator/prometheus`，记录 run/case 计数与下游 HTTP 耗时；`RunRunner` 输出 key=value 结构化日志。
- **阶段三：审计与合规**：`eval_audit_event` 落库；管理面成功/失败/安全拒绝均可追溯；debug 写库与出站均做脱敏+截断，且采用白名单策略。

> 本地开发建议在 `application-local.yml` 配置 datasource/target/token 等；该文件已加入 `.gitignore`，请勿提交密码与明文 token。

## 数据保留期与删除（P1）

vagent-eval 的“评测证据”会落库（PostgreSQL 表 `eval_run` / `eval_result`），需要明确的删除策略：

- **手动删除**：`DELETE /api/v1/eval/runs/{runId}`（results 级联删除）
- **自动清理（retention）**：配置 `eval.retention.enabled/days/interval-ms` 后，定时删除 `FINISHED/CANCELLED` 且 `finished_at < now - days` 的 run

## Debug 脱敏与白名单（阶段三）

逐题结果会写入 `eval_result.debug_json`，并在 `GET /api/v1/eval/runs/{runId}/results` 返回 debug。
为避免敏感信息落库或出站，服务端会做两层清洗（见 `com.vagent.eval.security.DebugSanitizer`）：

- **敏感键永不保留明文**（如 `query`/`answer`/`token`）：替换为 `*_len` + `*_sha256`
- **白名单优先**：仅允许的 key（或允许前缀 `membership_`）才会保留，其它默认丢弃（并记录 `debug_dropped_keys_count`）
- **体量可控**：字符串/列表/key 数均有限制，避免 debug 过大拖慢 DB 与网络

## Day2 草案

Dataset 导入 API 与 JSONL/CSV 行格式见 [docs/day2-dataset-import-draft.md](docs/day2-dataset-import-draft.md)。

## Day10（A 线 P0 收口）

- **带教与验收**：[docs/day10-guide.md](docs/day10-guide.md)  
- **一键导出脚本**（需先启动服务并 `--eval.api.enabled=true`）：[scripts/day10-export-demo.ps1](scripts/day10-export-demo.ps1) / [scripts/day10-export-demo.sh](scripts/day10-export-demo.sh) → 产物在 `out/`（已忽略提交）  
- **脱敏示例 JSON**：[docs/evidence/](docs/evidence/README.md)；**已知限制**：[docs/day10-known-limitations.md](docs/day10-known-limitations.md)

## GitHub Actions（CI + 自动化产物）

- **单测 CI**：`.github/workflows/ci.yml`（push / PR 跑 `mvn test`）
- **探针 nightly / 手动**：`.github/workflows/eval-demo-artifacts.yml`（本服务 `probe` + `day10-export-demo.sh`，上传 report/compare）
- **真实 target smoke（手动）**：`.github/workflows/eval-full-targets.yml` — 需先在仓库配置 **Variables + Secret**，步骤见 [docs/github-actions-secrets.md](docs/github-actions-secrets.md)

## P0+ S3（周报：按 tags 分桶子报表）

- **HTTP**：`GET /api/v1/eval/runs/{run_id}/report/buckets`  
  - 可重复传 `tag_prefix`（例如 `tag_prefix=attack/&tag_prefix=rag/empty`）；不传则默认三桶：`attack/`、`rag/empty`、`rag/low_conf`  
  - 可选：`error_code_top_n`（同 `/report`）  
- **导出脚本**：[scripts/p0plus-bucket-export.ps1](scripts/p0plus-bucket-export.ps1)（把 `report` + `report/buckets` 落到 `out/`）
