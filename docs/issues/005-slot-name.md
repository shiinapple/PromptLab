# 005 编辑槽名写死为 `user`

001 编辑的是 `system` 槽。以下位置只认 `user`（读代码所得）：

| 位置 | 写法 | 负责 |
|---|---|---|
| `PracticeController.create` | `fields(inputSlots, Set.of("user"))` | Codex |
| `frontend/src/practice/api.ts` `createRun` | `slots: { user }` | Claude |
| `frontend/src/practice/usePractice.ts`、`app/App.vue` | `defaultSlots.user` | Claude |
| `frontend/src/practice/labels.ts` `previewError` | 提示固定写 `{{requirement}}` | Claude |

题目面板还固定显示 `variables.requirement`（`ProblemPanel.vue`）。

**进度**：表中三处前端写法和题目面板已改（2026-11-27），`PracticeController` 待改。

**期望**：槽名取自题目数据，规则见 [API 契约](../design/API.md#m3-接口变更待实现)。前端部分不依赖后端改动。
