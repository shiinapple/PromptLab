# POC-05：严格 JSON 判题

[验证总览](../TESTING.md)

- 最小实验：JUnit + Jackson + 候选 JSON Schema validator（2020-12），本地受信 schema；样题 002 一个 case。
- 测试：[fixture 矩阵](../TESTING.md#最小判题-fixture-矩阵)；重复键、尾随 token、深度/数值限制；拒绝远程 $ref（mock 观察无外连）；类型、整数语义、数组顺序、重复 length、BigDecimal；格式错与答案错分离。
- 通过：所有 fixture 与预期一致，不提取或修复 JSON，解析与 Schema 校验可配置明确上限。最坏输入不使应用失去响应。
- 失败：更换或配置成熟库；不要自写 JSON parser 或允许任意脚本作为替代。

## 2026-11-26 执行证据

macOS arm64、Java 21.0.8。用 Maven Wrapper 执行 `clean verify`，退出 0。正式判题器 27 项测试全部通过。

- Jackson 3.1.8 检查整段 JSON、重复键和尾随 token。覆盖标准空白、字段类型、额外字段、围栏、注释、附文、NaN 和多值。样题四份原始 fixture 的判定及业务失败 ID 与预期一致。
- 使用本地 2020-12 Schema 的封闭子集。支持当前题目所用的对象、数组、整数与布尔。按数学整数语义接收 `13.0` 和 `130e-1`。高精度非整数拒绝，倒序通过，重复数值键和漏测边界返回答案错误。题库加载器拒绝带 HTTP `$ref` 的 Schema，本地 Mock 收到 0 次请求。
- 验证 256 KiB UTF-8 正文、64 层嵌套及 128 字符数值 token 限额。HTTP 独立拒绝超大正文及无 Content-Length 的超大流，之后仍可查询原运行，状态保持待处理。

已验证样题 002 的严格判题。通用 JSON Schema 库与更大词汇范围未验证。目标主机的并发与资源压力归 M6。
