# 被测方集成说明：meta 落库、导出与 compare（vagent / travel-ai）

本文面向 **vagent**、**travel-ai** 等被测服务方与联调同学，说明 **vagent-eval** 当前如何消费你们 `POST /api/v1/eval/chat`（或等价路径）的响应，以及你们在配置侧需要关注什么。**eval 不假设所有 target 共用同一套 `meta` 键**；按 `eval.targets[].target-id` 分别配置。

---

## 1. 事实（eval 侧行为）

| 项目 | 说明 |
|------|------|
| **持久化** | 每条 case 结果除原有 `debug_json`（判分摘要、白名单清洗）外，增加 **`eval_result.target_meta_json`**：保存上游响应根级 **`meta` 对象** 的快照（JSONB）。**不**默认保存整段 `EvalChatResponse` 正文（如长 `answer` / 全量 `sources`），以控制体积；检索类归因以 `meta`（及你们已在根级返回的字段）为准。 |
| **API / 导出** | `GET /api/v1/eval/runs/{run_id}/results` 返回的每条结果含 **`meta`**（与 `debug` 并列）。序列化字段名为 **snake_case**（与 Spring/Jackson 全局策略一致）。 |
| **语义** | **`debug`**：eval 判分与契约摘要（经 `DebugSanitizer`）。**`meta`**：来自被测响应的观测快照，**不做** debug 那套白名单裁剪；仅做 **安全与体积** 策略（见下）。 |
| **明文 `retrieval_hit_ids`** | 落库前默认 **删除** `meta.retrieval_hit_ids`；仅当 `eval.persistence.allow-plain-retrieval-hit-ids-in-meta=true` **且** `eval.runner.chat-mode=EVAL_DEBUG` 时可能保留（内网联调）。出站：未带 `X-Eval-Debug` 的管理 API 响应中仍会去掉该键。请与贵方 **EVAL_DEBUG + allow-cidrs** 等产品策略对齐。 |
| **超大 `meta`** | `eval.persistence.max-target-meta-json-chars`（默认约 256Ki 字符的 JSON 串长）超限则 **整段 `meta` 不落库**（该条 `meta` 为空）。 |

---

## 2. 对 vagent 方的约定（与既有 SSOT 对齐）

- 观测 **SSOT** 仍在贵方 **`EvalChatResponse.meta`**（及根级 `retrieval_hits` 等）中；eval **原样（在安全策略内）落库 `meta`**，不要求再把同一套字段抄进 eval 的 `debug`。
- 若某 PASS case 有检索命中且贵方 `meta` 含 `retrieve_hit_count > 0`，eval 导出/DB 中应能出现贵方写入的距离类字段（若贵方在 0 命中时不写距离字段，eval 侧视为预期）。
- **compare 摘要键**：在 `application.yml` 里为 `target-id: vagent` 配置 **`eval.targets[].meta-trace-keys`**（snake_case 列表）。compare 的 `base_meta_trace` / `cand_meta_trace` **仅拷贝列表中出现的键**，便于跨 run 对比；未配置的键不会出现在 compare 摘要里（全量仍以 `GET .../results` 的 `meta` 为准）。

---

## 3. 对 travel-ai 方的约定（与 vagent 解耦）

- **不要求** `meta` 中出现与 vagent 相同的 `hybrid_lexical_mode`、`retrieve_top1_distance` 等键；以贵方实际返回的 **`meta` JSON** 为准，eval **按字节落库**（在大小与 `retrieval_hit_ids` 策略内）。
- **compare**：为贵方 target 配置 **`meta-trace-keys`** 为贵方希望出现在 `*_meta_trace` 里的键名即可；若留空或不配置，compare 中对应 trace 为 **空对象** `{}`，避免套用 vagent 字段名。
- **本地探针**（`eval.probe.enabled=true` 时本进程模拟 `/api/v1/eval/chat`）：仅当某 target 配置了 `meta-trace-keys` 时，探针才可能为 **CITATIONS_OK** 类用例注入少量**联调用固定样本**；样本仅覆盖 eval 内置的少数键（见 `ProbeMetaAugmentor`）。**未在列表中声明的键不会由探针伪造**；贵方专有键请走真实服务或自行扩展探针逻辑。

---

## 4. 配置示例（运维 / 平台）

**vagent**（与当前仓库默认思路一致）：

```yaml
eval:
  targets:
    - target-id: vagent
      base-url: https://your-vagent-host
      enabled: true
      meta-trace-keys:
        - hybrid_lexical_mode
        - hybrid_lexical_outcome
        - retrieve_hit_count
        - retrieve_top1_distance
        - retrieve_top1_distance_bucket
```

**travel-ai**（不在 compare 摘要中强制 vagent 键；可按贵方 `meta` 逐步加键）：

```yaml
eval:
  targets:
    - target-id: travel-ai
      base-url: https://your-travel-host
      enabled: true
      meta-trace-keys: []   # 或列出贵方用于回归对比的键，例如:
      # meta-trace-keys:
      #   - your_retrieval_signal_foo
      #   - your_latency_breakdown_bar
```

---

## 5. 相关 HTTP / 数据路径（便于对方文档交叉引用）

- 创建 run、拉结果、compare：`RunApi` / `CompareApi`（路径以仓库内 `**/RunApi.java`、`CompareApi.java` 为准）。
- **compare** 在 `regressions` / `improvements` 行内：`base_meta_trace`、`cand_meta_trace`（按**该行结果**的 `target_id` 解析 `meta-trace-keys`）；`missing_in_*` 行内：`present_meta_trace`。
- 迁移：`src/main/resources/db/migration/V3__eval_result_target_meta.sql`。

---

## 6. 变更沟通

若贵方 **`meta` 键集合或语义**有破坏性变更，建议同步 eval 运维：**更新对应 target 的 `meta-trace-keys`**（及依赖导出字段的脚本/看板）。eval 侧不解析业务含义，只负责存储与按配置摘要。

---

## 7. 与组织 SSOT `eval-upgrade.md` 的对应关系

- **文档位置（业务仓库）**：在 **Vagent** 仓库内为 `plans/eval-upgrade.md`（与 `p0-execution-map.md` 等并列）。评测产品/契约的**主 SSOT**以该文件为准；本文仅描述 **vagent-eval 仓库**内的落地行为，避免双写漂移。
- **Harness 两层**：`eval-upgrade.md` 将 **Evaluation Harness**（本服务：dataset、run、判定、compare、报表）与 **Execution Harness**（各 target 内编排与可观测）拆开；本文中的 **`meta` 落库**对应「被测方通过评测接口把执行侧观测交给 Evaluation Harness」的那一段。
- **列名 / 字段名与 SSOT 词面对齐**：
  - SSOT 数据模型小节中可能出现的 **`eval_result.meta_json`** 泛指「上游 `meta` 快照」这一语义。
  - 本仓库实现为 **`eval_result.target_meta_json`**（仅存响应根级 **`meta` 对象**）+ 既有 **`eval_result.debug_json`**（判分摘要，白名单清洗）。**未**在结果表默认落整段 `answer` / 全量 `sources`（与 SSOT「最小化采集、体积可控」方向一致；若将来与 SSOT 完全字面对齐，可在迁移中重命名列或增加视图，不改变 API 中 `meta`/`debug` 对外形状）。
- **RAG 观测与 compare**：`eval-upgrade.md`「P0 推荐的 RAG 检索观测字段」与 Vagent 已提供字段对齐；本仓库通过 **`meta-trace-keys`** 选择进入 `*_meta_trace` 的子集，**travel-ai** 可配置自有键或留空，无需与 Vagent 同构。
