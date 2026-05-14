# vagent-eval

`vagent-eval` is a rule-based Evaluation Harness for RAG / LLM applications. It imports fixed datasets, calls one or more target services through a stable eval API, evaluates responses with deterministic rules, stores run results, and generates reports or base/candidate comparisons.

This project is **not** an LLM-as-judge service. It is designed for repeatable regression checks around RAG behavior, citation constraints, low-confidence handling, tool expectations, and response contract compliance.

## Core Flow

```text
Dataset Import -> Target Call -> Rule Evaluation -> Result Store -> Report / Compare
```

- **Dataset Import**: create datasets and import cases from JSONL or CSV.
- **Target Call**: execute cases against configured targets via `POST /api/v1/eval/chat`.
- **Rule Evaluation**: validate response contract, expected behavior, citations, membership evidence, low-confidence metadata, and tool outcomes.
- **Result Store**: persist runs, results, audit events, datasets, and cases in PostgreSQL.
- **Report / Compare**: generate single-run summaries, tag-bucket reports, and base/candidate regression diffs.

## Tech Stack

- Java 21
- Spring Boot 3
- Spring Web / JDBC / Validation / Actuator
- PostgreSQL
- Flyway
- Redis / Lettuce for optional run queue and quota coordination
- Micrometer / Prometheus metrics
- JUnit 5, Mockito, Spring Boot Test
- GitHub Actions CI

## Implemented Features

- Dataset management APIs with JSONL and CSV import.
- Multi-target eval execution through configurable `eval.targets`.
- Deterministic rule evaluation for RAG-style responses:
  - response contract validation
  - expected behavior matching
  - citation presence checks
  - hashed citation membership checks
  - low-confidence reason checks
  - tool expectation checks
- PostgreSQL persistence for datasets, cases, runs, results, and audit events.
- Flyway migrations for schema setup.
- Report generation with pass rate, skipped rate, latency, error-code summary, and dataset slices.
- Base/candidate compare API for regression review.
- Optional Redis-backed run queue and global per-target run quota primitives.
- Micrometer metrics for run creation, terminal states, case verdicts, target HTTP latency, and scheduling rejection.
- CI workflow with PostgreSQL service and `mvn test`.

Redis queue/quota support has the foundational implementation and focused tests. Full pressure-test evidence for production-like quota behavior is still listed as follow-up work.

## Quick Start

Requirements:

- JDK 21
- Maven 3.9+ or the Windows Maven wrapper `mvnw.cmd`
- PostgreSQL database named `eval` for integration tests and local persistence

Run tests:

```bash
mvn test
```

Start the service:

```bash
mvn spring-boot:run
```

Windows without global Maven:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Health check:

```bash
curl http://localhost:8099/actuator/health
```

Local secrets and target overrides should go into `src/main/resources/application-local.yml`, which is ignored by Git. Do not commit real tokens, gateway keys, salts, or private target URLs.

## Environment Variables

| Variable | Required | Purpose |
| --- | --- | --- |
| `EVAL_DEFAULT_EVAL_TOKEN` | Optional | Default token sent to target services as `X-Eval-Token` when a target-specific token is not configured. |
| `EVAL_TRAVEL_AI_GATEWAY_KEY` | Optional | Gateway key sent as `X-Eval-Gateway-Key` for targets that require an eval gateway header. |
| `EVAL_MEMBERSHIP_SALT` | Optional | Salt used by target-side membership evidence generation when supplied through deployment configuration. |
| `SPRING_DATASOURCE_URL` | Local/CI | PostgreSQL JDBC URL, for example `jdbc:postgresql://localhost:5432/eval`. |
| `SPRING_DATASOURCE_USERNAME` | Local/CI | PostgreSQL username. |
| `SPRING_DATASOURCE_PASSWORD` | Local/CI | PostgreSQL password. |
| `SPRING_DATA_REDIS_HOST` | Optional | Redis host when enabling Redis scheduling. |
| `SPRING_DATA_REDIS_PORT` | Optional | Redis port when enabling Redis scheduling. |
| `SPRING_DATA_REDIS_PASSWORD` | Optional | Redis password when needed. |

