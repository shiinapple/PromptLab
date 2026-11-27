# 004 用例变量只从可编辑槽收集，消息结构未校验

**位置**：`backend/src/main/java/cn/shiinapple/promptpractice/problem/ProblemCatalog.java` 的 `validateTemplates`。

用例变量必须正好等于各槽 `requiredVariables` 的并集。001 的固定 user 消息用 `{"type":"caseVariable","name":"question"}` 引用变量，可编辑的 system 槽不需要它，题包因此被拒绝。

**实测**：按设计编写的 001 题包报 `Case variables do not match required variables []`。让 system 槽强制引用 `{{question}}` 可以绕过，但这改变了题目设计。

**同一处缺口**（读代码所得）：加载时不检查 `messages`。未知的片段类型要到创建运行、`MessageAssembler` 抛错时才暴露，并返回成 `INVALID_SLOT`；未知角色不会被发现。

**期望**：按[题包子集契约](../design/PROBLEMS.md#m3-题包子集待实现)校验变量并集和消息结构。
