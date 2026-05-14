# GitHub Actions Secrets

本文说明 `eval-full-targets` workflow 调用真实 target 时需要配置的 Variables / Secrets。若 target 只在本机或内网可访问，可以暂不配置；workflow 会在缺少 target URL 时跳过。

## Repository Variables

在 GitHub 仓库页面进入 **Settings -> Secrets and variables -> Actions -> Variables**。

| Name | 示例 | 说明 |
| --- | --- | --- |
| `EVAL_TARGET_VAGENT_BASE_URL` | `https://vagent.example.com` | vagent target 的 base URL。 |
| `EVAL_TARGET_TRAVEL_AI_BASE_URL` | `https://travel-ai.example.com` | travel-ai target 的 base URL。 |

这些 URL 必须能被 GitHub-hosted runner 访问。如果服务只在内网，建议使用 self-hosted runner 或其他受控网络方案。

## Repository Secrets

在 **Settings -> Secrets and variables -> Actions -> Secrets** 配置。

| Name | 说明 |
| --- | --- |
| `EVAL_DEFAULT_EVAL_TOKEN` | 调用 target 时写入 `X-Eval-Token` 的 token。 |
| `EVAL_TRAVEL_AI_GATEWAY_KEY` | 可选；target 需要 `X-Eval-Gateway-Key` 时配置。 |
| `EVAL_MEMBERSHIP_SALT` | 可选；需要和 target 侧 membership evidence 生成逻辑对齐时配置。 |

不要把真实 token、gateway key、salt 写入仓库文件或 workflow 日志。

## Target 侧要求

- target 需要暴露 `POST /api/v1/eval/chat` 或等价 eval endpoint。
- target 的 eval API 鉴权配置要和 `EVAL_DEFAULT_EVAL_TOKEN` 对齐。
- 如果 target 要求 gateway key，eval 侧必须提供对应 secret。
- HTTPS、CIDR、网关等网络策略需要允许 CI runner 访问。

## GitHub CLI 示例

```bash
gh variable set EVAL_TARGET_VAGENT_BASE_URL --body "https://vagent.example.com"
gh variable set EVAL_TARGET_TRAVEL_AI_BASE_URL --body "https://travel-ai.example.com"

gh secret set EVAL_DEFAULT_EVAL_TOKEN --body "<token>"
gh secret set EVAL_TRAVEL_AI_GATEWAY_KEY --body "<gateway-key>"
gh secret set EVAL_MEMBERSHIP_SALT --body "<salt>"
```

## 运行 workflow

打开 **Actions -> eval-full-targets -> Run workflow** 手动触发。成功后会上传 report artifacts，例如：

- `out/report-vagent.json`
- `out/report-travel-ai.json`

这些 artifact 用于 CI 结果查看，不应直接提交回仓库。
