# 下一条 Codex 指令

POC-01 的本机 Mock 已通过。真实 HTTPS 阶段在提供测试站 Origin、首个目标网关的 Base URL 和版本、目标浏览器及短请求授权后恢复。用例和证据见 [TESTING](TESTING.md#poc-01-执行记录)。

## 可直接复制

请阅读 AGENTS.md、docs/MODEL_INTEGRATION.md、docs/TESTING.md 的 POC-01，以及 docs/DECISIONS.md 的 Q01/Q10。此次仅执行 POC-01 的真实 HTTPS 阶段。

1. 核对已授权的 HTTPS 测试站、网关版本、Base URL、浏览器及推理范围。真实 Key 仅由用户在浏览器运行时输入。
2. 从该 HTTPS Origin 测试目标网关。分别记录列表、普通生成、SSE、取消、OPTIONS 和实际响应的 CORS 头、本地网络权限及混合内容行为。使用短小、无隐私的固定输入。
3. 记录各组合的通过、失败或未运行项，并列明真实上游计费是否已验证。对不兼容的组合记录 D0 路径。
4. 将实际命令、退出码、结果和脱敏证据写入 TESTING。更新后结束本次任务。
