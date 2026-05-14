# Target Integration: meta, result, compare

本文说明被测服务如何接入 `vagent-eval`。目标是让 target 的 `POST /api/v1/eval/chat` 响应可以被评测服务稳定消费、落库，并进入 report / compare。

`vagent-eval` 不要求所有 target 使用同一套 `meta` 字段。每个 target 可通过 `eval.targets[].meta-trace-keys` 配置需要进入 compare 摘要的字段。

## Eval 侧行为

| 项目 | 说明 |
| --- | --- |
| Target 调用 | run 执行时，评测服务调用 `{target.base-url}/api/v1/eval/chat`。 |
| 结果落库 | 每条 case result 会保存 verdict、error_code、latency、debug 摘要和 target 返回的根级 `meta` 快照。 |
| `meta` 处理 | `meta` 主要用于回归观察和 compare 摘要，不作为完整业务响应存档。 |
| 敏感字段 | 默认不保留明文 `meta.retrieval_hit_ids`；只有明确打开 debug 相关配置时才用于内网联调。 |
| 体积限制 | 超过 `eval.persistence.max-target-meta-json-chars` 的 `meta` 不落库，避免单条结果过大。 |

## Target 响应要求

target 应返回稳定 JSON，至少满足 eval/chat 契约，并根据能力返回对应字段：

- `behavior`：实际行为，例如 `answer`、`clarify`、`deny`、`tool`。
- `capabilities`：声明 retrieval / tools 等能力是否支持。
- `sources`：当 answer 需要引用时返回引用来源。
- `meta`：放置检索、低置信、工具治理等可观测字段。
- `meta.low_confidence` / `meta.low_confidence_reasons`：当低置信为 true 时，必须提供非空原因数组。
- `meta.retrieval_hit_id_hashes`：用于 citation membership 检查，推荐返回 hash 后的候选证据。

## meta-trace-keys

`meta-trace-keys` 控制 compare 输出中 `base_meta_trace`、`cand_meta_trace` 等摘要字段。未配置的 key 不会进入 compare 摘要，但原始 result 中仍可查看落库后的 `meta`。

示例：

```yaml
eval:
  targets:
    - target-id: vagent
      base-url: https://your-vagent-host
      enabled: true
      meta-trace-keys:
        - retrieve_hit_count
        - low_confidence
        - retrieve_top1_distance
    - target-id: travel-ai
      base-url: https://your-travel-host
      enabled: true
      meta-trace-keys:
        - retrieve_hit_count
        - low_confidence
        - tool_calls_count
```

## Headers

评测服务调用 target 时会携带 eval 相关 header。常见字段包括：

| Header | 说明 |
| --- | --- |
| `X-Eval-Run-Id` | 当前 run id。 |
| `X-Eval-Dataset-Id` | 当前 dataset id。 |
| `X-Eval-Case-Id` | 当前 case id。 |
| `X-Eval-Target-Id` | 当前 target id。 |
| `X-Eval-Token` | 可选，用于 target 侧鉴权或 membership hash 派生。 |
| `X-Eval-Gateway-Key` | 可选，用于需要额外网关 key 的 target。 |
| `X-Eval-Membership-Top-N` | citation membership 使用的候选截断上限。 |

真实 token、gateway key、salt 不应提交到仓库；请通过环境变量、部署配置或 `application-local.yml` 注入。

## 相关 API

- 创建 run：`POST /api/v1/eval/runs`
- 查看 result：`GET /api/v1/eval/runs/{run_id}/results`
- 生成 report：`GET /api/v1/eval/runs/{run_id}/report`
- 对比 run：`GET /api/v1/eval/compare?base_run_id=...&cand_run_id=...`
- 查看运行状态摘要：`GET /internal/eval/status`

## 变更建议

如果 target 的 `meta` 字段集合或字段语义发生变化，建议同步更新对应 target 的 `meta-trace-keys`，并补充一条脱敏 compare evidence，避免回归报告中的字段含义漂移。
