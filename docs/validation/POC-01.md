# POC-01：浏览器直连（最高优先级）

[验证总览](../TESTING.md) · [交付报告](../personal/reports/POC-01.md)

目标：真实 HTTPS Origin 下能否使用目标网关；不开发正式页面。

- 最小实现：独立静态测试页 + Fetch/AbortController + 候选 SSE parser；Key 运行时输入，不提交仓库或平台。先以本机 mock 验证协议，再由用户授权实测真实调用；每次仅使用小型无隐私请求。
- 目标矩阵：CC Switch、Magpie、Ollama、至少一个常用公网兼容网关、一个官方 Chat Completions API；未提供的服务不猜测。macOS/Windows/Linux 与 Chromium/Safari/Firefox 代表组合，先覆盖用户实际使用的组合。
- 测试：记录真实 origin、URL 拼接、GET /models 可选、普通 POST、OPTIONS/CORS、LNA/混合内容、SSE UTF-8 跨字节/多行/空事件、正常结束、断流、空内容、工具调用、401/404/429/5xx、取消、超时、响应上限。区分可读 HTTP 错误与浏览器不透明网络错误。
- 通过：至少用户确认的目标组合能完成完整正文；取消后 UI 和读取任务停止；异常不判题、不自动重发；列表失败可手填。真实兼容性逐组合判定，不强求所有浏览器全通过。
- 失败：本机阻塞走 D0，评估 P2；公网阻塞评估 P1，不自动传 Key、不放宽任意 Origin。上游是否停止计费单列，不可仅凭客户端取消判断。

记录表（每项实际组合复制一行）：

| OS/浏览器版本 | 网关/版本 | Origin | CORS/权限 | 列表 | 普通 | SSE | 取消 | CLI | 结论/证据 |
|---|---|---|---|---|---|---|---|---|---|
| macOS 26.5.2 arm64 / Chrome Headless 158.0.8127.38 | 本机受控 Mock | `http://127.0.0.1:65183` → `http://127.0.0.1:65184` | PASS，覆盖允许/拒绝预检和实际响应缺 CORS 头；LNA / 混合内容 NOT_RUN | PASS | PASS | PASS | NOT_RUN | 本机浏览器 34/34 |
| 目标 OS / 浏览器待确认 | CC Switch、Magpie、Ollama、公网兼容网关、官方 Chat Completions API | 真实 HTTPS 测试站待提供 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | NOT_RUN | 缺测试站、目标端点与版本及实际调用授权 |

### POC-01 执行记录

2026-11-25。本机 Mock **PASS**，真实 HTTPS **BLOCKED**。源码与运行方式见 [浏览器直连 PoC](../../poc/browser-direct/README.md)。本机使用 Node.js 26.13.1、npm 11.20.0，以及锁定的 [eventsource-parser 3.0.8](https://www.npmjs.com/package/eventsource-parser/v/3.0.8)（MIT）。依赖只用于 PoC。

| 实际操作 | 退出码与结果 |
|---|---|
| `npm --prefix poc/browser-direct install --offline --ignore-scripts --no-audit --no-fund` | 1，npm 元数据缓存不可用（ENOTCACHED） |
| `npm --prefix poc/browser-direct install --ignore-scripts --no-audit --no-fund` | 0，安装 1 个锁定依赖 |
| 默认沙箱用 Node 尝试绑定 `127.0.0.1` | Node 探测失败（EPERM）；同批末项环境查询成功，整条命令退出码 0 |
| `npm --prefix poc/browser-direct test`（允许本机端口和 Chrome 进程后） | 0，首次 33/33；新增取消竞态用例后 34/34 |
| `npm --prefix poc/browser-direct ci --offline --ignore-scripts --no-audit --no-fund` | 0，按锁文件重装成功 |
| `node --check`（4 个 .mjs 文件）与 `git diff --check` | 均为 0 |

- 请求证据。JSON POST 收到一次 OPTIONS，预检包含 `authorization,content-type`，随后收到一次 POST。拒绝预检时为一次 OPTIONS、零 POST。仅实际响应缺 CORS 头时，Mock 收到 POST，Fetch 读不到响应。
- 协议证据。覆盖模型列表失败后手填 ID、无 Key、正文逐字符保留、跨写块 UTF-8、SSE 多行/空事件、`stop` + `[DONE]`、EOF/断流、非法 UTF-8、非正常结束、空正文、工具调用、HTTP 401/404/429/503 和三个字节上限。把 `stop` + `[DONE]` 当作本次候选结束规则，Q08/Q10 仍需真实联调确认。
- 取消与超时。Mock 观察到取消及首响应、流空闲、总时长超时各触发一次请求并断开。显式取消保留已收到的正文片段，也覆盖了结束事件已缓冲的情况。真实上游的计费停止情况未验证。
- 真实阶段。需确定测试站 Origin、首个网关的端点与版本、目标浏览器及短请求授权。Safari、Firefox、其他操作系统和 HTTPS → loopback 的本地网络权限、混合内容行为均未运行。
