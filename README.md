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

**travel-ai 评测网关**：若被测开启 **`APP_EVAL_GATEWAY_KEY`**，本进程须设置环境变量 **`EVAL_TRAVEL_AI_GATEWAY_KEY`**（与之一致），或在 `application-local.yml` 中为 `travel-ai` target 填写 **`eval-gateway-key`**。否则 `TargetClient` 不发送 **`X-Eval-Gateway-Key`**，travel-ai 会返回 **401**。CI 见 [docs/github-actions-secrets.md](docs/github-actions-secrets.md)；总纲见 Vagent 仓库 **`plans/eval-upgrade.md`**「认证方式 / 本机复现清单」。

## P0 联调状态（2026-04-18，摘要）

- **已验证（本机 + 双 target）**：对 `plans/datasets/p0-dataset-v0.jsonl`（**32 case**，源文件在 **Vagent** 仓库 `D:\Projects\Vagent\plans\datasets\`）导入后，分别对 **`vagent`**、**`travel-ai`** 跑满 `FINISHED`，并生成 **`GET .../runs/{id}/report`**（`run.report.v1`）与 **`GET .../compare`**（`compare.v1`）。示例 `run_id` 与登记见 **`D:\Projects\Vagent\plans\regression-baseline-convention.md`** §4 / §4.1；总纲与缺口见 **`D:\Projects\Vagent\plans\eval-upgrade.md`**「vagent-eval 与双 target 联调状态」及该文档 **P0 退出标准** 下的验收快照。
- **已实现（2026-04-18）**：`GET .../runs/{id}/report` 在 **本进程仍持有 dataset cases** 时附带 **`slices_version`**、**`by_expected_behavior`**、**`by_requires_citations`**（分母为该维度题数，与全卷 `pass_rate` 口径一致；`compare` 内嵌报告仍不含切片以免缺 dataset）。
- **已实现（dataset 持久化）**：Flyway **`V4__eval_dataset_and_case.sql`** 建 `eval_dataset` / `eval_case`；运行时 **`JdbcDatasetStore`**（`DatasetStore` 接口）写入 PostgreSQL，**重启后同一 `dataset_id` 仍可 `GET .../datasets/{id}`、`list cases`、跑 run、`report` 切片**。`DELETE .../datasets/{id}` 会先删引用该库的 **`eval_run`**（级联删 `eval_result`），再删库与题。
- **待补证据**：业务侧 **E4 限流** 与 eval 侧 **Redis 配额** 的完整 checklist 仍按 **`eval-upgrade.md`** P1/P0+ 验收。

## 被测方集成（meta 落库 / compare / travel-ai 解耦）

- **vagent / travel-ai 共用一份说明**：[docs/target-integration-meta-and-compare.md](docs/target-integration-meta-and-compare.md)（`meta` 落库、`meta-trace-keys`、探针行为与安全边界）

## 阶段四：按 target 的调度队列（FIFO + 回压）

- **语义**：每个 `target_id` 一条 lane（`ArrayBlockingQueue` + worker）；`POST /api/v1/eval/runs` 创建 run 后入队异步执行，不再为每个 run `new Thread`。
- **配置**（`eval.runner`）：
  - `target-concurrency`：该 target 的 worker 数（默认 `1`，同 target 内顺序更稳定）
  - `target-queue-capacity`：待调度 run 队列长度
  - `enqueue-timeout-ms`：入队等待；队列满或超时则该 run 直接 `CANCELLED`，`cancel_reason` 形如 `schedule_rejected:QUEUE_FULL` / `schedule_rejected:ENQUEUE_TIMEOUT`
- **可观测**：指标 `eval.runner.enqueue.rejected{target_id,reason}`；审计事件 `RUN_SCHEDULE_REJECT`（`actor=system`）
- **单测**：`mvn test` 前需本机或 CI 上可连 PostgreSQL 库 `eval`（见 `src/test/resources/application.yml`；CI 已在 `.github/workflows/ci.yml` 中启动 `postgres:15` 服务）。在 JDK 25 等环境下 Mockito 默认 inline 可能受限，本仓库在 `src/test/resources/mockito-extensions/` 指定了 subclass mock maker。

## 阶段五 5.1：Redis 接入（连通性）

- **连接**：使用 Spring Boot 标准 `spring.data.redis.*`（或 `SPRING_DATA_REDIS_*` 环境变量），由 `spring-boot-starter-data-redis` + Lettuce 自动装配 `RedisConnectionFactory`。
- **业务配置**（`eval.scheduler.redis`，与连接分离）：
  - `enabled`：为 `true` 时，启动阶段对 Redis 执行一次 `PING` 校验；默认 `false`。
  - `key-prefix`：调度相关 Redis key 的统一前缀（建议以 `:` 结尾）；默认 `vagent:eval:`。
  - `on-connect-failure`：`lenient`（仅告警）或 `strict`（启动失败）。
- **状态**：`GET /internal/eval/status` 会返回 `eval_scheduler_redis_*` 摘要字段（不含密钥）。

## 阶段五 5.2：Redis 跨实例 run 队列

- **何时启用**：`eval.scheduler.redis.enabled=true` **且** Spring 已装配 `StringRedisTemplate`（通常需同时配置 `spring.data.redis.*`）。此时 `POST /api/v1/eval/runs` 将 run 写入 Redis 列表（每 target 一条队列：`{keyPrefix}schedule:queue:{target_id}`），由本机及**其他实例**上的 worker 阻塞消费并执行 `RunRunner`。
- **何时回退内存**：`enabled=false`，或 `enabled=true` 但无 `StringRedisTemplate`（会打 WARN 并回退到阶段四内存队列）。
- **与 `eval.runner` 对齐**：`target-queue-capacity`（列表深度上限）、`target-concurrency`（每实例每 target 的 BLPOP worker 数）、`enqueue-timeout-ms`（队列满时等待入队）。
- **多实例安全**：`RunStore.tryMarkStarted` 仅在 `PENDING` 时原子改为 `RUNNING`，避免同一 `run_id` 被重复执行。
- **消费阻塞**：`eval.scheduler.redis.br-pop-timeout-seconds`（默认 `5`），超时后循环便于优雅停机。

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
