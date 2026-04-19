# E4（下游限流）与 eval 侧 Redis 配额 — 验收清单

与 README「待补证据」及 `plans/eval-upgrade.md` 对齐：**业务网关 E4** 与 **vagent-eval 进程内保护** 分工不同，本页用于发版前逐项勾选。

---

## A. 下游 HTTP 429 → 评测结果（E4 在数据面上的落点）

- [ ] **契约**：被测对 `POST /api/v1/eval/chat` 返回 **429** 时，本服务将对应 case 记为 **`verdict=FAIL`**、**`error_code=RATE_LIMITED`**（见 `RunRunner` 对 HTTP 状态映射）。
- [ ] **复现**：对某一 target 人为制造 429（网关 / mock），跑一小题集，确认 `GET .../runs/{id}/results` 中可见 `RATE_LIMITED`，且 **`GET .../runs/{id}/report`** 的 `error_code_counts` 含该项。
- [ ] **与调度拒跑区分**：**队列满 / 入队超时** 导致的是 **run 级 `CANCELLED`**（`cancel_reason` 含 `schedule_rejected:*`），**不是**逐题 `RATE_LIMITED`。

---

## B. eval 侧：对下游的全局并发（`eval.runner.max-concurrency`）

- [ ] **配置可读**：`GET /internal/eval/status` 返回 **`eval_runner_max_concurrency`**、**`eval_runner_acquire_timeout_ms`**（许可等待超时后本题记 **`RATE_LIMITED`**）。
- [ ] **压测（可选）**：将 `max-concurrency` 设为 `1`，对同一 target 并发提交多个 run（或单 run 内多题极快），观察是否在超时窗口内出现 **`RATE_LIMITED`**（取决于被测响应时延与 `acquire-timeout-ms`）。

---

## C. 阶段四：每 target 内存队列（FIFO + 回压）

- [ ] **配置可读**：`GET /internal/eval/status` 含 **`eval_runner_target_concurrency`**、**`eval_runner_target_queue_capacity`**、**`eval_runner_enqueue_timeout_ms`**。
- [ ] **拒跑语义**：队列满或入队超时 → run **`CANCELLED`**；指标 **`eval.runner.enqueue.rejected`**（若启用 Prometheus）带 `target_id` / `reason`。

---

## D. Redis：跨实例全局并发上限（`eval.scheduler.redis`）

**前置**：`spring.data.redis.*` 可连；`eval.scheduler.redis.enabled=true`；**`eval.scheduler.redis.global-max-concurrent-runs-per-target` > 0**。

- [ ] **配置可读**：`GET /internal/eval/status` 含 **`eval_scheduler_global_max_concurrent_runs_per_target`**（0 表示关闭该上限）。
- [ ] **Key 形态**：活跃计数 key 为  
  `{eval.scheduler.redis.key-prefix}quota:run:active:{sanitized_target_id}`  
  （见 `RedisGlobalRunQuota#activeCountKey`；`sanitize` 规则见 `RedisRunQueueDispatcher`）。
- [ ] **手工验**：`global-max-concurrent-runs-per-target=1` 时，对同一 `target_id` **快速** 提交 2 个 run：第二个应在 **配额 acquire** 阶段失败或重试后拒跑（具体以 `TargetRunScheduler` / dispatcher 日志为准），且不应出现双 **RUNNING** 写同一 `run_id`。
- [ ] **Redis 故障**：`on-connect-failure=lenient` 时进程仍起；配额 acquire 失败时打 WARN，调用方拒跑或回退（见实现与日志 `redis_global_quota_acquire_error`）。

---

## E. 文档与链接

- [ ] 本仓库 **README**「待补证据」与 **`plans/eval-upgrade.md`** 中 P1/P0+ 条目与本页勾选结果一致（或注明例外与日期）。

---

*最后更新：随 vagent-eval 里程碑维护；完成项请改 `[ ]` 为 `[x]` 并附 PR / 运行记录。*
