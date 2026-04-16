# GitHub Actions：为「对真实 target 跑 eval」准备 Secrets / Variables

> **何时做**：若 Vagent / travel-ai **仅本机**开发、未暴露公网，**不必**配置本文中的 `EVAL_TARGET_*_BASE_URL`；`eval-full-targets` workflow 会跳过。请在 **P0+ 升级收口后**，将 **staging/生产** 部署到 GitHub 托管 runner **可访问** 的 HTTPS 地址（或采用 self-hosted runner / 隧道）后，再按本文配置。总纲见 `plans/eval-upgrade.md` **§P0+ 自动化 A0**（Vagent 仓库）。

> 说明：**我无法替你登录 GitHub 点按钮写入 Secrets**（需要你的账号权限）。按下面做一遍即可；也可用 **GitHub CLI `gh`** 在已登录终端一键写入。

## 你需要什么

1. **两个 target 的公网可达 HTTPS 地址**（GitHub 托管 runner 在公网；若服务只在内网，须改用 [self-hosted runner](https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/about-self-hosted-runners) 或 VPN/隧道，否则 workflow 永远连不上）。
2. **与被测服务一致的 `X-Eval-Token` 明文**（仅存 GitHub Secret，勿提交仓库）。
3. （可选）**membership 盐**：若跑 `requires_citations` + membership 相关题，需与业务侧一致；否则可先不配 smoke 题集。

## 在 GitHub Web UI 里配置

仓库：`vagent-eval` → **Settings** → **Secrets and variables** → **Actions**

### Repository variables（非敏感）

| Name | 示例 | 说明 |
|------|------|------|
| `EVAL_TARGET_VAGENT_BASE_URL` | `https://vagent.example.com` | Vagent 根地址（不要尾斜杠也可） |
| `EVAL_TARGET_TRAVEL_AI_BASE_URL` | `https://travel-ai.example.com` | travel-ai 根地址 |

### Repository secrets（敏感）

| Name | 说明 |
|------|------|
| `EVAL_DEFAULT_EVAL_TOKEN` | eval 调用被测时写入的 `X-Eval-Token` 明文 |
| `EVAL_MEMBERSHIP_SALT` | （可选）与 `eval.membership.salt` 对齐 |

### 被测服务侧必须满足

- `vagent.eval.api.enabled=true` / `travelai.eval.api.enabled=true`（以各自仓库为准）
- Token 与 eval 侧一致；HTTPS 与 CIDR 策略允许 GitHub runner 出口 IP（或放宽到你们能接受的范围）

## 用 GitHub CLI 写入（本机已 `gh auth login`）

在 `vagent-eval` 仓库根目录执行（把值换成你的真实内容）：

```bash
gh variable set EVAL_TARGET_VAGENT_BASE_URL --body "https://你的-vagent-域名"
gh variable set EVAL_TARGET_TRAVEL_AI_BASE_URL --body "https://你的-travel-ai-域名"

gh secret set EVAL_DEFAULT_EVAL_TOKEN --body "你的明文-token"
# 可选：
# gh secret set EVAL_MEMBERSHIP_SALT --body "你的盐"
```

## 跑起来之后

- 打开 **Actions** → **eval-full-targets** → **Run workflow**（手动触发）。
- 产物：`out/report-vagent.json`、`out/report-travel-ai.json`（Artifact 下载）。

## 仍连不上时

- 确认两个 URL 在 **公网 curl** 能访问到健康检查或根路径。
- 确认被测 **未** 要求仅内网 IP 而拒绝 GitHub IP。
- 不要把 `EVAL_TARGETS_*` 环境变量在 CI 里“半覆盖”成空行（与本地踩坑相同）。
