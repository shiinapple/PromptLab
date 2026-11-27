# REST API 与错误协议

架构评审建议。路径均为与 SPA 同源的 `/api`，业务请求使用 UTF-8 JSON，用户私有响应禁缓存。首版由同一发行版交付前后端；对现有字段只作兼容演进，出现独立调用方或不可兼容升级再评估 URL 版本。服务端请求 DTO 拒绝未知字段及超限，响应客户端容忍新增字段。

## 端点

| 阶段 | 方法与路径 | 请求与响应 |
|---|---|---|
| M0 | GET /api/health | 200 最小就绪状态，未就绪 503；不含构建配置、数据路径或诊断详情 |
| M1/M3 | GET /api/problems | 当前发布的题目摘要与版本，list 返回 `items`；M1 仅 002。 |
| M1/M3 | GET /api/problems/{problemId} | 题目、当前版本、公开用例、编辑槽、预览模板和受信资产引用；按 zh-CN/en 返回展示文本。 |
| M1 | POST /api/runs | `Idempotency-Key` 请求头，正文含 problemId、version、caseId、mode、slots、受限 requestParams；201 + Location，返回 runId、冻结消息、参数、status、expiresAt。相同意图重试 200。 |
| M1 | POST /api/runs/{runId}/result | 提交完整正文或明确的故障/取消；200 返回终态与 feedback，重试与并发见数据模型。 |
| M1 | GET /api/runs/{runId} | 本人快照与状态，已判题时含原文、verdict 和 checks。 |
| M4 | GET /api/auth/csrf | 同源返回 token 与 headerName，允许匿名初始化会话。 |
| M4 | POST /api/auth/login；POST /api/auth/logout | 登录接收 email、password，成功 204；退出使会话失效并返回 204。两者校验 CSRF。 |
| M4 | GET /api/me | 用户标识、显示信息。无有效会话返回 401。 |
| M4 | GET/PATCH /api/settings | 读取或更新 locale 与允许的编辑器偏好。PATCH 返回保存后的记录。 |
| M4 | GET/PUT /api/problems/{problemId}/draft | GET 可带 version，缺省取当前版，无草稿返回 `draft: null`。PUT 明确 version、caseId、slots、revision，创建返回 201，更新 200。旧版草稿可读取并复制到当前版。 |
| M4 | GET /api/problems/{problemId}/runs | 本人跨版本历史，按时间倒序分页，列表只含摘要。 |
| M4 | POST /api/submissions；GET /api/submissions | 传 runId 归档已判题运行，201 或幂等 200；分页查看本人归档。 |
| M4 | DELETE /api/submissions/{submissionId} | 移除本人归档标记，成功 204。 |

M1 到本地 M3 使用显式本地测试主体。M4 生产形态下，除健康、获取 CSRF 与登录入口外，业务 API 需要会话。列表规模增长时采用 `limit` 与不透明 `cursor`，返回 `{items, nextCursor}`。游标绑定排序与筛选，读取始终带当前主体。运行详情与列表字段各自白名单，列表不返回模型原文或完整快照。

## 运行载荷示例

创建运行。调用方为一次显式操作生成随机幂等键，并在网络重试时复用。题库固定自然语言输入与允许的参数；完整上游请求由客户端基于返回快照组装，模型 ID 可在 D0 外部工具中显式替换。新运行要求当前发布版本，创建重试优先从既有 run 恢复冻结意图及其版本，再校验是否相同，旧版本退休不能破坏重试。

```json
{
  "problemId": "002",
  "version": 1,
  "caseId": "case-1",
  "mode": "D0",
  "slots": {"system": "只输出符合题面约束的 JSON。"},
  "requestParams": {}
}
```

提交结果时 `status` 是按类型分支的请求字段：

| 分支 | 接收字段 | 处理 |
|---|---|---|
| COMPLETED | `rawOutput` 必填、可选 `reported` | UTF-8 有界正文；普通格式错误与答案错误均生成已完成判题。D0 粘贴在 UI 显式确认完整。 |
| FAILED | `failureCode` 必填、可选 `reported` | 稳定枚举，例如 TIMEOUT、UPSTREAM_ERROR、TRUNCATED、PROTOCOL_ERROR；无正文、无判题。 |
| CANCELED | 可选 `reported` | 服务端保存取消状态，无正文、无判题。 |

