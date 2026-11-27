# 模型接入与安全

本文件是 P0/P1/P2 模型接入的权威设计。验证方法见 [TESTING](../TESTING.md)。

| 优先级 | 接入方式 | 定位 |
|---|---|---|
| P0 | 浏览器直连 | 首版主要运行方式 |
| P0 | D0 手动运行与结果导入 | 首版通用兼容方案，无需安装专用客户端 |
| P1 | 独立安全代理 | 解决公网模型接口的 CORS 兼容问题，默认不部署 |
| P2 | 本地客户端或浏览器扩展 | 本机网关自动化接入，按实际需求再评估 |

M2 先以本机 Mock 建立直连，目标人群常用网关的真实 HTTPS 验证纳入上线验收。如果常用公网目标在合理配置后仍普遍无法直连，结合受影响用户比例与 D0 操作成本决定是否提前评审 P1。主要阻塞若来自本机网络访问，应优先维持 D0 并评估 P2，公网代理无法访问用户的 localhost。

## 2.1 浏览器直连

Vue 参考 Open WebUI Direct Connections，用 Fetch、AbortController 和成熟 SSE 解析库直接请求用户配置的 OpenAI Chat Completions 兼容接口。Java 不发起推理。连接设置支持 Base URL、可选 Key（无鉴权本地网关留空）、Model ID、获取模型列表和测试连接。Base URL 输入实际 API 前缀，按规则拼接 GET /models 或 POST /chat/completions，展示最终路径，不盲加 /v1。拒绝 URL 中的凭据、查询参数和片段。模型列表不可用仍可手填 ID，并用小型生成请求验证实际连通性。发送前提示测试可能消耗额度。

支持普通 JSON 和 SSE。前端逐步显示 assistant 正文，SSE 解码处理 UTF-8 跨块、多行事件和结束条件。首版收取第一条结果，普通响应与 SSE 均验证兼容协议的正常终止和非空正文。截断、内容过滤、未完成、工具调用、协议错误及用户取消均不提交判题。AbortController 用于停止读取和中止请求，上游是否停止计费须实测；同一次运行不自动重发。设定总时长、首包/流空闲和累计字节上限，超限立即取消；阈值通过联调确定。失败可展示已收到的部分正文，运行记录只提交必要、脱敏的故障代码。

轻量 ModelTransport 封装测试连接、查询列表、普通请求与流式请求。首版仅 DirectTransport，D0 独立完成导出和导入，两者输出统一的运行结果（runId、来源、状态、可选原始正文和结束原因、安全故障代码、时间与模型 ID）。后续可添加 ProxyTransport。题目加载、快照、消息拼装和结果展示复用，共用后端判题，暂不引入插件系统。

### 跨源与本地网络

- 目标接口必须允许真实网站 Origin，通常含协议、域名和端口。Authorization 与 application/json 通常触发 OPTIONS 预检，需要检查允许的方法、请求头、预检和实际响应。网站自身的前端或 Nginx 配置无法替目标放行 CORS。
- 本机网关还受 Mixed Content、Local Network Access、浏览器权限与版本影响。以同源 HTTPS 预览站在目标浏览器和操作系统实测。公网 HTTPS 为默认要求，本地 HTTP 首版建议仅对显式配置的 localhost 或 loopback 地址开放，并以浏览器实际许可为准。
- 优先确认目标网关能否限定网站 Origin。自动注入用户 Key 的本地代理若对任意网站开放，恶意页面可能消耗用户额度。兼容性不足时引导 D0，不建议开放通配 Origin、使用 no-cors、关闭浏览器安全功能或接入公共 CORS 代理。
- HTTP 状态可读时，按鉴权、路径/模型、限流、上游异常等分别提示。Fetch 拒绝时提示可能为 CORS、DNS、TLS、网络或本地网络权限，给出相应检查和手动运行入口，不武断归类为 CORS。

### 浏览器凭据与渲染安全

