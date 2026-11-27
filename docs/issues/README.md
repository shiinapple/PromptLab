# 问题清单

发现的问题放在这里，一个问题一个文件。问题单写现象、证据和期望结果；怎么排期、改哪些文件、怎样验收写在任务卡里。

| 编号 | 问题 | 级别 | 状态 | 任务 |
|---|---|---|---|---|
| [001](001-schema-subset.md) | Schema 子集表达不了字符串和可选字段 | 阻塞 001 题 | FIXED | M3-01 |
| [002](002-extra-field-count.md) | 额外字段检查只比较数量 | 潜在误判通过 | FIXED | M3-01 |
| [003](003-empty-assertions.md) | 只判结构的题无法加载 | 阻塞 001 题 | FIXED | M3-01 |
| [004](004-case-variables.md) | 用例变量只从可编辑槽收集，消息结构未校验 | 阻塞 001 题 | FIXED | M3-02 |
| [005](005-slot-name.md) | 编辑槽名写死为 `user` | 阻塞 001 题 | FIXED | M3-02 |
| [006](006-single-problem.md) | 题号写死为 002 | 阻塞多题 | FIXED | M3-03 |
| [007](007-historical-version.md) | 读题接口只返回当前版本 | 发新版后旧运行配错题面 | FIXED | M3-04 |
| [008](008-single-case.md) | 每题只能有一个用例 | 阻塞多用例 | FIXED | M3-05 |

状态为 `OPEN`、`FIXED`（附测试证据）或 `WONTFIX`（附原因）。新问题接着编号，修复后保留文件。

## 证据说明

标为“实测”的结果来自 2026-11-27 的一次性探针：在仓库副本里放入按[样题 001](../design/PROBLEMS.md#52-样题-001月港酒馆的守夜人--the-night-watcher-of-moon-harbor)编写的题包，调用 `ProblemCatalog.loadPublished` 和 `JudgeService.judge`。加载器遇到第一个错误就停止，所以探针逐步改题包，让后面的限制依次暴露。探针没有提交进仓库，修复时请在正式测试中复现。
