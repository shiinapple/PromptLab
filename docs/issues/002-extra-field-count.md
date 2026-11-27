# 002 额外字段检查只比较数量

**位置**：`backend/src/main/java/cn/shiinapple/promptpractice/judge/JudgeService.java` 的 `validate`。判断条件是 `value.size() != properties.size()`，并且只递归检查 `required` 里的字段。

**实测**：直接调用 `JudgeService`，Schema 声明 `a`（必填，integer）和 `b`（可选，boolean）。

| 回答 | 现在 | 应该 |
|---|---|---|
| `{"a":1}` | FORMAT_ERROR / EXTRA_FIELD | PASSED |
| `{"a":1,"b":"not-bool"}` | PASSED | FORMAT_ERROR / TYPE |
| `{"a":1,"zzz":2}` | PASSED | FORMAT_ERROR / EXTRA_FIELD |

**影响**：加载器目前不允许可选字段，现有 002 题不受影响。只放开加载器而不改这里，就会把错误回答判为通过。

**期望**：先逐个检查 `properties`（必填项缺失报 `REQUIRED`，出现了就校验类型），再检查是否有未声明的字段名。上表三行进正式测试。