- API Key 默认只在 JavaScript 内存。用户主动开启后允许当前标签页用 sessionStorage 保留，退出、删除连接和主动清除时同时清理内存及会话副本。首版无 localStorage/IndexedDB 密钥存储或自研加密；内存和 sessionStorage 都无法彻底防御 XSS。
- 非敏感连接配置可在浏览器持久化，按站点账号隔离。Key 绑定规范化 Base URL，改地址时先停用旧 Key，须明确确认才可绑定新目标。Fetch 使用 redirect: 'error'、credentials: 'omit'，仅对明确选定的目标发 Authorization。提示仅接入可信服务。
- 直连 Key 只交给用户选择的模型服务，不进入平台 API、URL、日志、埋点、错误报告、运行记录和导出。运行/导出使用专门字段白名单，禁止序列化配置对象或请求头。用户自行粘贴的学习正文如含私人信息，应在提交前删除并重新导入合适内容。
- Prompt 和模型输出按不可信文本处理。Vue 默认转义，Markdown 可用 markdown-it 并关闭原始 HTML；确需输出不可信 HTML 时用 DOMPurify。限制链接协议并默认关闭外部图片等自动加载。原始输出与渲染视图分离，判题用原始正文。
- CSP 优先限制脚本、对象、嵌入、框架与资源来源，避免第三方统计/客服脚本和外部 JS CDN。固定站点 CSP 无法按页面内用户输入动态扩展。M2 比较固定来源名单和仅针对 connect-src 的 HTTPS 范围及受限 loopback 例外，验证实际浏览器的匹配行为；业务代码仍强制 URL 规则，不因自定义模型地址而整体放宽 CSP。

## 2.2 D0 手动运行

1. 编辑 Prompt、选择用例，冻结本次请求。
2. 复制 JSON 或下载 request.json，在已有模型工具中执行（例如 curl、Postman、Bruno 或本机网关支持的工具）。
3. 对照待导入卡片的题目、用例与 runId，导入响应 JSON，或粘贴完整的模型原始正文。
4. 浏览器读取并提取正文，提交统一判题。修改 Prompt 或更换用例后建立新运行。

D0 不要求网站知道 Base URL 或 API Key。每次只导出一个用例，下载名建议为 request-<caseId>-<runId>.json，复制时也显示关联信息；标准 API 响应不保证回传这些 ID，用户在导入位置确认对应关系，平台不做可信绑定证明。可在导出时填 Model ID 或本地替换占位值，导入时可确认实际使用的模型。冻结用于上下文与历史记录。导出没有凭据或认证头，使用兼容请求结构。

```json
{
  "model": "YOUR_MODEL",
  "messages": [
    {"role": "system", "content": "Reply using exactly three words."},
    {"role": "user", "content": "Describe the ocean."}
  ],
  "stream": false
}
```

该片段演示请求结构。正式题目仍使用各自的输出契约。

**响应导入**。浏览器先限制文件/粘贴长度，再本地读取 JSON，检查外壳、错误对象、choices、第一条 message 及 finish_reason。只接受正常结束且 choices[0].message.content 为非空字符串的响应。拒绝空白、错误返回、截断、工具调用和不支持的结束状态；不导入完整 HTTP 日志。粘贴方式由用户确认正文完整，提醒聊天客户端可能额外添加系统提示。两种方式都保留原文，不 trim、不去围栏、不修复 JSON、不改标点，仅将快照关联及判题所需数据提交平台。

命令示例见 [D0 CLI 指南](../guides/D0_CLI.md)，包含 macOS/Linux Bash 和 Windows PowerShell。

## 2.3 P1 独立安全代理

候选链路为 Vue → Spring Boot → OkHttp → Smokescreen → 公网模型 API。Smokescreen 是 HTTP CONNECT 出站代理候选。公开源码中的 safeResolve/dialContext 体现了解析地址校验和建连路径的关联，最终行为以锁定版本验证为准。Spring Boot 控制业务接口、鉴权、请求格式和限流；候选选型须经[验证计划](../TESTING.md)。只有用户显式选择并授权时，平台才接收本次调用的 API Key；不持久化、不记录，直连失败时不得自动转交。该路径限公网 API。

正式设计需验证 SSRF 防护、DNS 重绑定防护、实际连接 IP 与校验结果一致、IPv4/IPv6 及特殊用途地址过滤、云元数据与服务器自身地址拦截、拒绝重定向、TLS 证书和主机名验证。使用 nftables、systemd 或已验证的 OS 级网络隔离防止应用直连绕过，代理不可用时默认拒绝。还需验证请求并发、速率、超时、响应大小、跨用户隔离、Key 脱敏、SSE 与取消传播。Smokescreen 的维护、安全策略、实际内存及隔离能力通过验证后才可定型。 [Smokescreen 源码](https://github.com/stripe/smokescreen/blob/master/pkg/smokescreen/smokescreen.go) · [OWASP SSRF 指南](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)
