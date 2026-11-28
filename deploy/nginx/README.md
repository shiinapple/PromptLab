# Lab 站点响应头

部署时用 `https://lab.shiinapple.cn` 提供 SPA 和 `/api/`。平台 API 使用同源会话、Cookie 和 CSRF；在 Lab 的 Nginx 或 Spring 上都无需为它配置跨源放行。Nginx 只把 `/api/` 转发到部署时确定的内网 Spring 上游，模型请求由浏览器发往 `https://api.deepseek.com`。

## Lab 的 CSP

在返回 SPA 文档的 `server` 或 `location` 引用 [csp.conf](csp.conf)。现有 `connect-src 'self' https: http://localhost:* http://127.0.0.1:*` 已涵盖 DeepSeek HTTPS 与同源 `/api/`。如果内测部署只批准 DeepSeek 直连，可在该策略里把 `connect-src` 写成 `connect-src 'self' https://api.deepseek.com`。保留脚本、对象和嵌入约束，以及 `Referrer-Policy`。SPA 最终响应只配置一份生效的 CSP，避免重复头部把允许来源交叉收紧。

如果下级 `location` 自己配置了 `add_header`，在默认继承规则下上级 `add_header` 不会继承下来。在最终返回 HTML 的位置核对配置，保留 `always`。部署后检查 GET 文档的 `Content-Security-Policy` 和 `Referrer-Policy`，再用浏览器确认同源 API 与允许的模型请求都能发出。[Nginx `add_header` 文档](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header)

## 模型网关的 CORS

针对 `POST /chat/completions`，网关需要接受来自 `https://lab.shiinapple.cn` 的 OPTIONS，返回对应的 `Access-Control-Allow-Origin`、包含 `POST` 的 `Access-Control-Allow-Methods`，以及包含 `authorization, content-type` 的 `Access-Control-Allow-Headers`。普通 JSON、SSE 以及需要让浏览器读取的上游错误响应都需要匹配的 `Access-Control-Allow-Origin`。如果网关按请求 Origin 动态生成响应头，还需正确处理 `Vary: Origin`。若支持 `/models` 列表，另外核对其 GET 与授权头预检。

这些头由模型网关返回。部署 Lab 站点时只配置自身响应头。网关组合在浏览器实测不兼容时，按模型接入契约提供 D0。请求日志保留状态、耗时等运维信息，避免记录鉴权头、Cookie 或正文。
