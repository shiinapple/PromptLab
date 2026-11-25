# 下一条 Codex 指令

建议普通推理强度执行单一 PoC，不让 Agent 重做方案。先将本规划包放入仓库并检查合并冲突，确认 Q01 测试目标。

## 可直接复制

请阅读 AGENTS.md、docs/MODEL_INTEGRATION.md、docs/TESTING.md 中 POC-01 和 docs/DECISIONS.md 的 Q01/Q10。此次只执行 POC-01 浏览器直连的最小验证，不初始化正式业务工程，不处理其他里程碑。

1. 检查 Git 状态、实际环境和已有工具，保留用户修改。
2. 在独立临时目录或明确的 poc/browser-direct 目录建立最小实验页和受控 mock。使用 Fetch、AbortController 和成熟 SSE parser 候选；记录版本及许可证。
3. 验证普通 JSON、SSE 拆包及 UTF-8、正常结束、断流、取消、超时、HTTP 错误和响应上限；使用虚构 Key。先做可自动运行的 mock 测试。
4. 真实 CC Switch/Magpie/Ollama、公网服务及 HTTPS Origin 仅在用户提供实际环境与授权后测试。没有环境标 BLOCKED，不猜测 URL、端口或版本，不读取未知凭据，不发起可能扣费的未经授权请求。
5. 不关闭 CORS/浏览器安全功能，不添加公网代理，不提交任何真实凭据，不擅自修改核心设计。
6. 报告变更文件、实际命令和退出码、通过/失败/未运行项、各组合证据、兼容性结论和待用户确认事项。更新验证记录，不把 mock 通过写成真实网关通过。

完成后停止，等待我与 Chat 分析结果再进入下一任务。不要自动提交或推送。
