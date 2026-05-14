# vagent-eval

`vagent-eval` 是一个面向 RAG / LLM 应用的规则型 Evaluation Harness。它负责导入固定题集，按配置调用一个或多个被测服务，用确定性规则评估响应，把 run/result 落库，并生成 report 或 base/candidate compare 结果。

本项目**不是** LLM-as-judge 服务：不会再调用一个大模型给答案质量打分。它更适合做可重复的回归评测，覆盖 RAG 行为、引用约束、低置信处理、工具调用期望和响应契约。

## Core Flow

```text
Dataset Import -> Target Call -> Rule Evaluation -> Result Store -> Report / Compare
```

- **Dataset Import**：创建 dataset，并从 JSONL 或 CSV 导入 case。
- **Target Call**：通过 `POST /api/v1/eval/chat` 调用配置好的 target。
- **Rule Evaluation**：校验响应契约、期望行为、引用、membership 证据、低置信 meta 和工具调用结果。
- **Result Store**：将 dataset、case、run、result、audit event 持久化到 PostgreSQL。
- **Report / Compare**：生成单次 run 报告、按 tag 分桶报告，以及 base/candidate 回归对比。

## Tech Stack

- Java 21
- Spring Boot 3
- Spring Web / JDBC / Validation / Actuator
- PostgreSQL
- Flyway
- Redis / Lettuce：可选的 run 队列和配额协调
- Micrometer / Prometheus metrics
- JUnit 5, Mockito, Spring Boot Test
- GitHub Actions CI

## Implemented Features

- Dataset 管理 API，支持 JSONL 和 CSV 导入。
- 基于 `eval.targets` 的多 target 评测执行。
- 面向 RAG 响应的确定性规则评估：
  - 响应契约校验
  - expected behavior 匹配
  - 引用存在性检查
  - hashed citation membership 检查
  - low-confidence reason 检查
  - tool expectation 检查
- PostgreSQL 持久化 dataset、case、run、result 和 audit event。
- 使用 Flyway 管理数据库 schema。
- 生成包含 pass rate、skipped rate、latency、error-code summary 和 dataset slices 的 report。
- 提供 base/candidate compare API，用于回归差异查看。
- 可选 Redis-backed run queue 和 per-target global run quota 基础能力。
- Micrometer 指标覆盖 run 创建、终态、case verdict、target HTTP latency 和调度拒绝。
- GitHub Actions 使用 PostgreSQL service 执行 `mvn test`。

Redis queue/quota 已有基础实现和针对性测试；完整的生产式压测和验收证据仍属于后续补充项。

## Quick Start

依赖：

- JDK 21
- Maven 3.9+，或 Windows Maven Wrapper `mvnw.cmd`
- PostgreSQL，默认测试和本地持久化使用数据库 `eval`

运行测试：

```bash
mvn test
```

启动服务：

```bash
mvn spring-boot:run
```

Windows 无全局 Maven 时：

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

健康检查：

```bash
curl http://localhost:8099/actuator/health
```

本机 token、gateway key、私有 target 地址等覆盖配置应放在 `src/main/resources/application-local.yml`，该文件已被 Git 忽略。不要提交真实密钥、salt 或私有服务地址。

## Known Limitations

- 这是规则型 regression harness，不是语义答案打分系统。
- run 执行时仍会调用配置的 target 服务，不是完全离线的模型评测器。
- 本地 probe mode 只能验证契约和规则链路，不能证明真实 RAG 检索质量。
- Redis queue/quota 已有基础实现和测试，但完整压测和验收证据仍待补充。
- 测试执行默认需要可用的 PostgreSQL，除非调整测试配置。
- 部分管理 API 安全配置为了本地 demo 可开关；生产使用应收口 token、网络边界和 HTTPS。

## Roadmap

- 补充简洁架构图。
- 增加 Docker Compose，方便本地启动 PostgreSQL / Redis。
- 补齐 Redis queue/quota 的压测和验收证据。
- 增加 OpenAPI 文档或生成式 API 示例。
- 改进 CI 产物，自动生成脱敏 report / compare 示例。