See [`src/main/resources/application-example.yml`](src/main/resources/application-example.yml) for a sanitized configuration template.

## Core APIs

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/v1/eval/datasets` | Create a dataset. |
| `GET` | `/api/v1/eval/datasets` | List datasets. |
| `GET` | `/api/v1/eval/datasets/{dataset_id}` | Get dataset metadata. |
| `DELETE` | `/api/v1/eval/datasets/{dataset_id}` | Delete a dataset and related runs/results. |
| `GET` | `/api/v1/eval/datasets/{dataset_id}/cases` | List imported cases. |
| `POST` | `/api/v1/eval/datasets/{dataset_id}/import` | Import JSONL or CSV cases. |
| `POST` | `/api/v1/eval/runs` | Create and enqueue a run for a target. |
| `GET` | `/api/v1/eval/runs` | List runs. |
| `GET` | `/api/v1/eval/runs/{run_id}` | Get run status. |
| `POST` | `/api/v1/eval/runs/{run_id}/cancel` | Request run cancellation. |
| `GET` | `/api/v1/eval/runs/{run_id}/results` | List case results. |
| `GET` | `/api/v1/eval/runs/{run_id}/report` | Generate a run report. |
| `GET` | `/api/v1/eval/runs/{run_id}/report/buckets` | Generate tag-prefix bucket reports. |
| `GET` | `/api/v1/eval/compare` | Compare base and candidate runs. |
| `GET` | `/internal/eval/status` | Show sanitized runtime and target configuration summary. |

The optional local probe endpoint `POST /api/v1/eval/chat` is for demos and contract testing only. Real evaluation runs still call configured target services.

## Evaluation Rules

The evaluator is deterministic and rule based. It checks whether the target response matches a known eval contract and expected case behavior.

Important rule categories:

- **Contract**: required fields and valid JSON shape.
- **Behavior**: expected behavior such as answer, clarify, deny, or tool.
- **Citations**: answer cases that require citations must provide valid sources.
- **Citation membership**: cited source IDs must be present in target-provided retrieval evidence, preferably through hashed membership fields.
- **Low confidence**: when `meta.low_confidence=true`, the response must provide non-empty `meta.low_confidence_reasons`.
- **Tool path**: tool cases must show required, used, and succeeded tool state.
- **Security boundary**: sensitive debug fields are only allowed in explicit eval-debug mode.

This harness is useful for regression checks and behavior gates. It does not score answer quality with another model.

## Evidence

- End-to-end demo walkthrough: [`docs/evidence/eval-run-demo.md`](docs/evidence/eval-run-demo.md)
- Sanitized sample report: [`docs/evidence/day10-report.sample.json`](docs/evidence/day10-report.sample.json)
- Sanitized sample compare output: [`docs/evidence/day10-compare.sample.json`](docs/evidence/day10-compare.sample.json)
- Target integration notes: [`docs/target-integration-meta-and-compare.md`](docs/target-integration-meta-and-compare.md)
- GitHub Actions secrets guide: [`docs/github-actions-secrets.md`](docs/github-actions-secrets.md)

## Known Limitations

- This is a rule-based regression harness, not semantic answer grading.
- A run still calls the configured target service; it is not a fully offline model evaluator.
- Local probe mode validates contracts and rules, but does not prove real RAG retrieval quality.
- Redis queue/quota support has basic implementation and tests; complete pressure-test and acceptance evidence is still pending.
- Test execution expects PostgreSQL to be available unless the test profile is adjusted.
- Some admin/API security settings are intentionally configurable for local demos; production use should require token, network boundary, and HTTPS controls.

## Roadmap

- Add a concise architecture diagram.
- Add Docker Compose for local PostgreSQL/Redis startup.
- Expand Redis queue/quota pressure-test evidence.
- Add OpenAPI documentation or generated API examples.
- Improve CI artifacts for sanitized report and compare examples.
