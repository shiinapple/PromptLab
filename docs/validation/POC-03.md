# POC-03 提示词练习与题目质量

2026-11-25。离线复测 **17/17 通过**，真实 DeepSeek Flash 主实验 **108/108 完成**。T1、T2 整理为候选教学题，T3 保留本次端点观察。以下数据来自固定用例和单一模型。

| 实验 | 要验证的问题 |
|---|---|
| T1 三词段 | 对照无约束、简单指令、明确规则、加入示例四组，观察规则遵守与冲突处理。 |
| T2 颜色映射 | 用多个输入对照固定答案和按输入映射，检查格式通过时答案是否依赖输入。 |
| T3 消息位置 | 对照四种消息布局，分别记录接口是否接受和规则是否通过。 |

[复跑命令](../../poc/prompt-evaluation/README.md) · [实验配置](../../poc/prompt-evaluation/config.json) · [用例与提示组](../../poc/prompt-evaluation/cases.json)

## POC-03 执行记录

2026-11-25，Python 3.12.13、OpenAI Python SDK 2.26.0、HTTPX 0.28.1。采用任务书的 SDK 备选方案。该方案便于在测试中捕获实际序列化的请求、检查连续 `user` 消息和 `thinking` 参数，并将重试与预算统一由 runner 负责。保留了任务书的模型、种子、用例、轮数和生成参数。[Chat Completions](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/) · [思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/) · [Flash 费率](https://api-docs.deepseek.com/quick_start/pricing/)

| 检查 | 结果 |
|---|---|
| `python -m unittest discover -s tests -v` | 退出 0，15/15 通过。覆盖严格原文判题、错误分类、预算、重试、中断恢复、SDK Mock 与完整 110 格模拟。 |
| `python runner.py dry-run` | 退出 0，实际发送 0 次。生成 2 个 smoke、72 个 T1、12 个 T2、24 个 T3 请求格子。按峰时费率预留 USD 0.066792，未计重试。 |
| `python runner.py preflight` | 无环境变量时退出 2。使用虚构值时退出 0，用于检查本地条件，预检不发送网络请求。 |
| `python runner.py all --allow-live --key-stdin --run-id deepseek-flash-20261125` | 退出 2。首格 `SMOKE/A/F01/1` 返回 HTTP 401，延迟 496.85 ms。尝试 1 次，0 次重试，有效响应 0 个，剩余 109 格 `NOT_RUN`。 |
| `python runner.py summary --run-id deepseek-flash-20261125` | 退出 0，从真实尝试日志重建统计。 |

DeepSeek 将 401 定义为 API Key 鉴权失败。该响应没有用量，费用记为未知。该 run 未进入 T1、T2 和 T3。随后使用新 run ID 完成复测，见下节。[官方错误码](https://api-docs.deepseek.com/quick_start/error_codes/)

预检证据位于 `poc/prompt-evaluation/results/preflight/`。真实尝试的脱敏日志、请求清单和统计位于本机 `poc/prompt-evaluation/results/live/deepseek-flash-20261125/`。

## POC-03 单次 smoke 与主实验复测

新 run ID `deepseek-flash-20261125-02`。使用隐藏输入和本机管道向进程传入 Key。离线 Mock 通过虚构值检查读取、首尾空白处理、拒绝带标签的输入和 SDK 的 `Authorization: Bearer` 构造。真实 smoke 只发送 `SMOKE/A/F01/1` 一次，HTTP 200、`finish_reason=stop`，返回 15 输入和 8 输出 token。该无约束组的模型正文按三词段规则判为 `WRONG_ANSWER`，鉴权、响应及用量检查通过。

主实验 108/108 完成，108 个请求全部 HTTP 200，55 个满足各自判题规则，53 个为 `WRONG_ANSWER`，零重试。逐项比对上次预检文件，108 个主实验 payload、顺序、判定预期及预算预留一致。

| 实验 | 分组结果 |
|---|---|
| T1 三词段 | A 0/18，B 3/18，C 12/18，D 17/18。冲突用例分别为 0/15、0/15、9/15、14/15。 |
| T2 颜色映射 | FIXED 0/6，MAPPING 6/6。FIXED 六次返回 `Red`，大小写也影响结果。 |
| T3 消息结构 | R1 4/6、R2 4/6、R3 4/6、R4 5/6。R2 的连续 `user` 消息 6/6 被此端点接受。 |

本轮 109 次合计 6,987 输入和 778 输出 token，按峰时费率估算 USD 0.0030297。实测结果适用于本次 Flash 调用和固定用例，学习效果仍需用户实验。证据位于本机 `poc/prompt-evaluation/results/live/deepseek-flash-20261125-02/`。

## 归档与教学转化

- T1 整理为[三词段候选题](../../content/problems/candidate-three-ascii-words.md)，目标是编写可验证且能处理冲突的输出协议。格式本身允许常量解，学生可直接通过错误原文定位违反的规则。
- T2 整理为[颜色映射候选题](../../content/problems/candidate-color-code-mapping.md)，目标是用多个预期值检查输入是否真正参与输出。实测固定组输出 `Red`，大小写也是失败原因。理论常量小写 `red` 只会命中红色用例。
- T3 保留接口接受率和规则通过率。R2 连续 `user` 消息在本次端点上得到 6/6 HTTP 200，R4 仅比 R1 多通过一个格子。单模型、小样本、固定输入和部分对照同时改变历史结构，证据不足以确定通用角色优先级、跨模型兼容或可靠提升。正式模板需针对目标网关另验。
- 初次 401 和复测成功的原始日志、正文、计划、逐格状态与统计均保留在原有本机目录。结项整理阶段未发出新的模型请求。
