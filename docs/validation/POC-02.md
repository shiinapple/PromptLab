# POC-02：凭据与前端安全

[验证总览](../TESTING.md) · [凭据与渲染设计](../design/MODEL_INTEGRATION.md#浏览器凭据与渲染安全)

- 最小实验：同源业务 mock、一个受控跨源 mock、连接配置/切换和原文渲染；生产候选 CSP 在真实 HTTPS 站验证。
- 检查：用虚构 canary Key，检查平台请求、日志、导出、错误对象及非敏感持久化中没有该值；验证刷新、删除、退出、账号切换、不同标签页；地址更换须明确确认，redirect:error、credentials:omit 生效。
- 使用无害本地测试内容验证 HTML、Markdown 链接、图片和事件属性不执行脚本、不自动外连；检查 script-src/connect-src/object-src/frame-ancestors 等策略。对比仅允许已知来源与受限 HTTPS/loopback 方案。
- 通过：直连仅向选定目标发送凭据；默认无持久化 Key；正常模型/业务请求能执行；测试内容不能触发脚本/自动外连。注明 CSP 在允许任意 HTTPS 连接时不能阻止所有外传。
- 失败：默认纯文本渲染；缩小允许的地址范围或使用 D0；不要通过关闭安全策略解决。

## POC-02 执行记录

2026-11-25，本机 Mock **PASS**，真实 HTTPS **BLOCKED**。环境为 macOS 26.5.2、Chrome 158.0.8127.38、Node.js v26.13.1、npm 11.20.0。平台和两个模型 Mock 绑定 `127.0.0.1` 随机端口，分别使用独立的 Chrome 临时配置运行两套测试。模型 Mock 使用每轮随机生成的虚构 Key。

| 在仓库根目录执行 | 实际结果 |
|---|---|
| `npm --prefix poc/browser-direct run test:security` | 退出码 0，11/11 通过 |
| `npm --prefix poc/browser-direct test` | 退出码 0，POC-01 回归 34/34 通过 |
| `for file in poc/browser-direct/*.mjs; do node --check "$file" \|\| exit; done` | 退出码 0 |
| `git diff --check` | 退出码 0 |

默认沙箱首次运行 POC-01 时因 loopback 监听 `EPERM` 退出 1，允许本机监听后通过。POC-02 首轮通过 7/10，另外 3 项在浏览器打开独立标签页时超时。改用 Chrome 本地调试管道创建实际标签页后，刷新、会话隔离和固定来源 CSP 用例均通过。新增嵌入防护用例后，最终共 11 项通过。

| 验证项 | 本机证据 |
|---|---|
| 发送边界 | 模型 Mock 收到选定地址的 Authorization。带凭据的控制请求证明该模型端口可收到平台 Cookie，直连请求省略 Cookie，同源业务 Mock 收到自己的 Cookie。目标变更前无新地址流量，确认后首次调用无认证头，显式录入 Key 后才带认证头。跨源 307 的目标端口未收到跳转请求。 |
| 生命周期 | 默认只保存在内存，实际刷新后请求无认证头。显式启用的会话副本在同一标签页刷新后可用。独立打开的新标签页可读非敏感地址，实际请求无认证头。主动清除、删除连接、退出和账号切换清理当前会话副本。 |
| 平台与导出 | 浏览器测试序列化时附加仿真的 Key 和认证头字段，请求及导出白名单过滤了额外字段。Mock 审计检查平台请求、测试结果上报及模型 URL。平台审计、持久化配置和归一化错误无虚构 Key。 |
| 原文 | 模型响应包含 HTML、事件属性和 Markdown 的本机标记，纯文本输出保留原始空白和字符，浏览器未解析出子元素，探针路由未收到请求。 |
| 固定来源 CSP | 同源和选定模型端口可访问，未列入的第二个端口无请求。 |
| HTTPS 与 loopback 候选 CSP | Chrome 允许 `http://127.0.0.1:*` 和 `http://localhost:*` 的本机请求，拦截未列入的 `127.0.0.2`，并触发 `connect-src`、`script-src-elem`、`object-src` 违规事件。嵌入用例中，对照页正常装入 iframe，设置 `frame-ancestors 'none'` 的测试页被拦截。 |

真实 HTTPS 环境需授权的测试站 Origin、目标网关及测试条件。尚未验证真实 Nginx/HTTPS 响应头、公网模型源、IPv6 loopback、目标浏览器矩阵、带 opener 的会话复制、跨标签页注销同步和正式 Markdown 渲染。候选策略的 `connect-src https:` 允许任意 HTTPS 来源，无法阻止所有经 HTTPS 发出的数据。Q10 保持待确认。

## 2026-11-26 M2 正式前端回归

`npm --prefix frontend run test:e2e` 退出 0。正式 SPA 的虚构 Key 只出现在选定模型请求的 Authorization 和显式启用的当前标签页会话副本。Chrome 网络记录、同源 API、服务进程日志抽查、临时 SQLite 主文件及 WAL、非敏感配置、导出请求均未含该值。设置为默认内存时刷新清除，显式会话保留时同页签刷新可用。独立新标签页没有会话副本。取消地址变更未访问新地址，确认后新地址首次请求无认证头。清除、删除与模型回显原样 Key 的拒绝也通过。账号切换与退出目前覆盖存储逻辑单测，真实账号流程随 M4 接入。

模型返回的 HTML 和 Markdown 标记按原文展示，未创建 DOM 子节点或访问探针页面。使用 [Nginx CSP 候选片段](../../deploy/nginx/csp.conf) 的同一份策略值在打包预览中验证了回环模型请求、同源平台 API、脚本/对象限制和嵌入拒绝。固定来源允许已登记源，候选 `https:` 可匹配额外 HTTPS 源，Chrome DevTools 拦截模拟测试 URL 的响应，验证未拨出 HTTPS。候选 HTTP 例外为 `localhost` 和 `127.0.0.1`，采用可由 CSP 表达并验证的来源。候选的 `connect-src https:` 增加公网连接范围，默认支持策略、真证书/TLS、HTTPS 到本机的权限和实际 Nginx 响应头进入 M6/Q10。
