# vagent-eval

P0 统一评测服务（独立部署）。Day1：可启动、读取 `eval.targets`、Actuator 与 `/internal/eval/status`。

## 运行

需要 **JDK 21** 与 **Maven 3.9+**（或仓库根目录的 **Maven Wrapper**：`mvnw.cmd` / `mvnw`）。

```bash
mvn spring-boot:run
```

Windows 若无全局 `mvn`：先设置 `JAVA_HOME` 指向 JDK 21+，再执行 `.\mvnw.cmd spring-boot:run` 或 `.\mvnw.cmd test`。

- 健康检查：`GET http://localhost:8099/actuator/health`
- 进程与 target 配置摘要：`GET http://localhost:8099/internal/eval/status`（不含 token）

配置见 `src/main/resources/application.yml`；生产示例见 `application-example.yml`（勿提交真实密钥）。

## 阶段四：按 target 的调度队列（FIFO + 回压）

- **语义**：每个 `target_id` 一条 lane（`ArrayBlockingQueue` + worker）；`POST /api/v1/eval/runs` 创建 run 后入队异步执行，不再为每个 run `new Thread`。
- **配置**（`eval.runner`）：
  - `target-concurrency`：该 target 的 worker 数（默认 `1`，同 target 内顺序更稳定）
  - `target-queue-capacity`：待调度 run 队列长度
  - `enqueue-timeout-ms`：入队等待；队列满或超时则该 run 直接 `CANCELLED`，`cancel_reason` 形如 `schedule_rejected:QUEUE_FULL` / `schedule_rejected:ENQUEUE_TIMEOUT`
- **可观测**：指标 `eval.runner.enqueue.rejected{target_id,reason}`；审计事件 `RUN_SCHEDULE_REJECT`（`actor=system`）
- **单测**：`mvn test` 前需本机或 CI 上可连 PostgreSQL 库 `eval`（见 `src/test/resources/application.yml`；CI 已在 `.github/workflows/ci.yml` 中启动 `postgres:15` 服务）。在 JDK 25 等环境下 Mockito 默认 inline 可能受限，本仓库在 `src/test/resources/mockito-extensions/` 指定了 subclass mock maker。

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
