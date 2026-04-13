# P0+ S1-D1 — Eval（A）交付物

> **角色**：Eval 负责人（A）  
> **冲刺**：S1 — 归因与契约  
> **工作日**：S1-D1（`plans/p0-plus-daily-and-acceptance-tables.md` §1.1）  
> **配套强制手册**：`D:\Projects\Vagent\plans\p0-plus-execution.md`（§16.1、§16.3、§17 项 7～8）

---

## 1. `RunEvaluator.evaluate` — 仍返回 `ErrorCode.UNKNOWN` 的分支清单

**文件**：`src/main/java/com/vagent/eval/run/RunEvaluator.java`  
**方法**：`evaluate(EvalCase c, JsonNode respJson, String targetId)`

**结论**：在 `evaluate` 主流程中，**仅 2 处**在已明确归因的情况下仍使用 `UNKNOWN`（与 `p0-plus-execution.md` §16.1 一致）。

| # | 大致行号 | 触发条件 | `debug.verdict_reason` | P0+ 计划动作（S1-D2，非本日代码） |
|---|----------|----------|------------------------|-----------------------------------|
| 1 | 107–109 | 契约已通过，但 `behavior` 与 `expected_behavior` 文本不一致（大小写不敏感比较下仍不等） | `behavior_mismatch` | 改为专码（如 `BEHAVIOR_MISMATCH`），见 §17 项 1～2 |
| 2 | 140–142 | `expected_behavior` 为 `tool`，且 `tool` 块存在，但 **非** `(required && used && succeeded)` 全真 | `tool_not_satisfied` | 改为专码（如 `TOOL_EXPECTATION_NOT_MET`），见 §17 项 1～2 |

**说明**：

- **`verifyCitationMembership`** 仅返回 `CONTRACT_VIOLATION`、`SOURCE_NOT_IN_HITS` 或 `null`（通过），**不使用** `UNKNOWN`。
- **`respJson == null`** → `PARSE_ERROR`（76–77 行）。
- **契约失败** → `EvalChatContractValidator` 给出的码（多为 `CONTRACT_VIOLATION`）（80–83 行）。
- **期望 tool 但 `tools.supported=false`** → `SKIPPED_UNSUPPORTED`（102–105 行）。
- **citations 路径**：缺 `sources`、缺/坏 `retrieval_hits`、source 结构问题等 → 多为 `CONTRACT_VIOLATION` 或 `SOURCE_NOT_IN_HITS`，非 `UNKNOWN`。

---

## 2. `RunRunner.runOneCase` — 与 `UNKNOWN` 的关系（S1-D1 备注）

**文件**：`src/main/java/com/vagent/eval/run/RunRunner.java`  
**行**：约 154–164

- `try` 内各分支均 **提前 `return`**，正常不会落到方法末尾。
- `catch` 分支将 `errorCode` 设为 `TIMEOUT` 或 `UPSTREAM_UNAVAILABLE`，**非 null**。
- 末尾表达式 `errorCode == null ? … : ErrorCode.UNKNOWN` 在 **当前控制流** 下对业务路径 **等价于防御性兜底**；若未来在 `try` 内增加未赋值 `errorCode` 且未 `return` 的分支，兜底会重新生效（`p0-plus-execution.md` §16.1、§23.3）。

**S1-D1 排障优先级**：报表中 **`UNKNOWN` 高企时优先查 `RunEvaluator` 上表两处**，而非假设 `RunRunner` 末尾兜底为主因。

---

## 3. `TargetClient` — `X-Eval-Token` 改造点与配置键名草案

**文件**：`src/main/java/com/vagent/eval/run/TargetClient.java`

### 3.1 现状

| 位置 | 行为 |
|------|------|
| 约 77–78 行 | 注释写明 token 未接入；`String token = ""` **写死空串** |
| 约 96 行 | `HttpRequest` 始终设置 `.header("X-Eval-Token", token)`，即 **常为空** |

**影响**（与 `p0-plus-execution.md` §16.3 **路径 B** 一致）：被测端（如 Vagent）若约定「`X-Eval-Token` 为空则不填充 `meta.retrieval_hit_id_hashes`」，则评测侧无法依赖该辅助观测字段；**注意**：Day6 **citation membership（路径 A）** 依赖的是响应根上 **`retrieval_hits[].id`** + `eval.membership.salt` / `top_n`，**不依赖**该 token。

