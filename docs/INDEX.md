# Prompt Practice 文档索引

按任务或问题选一行，从对应章节开始。当前接手点见 [项目状态](STATUS.md)，当前验证状态见 [TESTING](TESTING.md)。

## 按任务找资料

| 任务 | 优先读 | 需要时再查 |
|---|---|---|
| POC-01 浏览器直连 | [验证标准和结果](validation/POC-01.md) | [直连协议](design/MODEL_INTEGRATION.md#21-浏览器直连)、[跨源与本地网络](design/MODEL_INTEGRATION.md#跨源与本地网络)、[源码与复跑](../poc/browser-direct/README.md) |
| POC-02 Key、渲染与 CSP | [验证标准](validation/POC-02.md) | [浏览器凭据与渲染安全](design/MODEL_INTEGRATION.md#浏览器凭据与渲染安全)、[待确认的 Q10](DECISIONS.md#待确认原稿--审查新增建议) |
| POC-03、M0 Java 与 SQLite | [栈验证](validation/POC-03.md)、[M0 任务](tasks/M0.md) | [技术与模块](design/ARCHITECTURE.md#32-技术与模块)、[依赖候选](DECISIONS.md#待验证依赖) |
| POC-04、M1 判题和运行闭环 | [判题验证](validation/POC-04.md)、[M1 任务](tasks/M1.md) | [判题契约](design/JUDGE.md)、[fixture 矩阵](TESTING.md#最小判题-fixture-矩阵)、[样题 002](design/PROBLEMS.md) |
| POC-05 手动导入导出 | [D0 验证](validation/POC-05.md) | [D0 流程](design/MODEL_INTEGRATION.md#22-d0-手动运行)、[CLI 指南](guides/D0_CLI.md) |
| POC-06 视觉题 | [视觉验证](validation/POC-06.md) | [样题 003](design/PROBLEMS.md)、[发布决策](DECISIONS.md#已确定) |
| M2 直连产品化 | [M2 任务](tasks/M2.md) | [页面交互](design/UI.md)、[传输与安全](design/MODEL_INTEGRATION.md) |
| 后续阶段和发布 | [路线图](ROADMAP.md)、[任务状态](tasks/README.md) | [发布验收](TESTING.md#发布验收分层) |

## 按问题找依据

| 要查什么 | 去哪里 |
|---|---|
| 首版做什么，哪些决定已确认 | [产品范围](design/PRODUCT.md) · [已确定决策](DECISIONS.md#已确定) |
| URL、凭据、CORS、SSE、D0 的行为 | [模型接入](design/MODEL_INTEGRATION.md) |
| API、数据模型、账户和部署 | [数据模型](design/ARCHITECTURE.md#33-概念数据模型) · [API 与时序](design/ARCHITECTURE.md#34-api-与运行时序) · [账户与部署](design/ARCHITECTURE.md#35-账户资源与部署) |
| 页面怎么操作，题目怎么定义 | [UI](design/UI.md) · [样题](design/PROBLEMS.md) · [判题契约](design/JUDGE.md) |
| 某项设计为什么这么做 | 按 `Rxx` 查 [设计审查](research/REVIEW.md)，按 `Dxx/Qxx` 查 [决策](DECISIONS.md)，按需查 [参考资料](research/REFERENCES.md) |
| 找交付报告、开发日志或学习笔记 | [个人文档](personal/README.md) |
| 找历史设计原稿 | [v0.2](archive/DESIGN-v0.2.md) · [v0.1](archive/DESIGN-v0.1.md) |

## 文档职责

| 问题 | 应维护的位置 |
|---|---|
| 产品和技术现在怎么设计？ | [design/](design/)。已确认的选择和变更原因写入 [DECISIONS](DECISIONS.md) |
| 怎么验证，当前进度如何？ | 每个 PoC 的验收与证据在 [validation/](validation/)，总览和共用验收在 [TESTING](TESTING.md) |
| 哪项开发任务能开始？ | [ROADMAP](ROADMAP.md) 维护阶段依赖，[tasks/](tasks/) 维护单项任务范围、前置条件和状态 |
| 下次从哪里接？ | [项目状态](STATUS.md) 只记录当前优先项与恢复条件 |
| 命令和设计来源去哪找？ | [guides/](guides/) 放操作命令，[research/](research/) 放调研和审查，[archive/](archive/) 放原稿 |
| 给用户的材料去哪找？ | [personal/](personal/README.md) 集中放用户的交付报告、开发日志和学习笔记 |

## 在终端只定位所需章节

在 PromptLab 仓库根目录执行。例如要做 POC-02，先取两个文件中的章节位置，再读取相关行。

```sh
sed -n '1,25p' docs/validation/POC-02.md
rg -n '^### 浏览器凭据与渲染安全' docs/design/MODEL_INTEGRATION.md
rg -n '\bQ10\b' docs/DECISIONS.md
```

从定位到的标题读到下一个同级标题即可。需要历史背景时，再进审查、归档或个人笔记。任务完成后，把新证据写进对应的验证或任务文件，再更新总览中的状态。
