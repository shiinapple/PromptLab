# POC-06：D0 与统一运行结果

[验证总览](../TESTING.md)

- 最小实验：浏览器生成一个请求、导入 mock 响应文件、粘贴原文；CLI 使用 [Bash / PowerShell 指南](../guides/D0_CLI.md)，真实命令仅在具备环境并获授权后测试。
- 测试：同一原文经普通响应、SSE 完整拼接、JSON 文件、粘贴后逐字符一致；预览/冻结快照/实际 messages 一致；Key 不出现在 JSON；文件上限、非法 UTF-8、错误外壳、null/空白 content、截断、tool calls；HTTP 3xx/401/超时失败不产生本次可导入结果；旧文件/错 case 以用户确认处理，不做防作弊。
- 通过：四条来源一致判定；D0 零配置可完成，取消/错误不进入判题；每次独立输出目录，状态检查有效。
- 失败：先保留复制 JSON + 粘贴，修正文件与命令指南；不要求用户关闭 TLS 或浏览器保护。

## 2026-11-26 D0 执行证据

在 macOS arm64 使用打包 JAR、Vite Preview 与 Headless Chrome，`npm --prefix frontend run test:e2e` 退出 0，完成四组流程。

- 当前预览、服务端冻结消息、剪贴板和下载文件逐项一致。导出包含 `YOUR_MODEL`、冻结的 messages、`stream:false`。导入卡片显示 case 和 runId，用户勾选确认后才能提交。
- 文件导入和整段粘贴用两个独立运行提交同一正文，原始 CRLF、前后空白、判定和检查列表逐项一致。修改 Prompt 创建新运行，旧消息保持原样。截断响应被拒绝，运行保持待导入。刷新后仍可读取原文及业务错误反馈。
- `npm --prefix frontend test` 退出 0，共 21 项。D0 覆盖导出、空/错误响应、非法 UTF-8、大小限制、工具调用、拒绝和截断。HTTP 测试确认取消和执行失败无判题结论。
- `python3 poc/d0/bash_mock.py` 退出 0。基于[现有 Bash 指南](../guides/D0_CLI.md)替换回环 Mock 端口和测试超时。无凭据及虚构凭据两次成功，各生成独立响应文件。401、302、超时、传输截断四次均退出 1，没有产出可导入文件。虚构凭据只进入本地 Mock 请求头，脚本输出未显示其值。[Bash Mock 脚本](../../poc/d0/bash_mock.py)

上述记录覆盖 M1 本地 D0。Windows PowerShell 和真实网关未运行。

## 2026-11-26 M2 四来源验收

`npm --prefix frontend run test:e2e` 退出 0。同一份样题 002 正文含 CRLF、前后空白与制表符，经普通 Chat Completions、SSE、D0 JSON 文件及整段剪贴板粘贴进入四条独立运行。四个运行读回的 `rawOutput`、`verdict`、`checks` 逐项相同。普通与 SSE 发出的 messages 同各自平台快照及前端预览相同。

主动停止和断流的运行无原文、判定或检查结果入库，浏览器保留已读片段；切换 D0 保留编辑器中的 Prompt。刷新可读回原运行，模型 Mock 请求数未增加。模拟结果保存 503 后重试，仅提交已经得到的结果。真实网关与 Windows PowerShell 继续待验。
