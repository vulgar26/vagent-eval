# vagent-eval 必做清单（工程与平台缺口）

本文档把当前仍欠的交付项集中成可勾选清单，便于发版前自检；与「一页纸」验收口径对齐。

---

## 1. 分支与提交卫生（别人 / CI 能复现）

- [x] **整理未提交改动**：dataset JDBC、`V4__eval_dataset_and_case.sql`、Redis 调度相关、安全/探针等，按主题拆成少量 commit（例如：迁移 + store；报告切片；Redis；安全与测试），避免单巨型 diff。  
  *本次：拆为 `feat(report): run.report slices v2` 与 `feat: eval platform (dataset, redis, security, ci, docs)` 两个 commit。*
- [x] **与远端对齐**：`main` 已 `git push origin main`（本环境已成功推送至 `origin`）。
- [x] **确认 CI 配置**：workflow 使用仓库根路径、`mvn`、`scripts/*.sh`；`eval-demo-artifacts` 为 probe + admin API，无外部 target 依赖。

---

## 2. 仓库洁净度（误提交防护）

- [x] **已处理 Office 垃圾文件**：`~$*.docx` 已删；`.gitignore` 已含 `~$*`、`~WRL*.tmp`。若 `~WRL*.tmp` 仍提示占用，退出 Word 后手动删除即可。
- [x] **业务文档**：根目录 `命令行.docx` 已加入 `.gitignore`，避免 `git add .` 误加；若需长期保留请移到仓库外。
- [x] **`target/`**：保持忽略；勿将编译产物提交。
- [x] **`.idea/workspace.xml`**：`.gitignore` 已忽略整个 `.idea/`；勿单独跟踪 `workspace.xml`。

---

## 3. `run.report` 维度切片「做细」（平台缺口）

**基线（`run.report.slices.v1`）**：按 `expected_behavior` / `requires_citations` 分桶，分母为该维度题数。

**`run.report.slices.v2`（已实现）**：

- [x] **每桶时延**：各切片 `latency_sample_count`、`p95_method`、`p95_latency_ms`（算法与全卷一致）。
- [x] **每桶错误码**：各切片 `error_code_top_n`（与请求参数一致）、`error_code_counts`。
- [x] **`markdown_summary`**：追加 `## slices expected_behavior` / `## slices requires_citations` ASCII 行。
- [ ] **（可选）交叉维度**：`expected_behavior` × `requires_citations` 矩阵 — 未做，避免报表膨胀；需要时再开版本。

实现说明：`RunCompareService` 等仍调用 `computeReport(..., null)` 时不带切片；客户端以 `slices_version` 区分字段集。

---

## 4. 排障约定（已具备能力）

- 数据源不一致时：查看 `GET /internal/eval/status` 中的 `jdbc_url_masked`、`jdbc_username`，与 DBeaver 连接对齐；GUI 查看表数据后记得 **刷新**。

---

*文档随里程碑更新；完成项请勾选并可在 PR 描述中引用本文件路径。*
