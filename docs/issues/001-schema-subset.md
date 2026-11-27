# 001 Schema 子集表达不了字符串和可选字段

**现象**：001 题要求 `name`、`reply` 为非空字符串，`in_character` 可选且类型不限。这两点都无法表达。

**位置**：`backend/src/main/java/cn/shiinapple/promptpractice/problem/ProblemCatalog.java` 的 `validateSchema`；`backend/src/main/java/cn/shiinapple/promptpractice/judge/JudgeService.java` 的 `validate`。

- 类型只支持 `object`、`array`、`integer`、`boolean`。
- `required` 必须等于全部 `properties`。
- 不接受 `{}`。

**实测**：可选字段报 `Required fields must match declared properties`；字符串报 `/name Unsupported schema type`。

**期望**：按[题包子集契约](../design/PROBLEMS.md#m3-题包子集待实现)扩展。必须和 [002](002-extra-field-count.md) 一起改。
