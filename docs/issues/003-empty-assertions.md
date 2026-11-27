# 003 只判结构的题无法加载

**位置**：`backend/src/main/java/cn/shiinapple/promptpractice/problem/ProblemCatalog.java` 的 `validateExpectations` 要求 `assertions` 非空。

**实测**：`"assertions": []` 报 `Missing case assertions`。

**期望**：允许显式的空数组，字段本身仍然必填。空数组表示只判 JSON 与结构：判题结果只会是 `PASSED` 或 `FORMAT_ERROR`。页面需要据此换通过文案，见 [API 契约](../design/API.md#m3-接口变更待实现)的 `grading`。