`reported` 可包含有界的 modelId、安全枚举 finishReason、非负 durationMs 与 token 计数，缺失即未知。D0 模型标识可手填，测量条件可不同；不接收上游 URL、认证头、响应外壳、部分输出和规则覆写字段。响应使用 `status` 表示运行状态，以 `verdict` 和 `checks` 表示判题反馈，GET 和 POST result 使用同一结果投影。[模型接入](MODEL_INTEGRATION.md) · [判题标签](JUDGE.md) · [状态机与事务](DATA_MODEL.md#状态并发与删除)

## M3 接口变更（待实现）

现在的实现只认题号 002 和槽名 `user`。M3 按下表调整，其余接口不变。任务见 [M3](../tasks/M3.md)。

| 接口 | 变更 | 任务 |
|---|---|---|
| POST /api/runs | `slots` 的键由题目决定。不是对象返回 400 `INVALID_REQUEST`；键集合与题包不一致返回 422 `INVALID_SLOT`；单个值上限仍为 8 KiB。题号不在发布清单返回 404 | M3-02、M3-03 |
| GET /api/problems | `items` 列出发布清单中的全部题目，按 `problemId` 升序；每项为 `problemId`、`version`、`title`、`category`、`difficulty` | M3-03 |
| GET /api/problems/{problemId} | 发布清单中的题目都可读。`cases[]` 增加 `grading`：该用例 `assertions` 为空时是 `STRUCTURE_ONLY`，否则是 `RULES`。页面不接收断言内容 | M3-03 |
| GET /api/problems/{problemId}?version=N | 新增可选参数。允许 1 到当前发布号之间实际存在的版本；高于发布号或不存在返回 404；不是正整数返回 400 `INVALID_REQUEST`。响应增加 `publishedVersion` | M3-04 |
| GET /api/problems/{problemId} | `cases` 按 `caseIds` 顺序返回全部公开用例 | M3-05 |

页面行为：

- 地址里 `?problem=001` 选题，缺省取列表第一项。带 `?run=` 时以运行记录的题号和版本为准。
- 恢复旧版本运行时，题面按该版本显示并标明“旧版本”。新建运行只能用当前版本，页面提供切回入口。
- `STRUCTURE_ONLY` 且 `PASSED` 时显示“JSON 格式通过；人设表现未自动评价”。这句文案属于 001；以后出现别的结构题时再改成题目配置。

保持不变：幂等重试先按既有运行比对意图，再校验当前题包；判题按运行记录的题号、版本、`caseId` 和内容摘要加载规则；数据库结构沿用 V2。

## 错误响应

应用建议使用 Spring MVC ProblemDetail / `application/problem+json`，扩展稳定业务码、本地化键、受限字段详情与请求 ID。统一映射 MVC 异常和 Spring Security 的 401/403 入口。Nginx 自行产生的 413/502/503 等可返回非 JSON，API client 按 HTTP 状态回退提示，避免解析错误覆盖原始故障。[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "code": "DRAFT_REVISION_CONFLICT",
  "messageKey": "errors.draftRevisionConflict",
  "details": {"serverRevision": 4},
  "requestId": "example-request"
}
```

| HTTP | 代表业务码与语义 |
|---|---|
| 400 | INVALID_JSON、UNKNOWN_FIELD、INVALID_CURSOR、INVALID_IDEMPOTENCY_KEY |
| 401 | AUTH_REQUIRED、INVALID_CREDENTIALS |
| 403 | CSRF_INVALID、FORBIDDEN（全局权限不足） |
| 404 | RESOURCE_NOT_FOUND（含他人资源）；未知题目或运行 |
| 409 | VERSION_CHANGED、DRAFT_REVISION_CONFLICT、IDEMPOTENCY_CONFLICT、RESULT_CONFLICT、RUN_NOT_COMPLETED |
| 410 | RUN_EXPIRED（本人的过期运行提交）；查询仍可显示 EXPIRED，按策略清理后为 404 |
| 413 / 415 | PAYLOAD_TOO_LARGE、UNSUPPORTED_MEDIA_TYPE |
| 422 | INVALID_CASE、INVALID_SLOT、INVALID_RESULT（可解析的业务输入不符） |
| 429 / 503 | RATE_LIMITED、TEMPORARILY_UNAVAILABLE（受控负载或 DB 忙）；可用 Retry-After 表达重试时间 |
| 500 | INTERNAL_ERROR，记录请求 ID 并返回通用提示 |

M0 的 `GET /api/health` 在应用接受流量时返回 `200 {"status":"UP"}`。启动迁移完成后应用才进入就绪状态。应用拒绝流量时返回 `503 TEMPORARILY_UNAVAILABLE`。此接口读取应用就绪状态。M0 已覆盖 MVC 路由 404 `RESOURCE_NOT_FOUND` 和方法 405 `METHOD_NOT_ALLOWED`，405 保留 `Allow` 响应头。

`FORMAT_ERROR` 和 `WRONG_ANSWER` 是判题结果，符合 API 载荷约束的作答正常返回 200。错误详情只返回预先允许的字段路径、枚举或安全数值，不含原始请求内容、SQL、栈、内部配置、模型地址和凭据。
