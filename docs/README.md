# Documentation

面向公开仓库展示时，建议先看根目录 [`README.md`](../README.md)。本目录保留与运行、集成、证据相关的文档；开发过程记录已归档到 [`archive/`](archive/)。

## 推荐阅读

| 文档 | 说明 |
| --- | --- |
| [`evidence/eval-run-demo.md`](evidence/eval-run-demo.md) | 一次完整评测闭环的脱敏示例：导入 dataset、创建 run、调用 target、查看 result、report 和 compare。 |
| [`evidence/README.md`](evidence/README.md) | evidence 目录说明和脱敏样例索引。 |
| [`target-integration-meta-and-compare.md`](target-integration-meta-and-compare.md) | target 侧需要配合的 eval/chat 响应、meta 落库和 compare 摘要约定。 |
| [`github-actions-secrets.md`](github-actions-secrets.md) | GitHub Actions 调用真实 target 时需要的 Variables / Secrets 配置。 |
| [`examples/day2-sample.jsonl`](examples/day2-sample.jsonl) | JSONL dataset 导入样例。 |
| [`examples/day2-sample.csv`](examples/day2-sample.csv) | CSV dataset 导入样例。 |

## 文档分类

- **保留**：项目说明、target 集成、CI 配置、脱敏 evidence、dataset 示例。
- **归档**：阶段总结、Day/P0/P1 开发记录、checklist、历史实现说明。
- **删除**：临时日志、一次性运行结果、本地截图、崩溃日志等不适合公开展示的文件。

## Archive

[`archive/`](archive/) 中的文档保留历史上下文，但不建议作为面试或公开仓库首页的主入口。
