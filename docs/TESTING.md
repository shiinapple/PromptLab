# 技术验证与测试计划

以下记录已执行的 PoC 证据。其余 PoC 状态为 NOT_RUN。记录实际环境、依赖锁定版本、命令、观察、脱敏证据、结论和未覆盖项。PASS/FAIL/BLOCKED/NOT_RUN 分开。Mock 通过不能代替真实网关或同规格服务器验证。

## 原稿验证矩阵

| 开发前最小验证 | 操作与通过标准 |
|---|---|
| 网关和浏览器 | 从真实 HTTPS Origin 验证 CC Switch、Magpie、Ollama、至少一个目标用户常用公网兼容网关和一个官方 Chat Completions API。覆盖目标 macOS/Windows/Linux 与 Chromium/Safari/Firefox 的代表组合。记录版本、协议、Origin 白名单能力、预检、权限提示、列表、普通生成、SSE 和取消。浏览器与 CLI 分别记录通过或失败原因，暂未测试标未验证 |
| 凭据与 CSP | 验证公网 HTTPS、受限 loopback、同源业务 API 与脚本策略；核对配置切换、重定向拒绝、清除和注销。抓取请求和日志确认未发 Key 给平台；核对不可信链接、图片及文本不执行、不自动外连 |
| D0 与统一判题 | 用同一原文走直连回传、Bash、Windows PowerShell 和粘贴，验证同判定；用错文件、401、3xx、超时、截断和无正文分别验证不会产生可误导入的结果。测试零配置和可选 Key，核对多用例关联 |
| 栈和资源 | 验证 Java 21、Boot/MVC、SQLite 驱动与迁移工具、Schema 库、SSE 库的兼容和许可证；验证 2 核 2 GB 的运行余量与 SQLite 恢复。正式界面开发前核对可用的 Design Token |
| 视觉题 | 验证实际模型支持图片 content parts、平台题图素材和请求大小限制；同一图文快照可直连与离线导出。通不过就保留样题设计，推迟正式发布 |

## 原稿 MVP 验收

- 题目与草稿。文字题 001/002 走同一页面，预览与 payload 一致，i18n、切换运行方式、断网和题目升级都保留对应草稿与历史，越权或非法样例绑定由后端拒绝。
- 判题。正常 JSON 与标准空白通过解析，围栏、附文、多值、重复键、错误类型失败。检查数组顺序规则、参考字段不计分和金额类型，区分格式错、答案错与传输故障。
- 模型。覆盖兼容普通响应、SSE 跨包 UTF-8 与多行事件、完整结束、空响应、断流、过大、取消、超时、401/404/429/5xx。故障保留页面内片段，普通运行不判题。刷新不重新触发推理。
- 安全。账号隔离、CSRF、CSP/XSS、密钥生命周期和客户端配置隔离、日志脱敏、导入与 REST 大小上限、题图 MIME/大小及 Schema 无远程读取。服务端使用自己选定的题目规则并拒绝客户端覆盖。
- 记录与部署。验证快照不可变、结果提交重试、归档幂等、并发与过期、掉线重取已有结果，以及同规格主机测量和备份恢复。

> 文字题 001/002 先发布。视觉题 003 单独走 POC-06 和发布验收（DECISIONS D10）。

## POC-01：浏览器直连（最高优先级）

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

