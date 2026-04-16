# vagent-eval

P0 统一评测服务（独立部署）。Day1：可启动、读取 `eval.targets`、Actuator 与 `/internal/eval/status`。

## 运行

需要 **JDK 21** 与 **Maven 3.9+**：

```bash
mvn spring-boot:run
```

- 健康检查：`GET http://localhost:8099/actuator/health`
- 进程与 target 配置摘要：`GET http://localhost:8099/internal/eval/status`（不含 token）

配置见 `src/main/resources/application.yml`；生产示例见 `application-example.yml`（勿提交真实密钥）。

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