### 3.2 建议改造点（实施留待 S1-D3 / §17 项 7～8）

1. **`EvalProperties.TargetConfig`**（或等价配置模型）  
   - 增加可选字段，例如：**`evalToken`**（Java 驼峰）  
   - YAML 示例（kebab-case）：`eval.targets[].eval-token: "..."`  
   - **明文 token 禁止提交 Git**；使用环境变量、`application-local.yml`（已 gitignore）或部署密钥注入。

2. **全局回退（可选）**  
   - 若需「所有 target 共用同一 token」：`eval.default-eval-token` → 绑定到 `EvalProperties.Api` 下新字段或 `EvalProperties` 根级 **`defaultEvalToken`**（与 `p0-plus-execution.md` §17「`eval.targetToken`」表述二选一，**建议优先 per-target**）。

3. **`TargetClient.postEvalChat`**  
   - 删除 `token = ""` 硬编码；按 **`targetId`** 解析当前 target 配置（可与 `findTarget` 对齐），取 **`evalToken`**，缺省则为 `""`。  
   - 保持现有 **`X-Eval-Membership-Salt`** / **`X-Eval-Membership-Top-N`** 逻辑不变。

4. **`RunRunner`**  
   - 若 token 改由 `TargetClient` 内部按 `targetId` 查配置，**可不改** `postEvalChat` 签名；若改为显式传参，则需同步调用处（约 112 行）。

5. **验证**  
   - 单测或集成测：mock server **断言** 请求头 **`X-Eval-Token`** 在配置非空时已发出（§17 项 8）。

### 3.3 配置键名草案（组长可拍板）

| 优先级 | YAML 键（示例） | Java 属性 | 说明 |
|--------|-----------------|-----------|------|
| 推荐 | `eval.targets[i].eval-token` | `TargetConfig.evalToken` | 与 `target-id` / `base-url` 同级，per-target |
| 可选 | `eval.default-eval-token` | `EvalProperties` 新增字段 | 未配置 per-target 时使用 |

---

## 4. 本日不做事项（遵守 S1-D1 边界）

- 未修改 `RunModel.ErrorCode`、未改 `RunEvaluator` 判定逻辑（留待 **S1-D2**）。  
- 未接 `TargetClient` token 实现（留待 **S1-D3** 与 §17 项 7～8）。

---

**文档版本**：与仓库当前 `RunEvaluator` / `TargetClient` / `RunRunner` 快照一致，供组长签收 S1-D1。

---

## 附录：S1-D2 代码已落地（Eval）

- `RunModel.ErrorCode` 已增加 `BEHAVIOR_MISMATCH`、`TOOL_EXPECTATION_NOT_MET`。  
- `RunEvaluator.evaluate` 中 `behavior_mismatch` / `tool_not_satisfied` 两分支已改挂上述专码（不再使用 `UNKNOWN`）。  
- 单测：`RunEvaluatorTest#behaviorMismatch_mapsToBehaviorMismatch_notUnknown`、`RunEvaluatorTest#toolExpected_toolBlockIncomplete_mapsToToolExpectationNotMet`。

## 附录：S1-D3（P0+ §16.7 + X-Eval-Token）已落地（Eval）

- **`EVAL_RULE_VERSION`** 递增至 **`p0.v3`**：`requires_citations=true` 且期望/实际均为 **`deny`**、且检索 **0 命中**（`retrieval_hits` 缺省/空数组，或 `meta.retrieve_hit_count` / `meta.retrieval_hit_count` 为 0）时，允许 **`sources` 为空**，`verdict_reason`=`citations_exempt_expected_deny_no_retrieval_hits`；**非空 `retrieval_hits` 仍要求非空 `sources`**（否则仍为 `missing_sources`）。  
- **配置**：`eval.default-eval-token`、`eval.targets[].eval-token` → `TargetClient` 填充 **`X-Eval-Token`**（与 membership 头独立）。  
- 单测：`RunEvaluatorTest` 中 §16.7 场景 3 条；`TargetClientEvalTokenTest`。