2026-11-25。本机 Mock **PASS**，真实 HTTPS **BLOCKED**。源码与运行方式见 [浏览器直连 PoC](../poc/browser-direct/README.md)。本机使用 Node.js 26.13.1、npm 11.20.0，以及锁定的 [eventsource-parser 3.0.8](https://www.npmjs.com/package/eventsource-parser/v/3.0.8)（MIT）。依赖只用于 PoC。

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

## POC-02：凭据与前端安全

- 最小实验：同源业务 mock、一个受控跨源 mock、连接配置/切换和原文渲染；生产候选 CSP 在真实 HTTPS 站验证。
- 检查：用虚构 canary Key，检查平台请求、日志、导出、错误对象及非敏感持久化中没有该值；验证刷新、删除、退出、账号切换、不同标签页；地址更换须明确确认，redirect:error、credentials:omit 生效。
- 使用无害本地测试内容验证 HTML、Markdown 链接、图片和事件属性不执行脚本、不自动外连；检查 script-src/connect-src/object-src/frame-ancestors 等策略。对比仅允许已知来源与受限 HTTPS/loopback 方案。
- 通过：直连仅向选定目标发送凭据；默认无持久化 Key；正常模型/业务请求能执行；测试内容不能触发脚本/自动外连。注明 CSP 在允许任意 HTTPS 连接时不能阻止所有外传。
- 失败：默认纯文本渲染；缩小允许的地址范围或使用 D0；不要通过关闭安全策略解决。

## POC-03：SQLite 与 Java 栈

- 最小实验：Java 21 + 候选受支持 Boot/MVC + SQLite JDBC + JDBC 访问层 + 迁移工具；只建立 users/runs 模拟表，不实现正式账户。
- 验证：记录 Java/Boot/Jackson/驱动/迁移器版本与依赖树；干净库迁移、再次启动、升级已有库、失败迁移行为、WAL 与 busy timeout（每个连接适用项）、事务回滚、外键、并发读写、条件更新、备份恢复。
- 样例负载（建议，不是 SLA）：1/5/10 并发客户端，分别执行短读写事务；记录错误、锁等待、延迟 P50/P95、JVM RSS、CPU 与磁盘增长；同规格目标机器或限制环境另行测量。
- 通过：迁移可复现，不发生丢写/无解释锁错误，备份可恢复并验证数据，2GB 环境在候选负载下无 OOM。数值门槛由实测及目标规模确认。
- 失败：先缩短事务、限制连接/并发并核对驱动；迁移工具不兼容时评估其他成熟工具；仍无法满足需求再评审独立 DB，非默认升级。

## POC-04：严格 JSON 判题

- 最小实验：JUnit + Jackson + 候选 JSON Schema validator（2020-12），本地受信 schema；样题 002 一个 case。
- 测试：下方 fixture 矩阵；重复键、尾随 token、深度/数值限制；拒绝远程 $ref（mock 观察无外连）；类型、整数语义、数组顺序、重复 length、BigDecimal；格式错与答案错分离。
- 通过：所有 fixture 与预期一致，不提取或修复 JSON，解析与 Schema 校验可配置明确上限。最坏输入不使应用失去响应。
- 失败：更换或配置成熟库；不要自写 JSON parser 或允许任意脚本作为替代。

## POC-05：D0 与统一运行结果

- 最小实验：浏览器生成一个请求、导入 mock 响应文件、粘贴原文；CLI 使用原稿 Bash/PowerShell 示例，真实命令仅在具备环境并获授权后测试。
- 测试：同一原文经普通响应、SSE 完整拼接、JSON 文件、粘贴后逐字符一致；预览/冻结快照/实际 messages 一致；Key 不出现在 JSON；文件上限、非法 UTF-8、错误外壳、null/空白 content、截断、tool calls；HTTP 3xx/401/超时失败不产生本次可导入结果；旧文件/错 case 以用户确认处理，不做防作弊。
- 通过：四条来源一致判定；D0 零配置可完成，取消/错误不进入判题；每次独立输出目录，状态检查有效。
- 失败：先保留复制 JSON + 粘贴，修正文件与命令指南；不要求用户关闭 TLS 或浏览器保护。

## POC-06：视觉题（独立后置）

先由所有者制作虚构无个人信息题图，验证受信 MIME/尺寸、固定资产引用、真实视觉模型、data URL 与 D0 导出/导入。通过后才能公开样题 003；未通过不阻塞文本 MVP。不声称已具备 Agent 工具调用。

## 初始实验参数（待确认/实测）

| 参数 | 建议起点 |
|---|---|
| 单次完整模型正文 UTF-8 | 256 KiB |
| 导入响应 JSON / 累计 SSE 解码字节 | 1 MiB |
| 单个 SSE 事件 | 256 KiB |
| 总时长 / 首个有效响应 / 空闲 | 180 / 30 / 30 秒 |
| 单页面运行 | 1 |
| JSON 深度 / 单数字字符数 | 64 / 128 |
| 题图 | 单图 512 KiB；实际图文 payload 另设上限 |
| API body | 按 endpoint 明确字节限额，JSON 转义开销与正文分开考虑 |
| JVM | Xms128m/Xmx512m 初始实验；实际 RSS 非仅堆内存 |
| SQLite | 小连接池（候选 2–4）；按压测验证 |

以上仅实验预算。浏览器流按实际接收字节限额，不信任 Content-Length 或 max_tokens；D0 先检查 File.size，再检查正文实际字节。服务端对原文和请求独立限额。

## 最小判题 fixture 矩阵

样题 002 Case 1 正确数组为 5/false,6/true,7/true,11/true,12/true,13/false。

| 输入变体 | 预期 |
|---|---|
| 正确对象 / 标准 JSON 空白 / 数组倒序 | PASSED |
| Markdown 围栏、附文、两个 JSON 值、尾随逗号、注释、重复对象键、NaN | FORMAT_ERROR |
| 缺 cases、非数组、额外字段、布尔字符串、length 非整数 | FORMAT_ERROR |
| 缺 length 13、重复 length、额外 length 14、5 判 true | WRONG_ANSWER |
| 超限深度或正文 | 拒绝/限额故障；不得判为 PASSED |
| 样题 001 缺可选参考字段 | PASSED（格式通过，不评价角色） |
| 样题 003 total 为 "68.5" / "68.00" | FORMAT_ERROR / WRONG_ANSWER（视觉发布后） |

## 发布验收分层

- 单元：消息组装、模板边界、判题、URL、安全策略、导入与 SSE 解析。
- 集成：真实 SQLite 临时库、迁移、鉴权/CSRF、越权、幂等、原子终态与快照不可变。
- E2E：mock 模型成功/故障、D0、草稿冲突、刷新、语言切换、历史归档。真实网关测试另列，不在默认 CI 扣费。
- 发布：目标规格资源测试、备份恢复、HTTPS、权限、日志轮转、回滚、无生产调试端点。未完成 M4/M6 不公开服务。
