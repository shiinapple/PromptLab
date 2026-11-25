# Prompt Practice POC-01 浏览器直连交付报告

2026-11-25

**结论：本机 Mock 阶段通过，Chrome 自动化用例 34/34。** 在本机两个不同端口之间，浏览器完成了 JSON 和 SSE 请求，按目标接口返回的 CORS 头决定是否放行，并能在取消和超时后关闭连接。真实 HTTPS 阶段为 **BLOCKED**，待确定测试站、目标网关和调用授权。正式工程仍处于设计阶段。

## 已完成的设计决策

| 事项 | 已确认的方案 |
|---|---|
| 开发测试环境 | 先验证本机 Mock，再从真实 HTTPS 测试站测试目标网关 |
| 首版账户 | 项目所有者预建账户，邀请用户内测。公开自助注册和邮件找回后置 |
| 发布范围 | 文字题 001/002 先发布，视觉题 003 经独立验证后发布 |
| 旧项目代码 | 完全新建。正式界面的 Design Token 另行核对 |

完整记录见 [DECISIONS](../../DECISIONS.md)。

## 实现与产物

| 文件 | 做了什么 |
|---|---|
| [index.html](../../../poc/browser-direct/index.html) | 提供本机实验页，可运行用例并查看结果 |
| [transport.mjs](../../../poc/browser-direct/transport.mjs) | 基于 Fetch 和 AbortController 请求模型列表、普通响应和 SSE。保留原始模型正文，限制响应大小及运行时间，拒绝重定向 |
| [mock.mjs](../../../poc/browser-direct/mock.mjs) | 提供受控模型响应、CORS 策略及故障场景，记录预检、实际请求与连接断开，用虚构 Key 检查鉴权头 |
| [cases.mjs](../../../poc/browser-direct/cases.mjs) · [harness.mjs](../../../poc/browser-direct/harness.mjs) | 定义 34 个浏览器用例，启动两个仅绑定本机的随机端口及临时 Chrome 配置，汇总通过数和退出码 |
| [package.json](../../../poc/browser-direct/package.json) · [package-lock.json](../../../poc/browser-direct/package-lock.json) · [.gitignore](../../../poc/browser-direct/.gitignore) | 提供启动和测试命令，锁定 PoC 的 SSE 解析依赖 eventsource-parser 3.0.8（MIT），将本地依赖目录设为 Git 忽略项 |
| [PoC 运行说明](../../../poc/browser-direct/README.md) | 记录自动和手动复跑方式、依赖要求和暂定协议规则 |

设计同步到 [PRODUCT](../../design/PRODUCT.md)、[ARCHITECTURE](../../design/ARCHITECTURE.md)、[ROADMAP](../../ROADMAP.md)、[M0](../../tasks/M0.md) 与 [任务清单](../../tasks/README.md)。[TESTING](../../TESTING.md) 记录验证证据，[项目状态](../../STATUS.md) 更新真实阶段的执行条件，[项目 README](../../../README.md) 提供当前状态和入口。[AGENTS.md](../../../AGENTS.md) 登记后续交付报告约定。

## 验证结果

环境为 macOS 26.5.2（arm64）、Headless Chrome 158.0.8127.38、Node.js 26.13.1 和 npm 11.20.0。

| 验证项 | 实际结果 |
|---|---|
| 模型列表与普通 JSON | 列表可读取，返回 404 时可手填模型 ID 并继续运行。带虚构 Key 和无 Key 的请求均成功，正文空格和 Unicode 字符得到保留 |
| SSE | 处理跨写块 UTF-8、多行数据、空事件和正常结束。拒绝缺少结束信号、截断、断流、空正文、工具调用、内容过滤和非法 UTF-8 |
| CORS、HTTP 与重定向 | 预检获准时 Mock 收到一次 OPTIONS 和一次 POST。拒绝预检时只收到 OPTIONS。实际响应缺 CORS 头时，POST 已到达 Mock，但浏览器无法读取响应。具备 CORS 头的 401、404、429 和 503 可读取，重定向目标未收到请求 |
| 取消和超时 | 取消、首响应超时、流空闲超时和总时长超时分别只触发一次请求并断开连接。已有正文可作为失败片段保留。取消优先于已缓冲的结束事件 |
| 大小限制 | 累计响应、单个 SSE 事件和模型正文的字节上限均触发了预期拒绝 |

| 实际执行 | 退出码及结果 |
|---|---|
| `npm --prefix poc/browser-direct install --offline --ignore-scripts --no-audit --no-fund` | 1。初次离线安装缺少 npm 元数据，报 `ENOTCACHED` |
| `npm --prefix poc/browser-direct install --ignore-scripts --no-audit --no-fund` | 0。安装一个依赖 |
| Node 绑定本机端口的环境探测 | Node 子命令报 `EPERM`。同批末项环境查询成功，整条命令退出码 0。实际浏览器测试在取得本机端口与进程权限后运行 |
| `npm --prefix poc/browser-direct test` | 0。首轮 33/33，补充取消竞态用例后 34/34 |
| `npm --prefix poc/browser-direct ci --offline --ignore-scripts --no-audit --no-fund` | 0。按锁文件重装成功 |
| `node --check`（4 个 `.mjs` 文件）与 `git diff --check` | 均为 0 |

## 结论与下一步

本机证据支持保留浏览器直连方案，并继续用真实 HTTPS Origin 逐个验证目标网关。PoC 暂定 SSE 必须先收到 `finish_reason: "stop"`，再收到完整的 `[DONE]`；单个 SSE 事件的大小在解析出完整事件时检查，未结束事件受整个响应的字节上限约束。

真实阶段需要 HTTPS 测试站地址、首个网关的 Base URL 和版本、目标浏览器及短推理请求授权。届时重点验证真实网关的 CORS、HTTPS 页面访问本机网关时的浏览器权限和混合内容限制、实际 SSE 结束方式及取消传播。其他浏览器、操作系统、CLI 和真实上游计费情况仍待验证。
