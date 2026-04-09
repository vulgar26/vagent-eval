# Day2 草案：dataset 导入接口与 JSONL 行格式

与 `eval-upgrade.md` / `p0-execution-map.md` 对齐：**对外 JSON 一律 snake_case**；下列为 eval 服务侧 API 与导入行 schema 草案。

## HTTP（eval 服务）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/eval/datasets` | 创建数据集元数据（`name`、`version` 等，body snake_case） |
| `POST` | `/api/v1/eval/datasets/{id}/import` | 上传文件：`Content-Type: text/csv` 或 `application/x-ndjson`（JSONL） |
| `GET` | `/api/v1/eval/datasets/{id}` | 数据集详情 |
| `GET` | `/api/v1/eval/datasets/{id}/cases` | 分页列出 cases |

创建数据集响应建议包含：`id`、`name`、`version`、`created_at`（ISO-8601）。

## JSONL：每行一个 case（P0 最小字段）

```json
{
  "case_id": "rag_basic_001",
  "question": "用户自然语言问题",
  "expected_behavior": "answer",
  "requires_citations": true,
  "tags": ["rag/basic", "smoke"]
}
```

- `case_id`：同一 dataset 内唯一；若缺省可由导入器生成稳定 id（不推荐生产）。
- `expected_behavior`：`answer` | `clarify` | `deny` | `tool`（与 SSOT 一致）。
- `requires_citations`：boolean。
- `tags`：字符串数组；`attack/*` 等按 eval-upgrade 约定。

## CSV：首行为表头（与 JSON 字段名一致）

建议列：`case_id,question,expected_behavior,requires_citations,tags`

- `tags` 单元格：`|`-分隔或 JSON 数组字符串二选一（Day2 实现时固定一种并写进 README）。

## 校验（Day2 最小）

- 必填：`question`、`expected_behavior`、`requires_citations`。
- `expected_behavior` 枚举非法 → 拒绝该行并汇总错误文件/计数。
- 导入完成后 `GET .../datasets/{id}` 返回 `case_count`（≥30 为 P0 数据集门槛）。

## 与后续 run 的衔接

- run 创建时引用 `dataset_id` + `target_id`；执行器按 case 顺序（P0 串行）调用 `POST /api/v1/eval/chat`，Header 使用附录 C 的 `X-Eval-*`。
