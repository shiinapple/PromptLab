# 008 每题只能有一个用例

**实测**：两个 `caseIds` 报 `Expected one public case`。

读代码所得：`ProblemPack` 只有一个 `caseId` 和一份 fixture；启动时 `PracticeRuns.run` 只登记一个用例；`submit` 用 `pack.caseId()` 做相等判断；页面固定取 `cases[0]`。数据库的 `problem_cases` 表本来支持多行，不需要新迁移。

**发布约束**：数据库保存每个已发布版本的内容摘要，启动时发现同一版本内容变化会直接失败。给 002 增加 Case 2 必须新建 `002/v2`，`v1` 一个字节都不能改。加载器重构也不能改变参与摘要的文件集合。

002 v1 的当前摘要（按 `ProblemCatalog.digest` 的算法重算，与本地数据库记录一致）：

```text
5cfa92e677ba4b90b12493b5a3c920c578b2b116521070721baf604d369f23c8
```
