# 006 题号写死为 002

读代码所得：

- `PracticeRuns.PROBLEM_ID = "002"`：启动登记和创建运行都只认它。
- `PracticeController`：题目列表只返回它，`GET /api/problems/{id}` 对其他题号直接返回 404。
- `frontend/src/practice/api.ts` 的 `readProblem` 固定请求 `/api/problems/002`，页面没有题目切换。

**期望**：题目范围以 `content/problems/releases.json` 为准。接口见 [API 契约](../design/API.md#m3-接口变更待实现)。
