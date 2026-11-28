# 文档

第一次打开项目，先按[本地开发](../README.md#本地开发)跑样题 002。继续开发，从[项目状态](STATUS.md)开始。

## 按目的查

| 我要做什么 | 看哪里 |
|---|---|
| 看现在做到哪、下一步是什么 | [STATUS](STATUS.md) · [路线图](ROADMAP.md) |
| 领开发任务 | [任务卡](tasks/README.md) |
| 看已发现的问题 | [问题清单](issues/README.md) |
| 查已定事项 | [决策记录](DECISIONS.md) |
| 看测试范围和待验项 | [TESTING](TESTING.md) · [PoC 证据](validation/) |
| 并行开发、切换分支、清理工作树 | [Git 分支与工作树](guides/GIT_WORKTREE.md) |

## 按主题查

| 主题 | 设计 | 验证与指南 |
|---|---|---|
| 范围与页面 | [产品](design/PRODUCT.md) · [页面交互](design/UI.md) | |
| 样式 | [Design Token](../frontend/src/styles/tokens.css) · [HTML 图册](design/design-tokens.html)（本机打开） | |
| 结构、接口、数据 | [架构](design/ARCHITECTURE.md) · [REST API](design/API.md) · [数据模型](design/DATA_MODEL.md) | [SQLite](validation/POC-04.md) |
| 题目与判题 | [题库](design/PROBLEMS.md) · [判题](design/JUDGE.md) | [判题测试](validation/POC-05.md) |
| 模型接入与安全 | [模型接入](design/MODEL_INTEGRATION.md) | [直连](validation/POC-01.md) · [凭据与 CSP](validation/POC-02.md) |
| 手动运行（D0） | [模型接入](design/MODEL_INTEGRATION.md) | [命令行指南](guides/D0_CLI.md) · [D0 验证](validation/POC-06.md) |
| 参考与复用 | [来源](design/REFERENCES.md) | |
| 协作与 Git | | [Git 分支与工作树](guides/GIT_WORKTREE.md) |

## 放什么、放哪里

| 目录 | 内容 |
|---|---|
| `design/` | 系统应该怎样工作 |
| `issues/` | 发现的问题：现象、证据、期望结果 |
| `tasks/` | 要做的事、验收标准和状态 |
| `validation/`、`guides/` | 实测证据、操作步骤 |
| `personal/`、`agent/` | 只留在本机，不进 Git |

同一件事只在一处写全，其他地方放链接。
