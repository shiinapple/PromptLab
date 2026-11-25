# Prompt Practice

面向 Prompt 初学者和开发者的中英文练习平台。用户自带模型；浏览器直连（P0）为主，D0 手动执行与导入为兜底，Java 后端负责严格 JSON 判题及学习记录。目标部署为 2 核 CPU / 2 GB RAM / 40 GB 系统盘。

当前状态：**正式工程尚未初始化。** POC-01 已完成本机浏览器 Mock 验证，真实 HTTPS 阶段待目标环境与授权。见 [POC-01 交付报告](docs/reports/POC-01.md)和[复跑方式](poc/browser-direct/README.md)。

## 文档入口

| 文件 | 权威职责 |
|---|---|
| [PRODUCT](docs/PRODUCT.md) | 产品范围、MVP 与非目标 |
| [MODEL_INTEGRATION](docs/MODEL_INTEGRATION.md) | 直连、D0、P1/P2、凭据和安全 |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | 模块、数据、API、部署 |
| [UI](docs/UI.md) | 页面、编辑器、消息组装和运行交互 |
| [JUDGE](docs/JUDGE.md) | 严格 JSON、Schema、业务规则与反馈 |
| [PROBLEMS](docs/PROBLEMS.md) | 三道样题、题库作者规范 |
| [DECISIONS](docs/DECISIONS.md) | 决策状态与确认门槛 |
| [REVIEW](docs/REVIEW.md) | 审查发现、建议修订和风险 |
| [TESTING](docs/TESTING.md) | PoC、测试矩阵、验收 |
| [ROADMAP](docs/ROADMAP.md) | 里程碑、依赖、发布门槛 |
| [TASKS](docs/tasks/README.md) | 首批任务与完成标准 |
| [REFERENCES](docs/REFERENCES.md) | 原稿引用与复用边界 |
| [AGENTS](AGENTS.md) | 开发 Agent 工作约束 |
| [NEXT_CODEX](docs/NEXT_CODEX.md) | 可复制的下一条执行指令 |

按 ROADMAP 执行 PoC，并在相关任务前确认 DECISIONS 中所需的待定项。原稿中的设计示例、候选依赖及未验证兼容性不能视为完成。工作目录已有 README 或 AGENTS 时应人工合并，不直接覆盖。

## 设计归档

| 版本 | 内容 |
|---|---|
| [v0.2](docs/archive/DESIGN-v0.2.md) | 浏览器直连与 D0 手动运行的修订合稿 |
| [v0.1](docs/archive/DESIGN-v0.1.md) | 最初的产品与技术方案评审稿 |
