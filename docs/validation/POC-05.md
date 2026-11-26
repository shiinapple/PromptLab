# POC-05：严格 JSON 判题

[验证总览](../TESTING.md)

- 最小实验：JUnit + Jackson + 候选 JSON Schema validator（2020-12），本地受信 schema；样题 002 一个 case。
- 测试：[fixture 矩阵](../TESTING.md#最小判题-fixture-矩阵)；重复键、尾随 token、深度/数值限制；拒绝远程 $ref（mock 观察无外连）；类型、整数语义、数组顺序、重复 length、BigDecimal；格式错与答案错分离。
- 通过：所有 fixture 与预期一致，不提取或修复 JSON，解析与 Schema 校验可配置明确上限。最坏输入不使应用失去响应。
- 失败：更换或配置成熟库；不要自写 JSON parser 或允许任意脚本作为替代。
