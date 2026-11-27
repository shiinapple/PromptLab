# 010 两个题目 GET 的 locale 校验依赖默认值绑定和题包查找

**状态** `FIXED`

## 现象

M5 版 API.md 第 10–11、96 行约定省略 `locale` 时用 `zh-CN`，其余值除 `zh-CN` 和 `en` 外返回 `400 INVALID_LOCALE`。当前两个 GET 在合法题目数据下对 `fr` 都返回该错误，但显式传入 `?locale=` 会按 `zh-CN` 返回 200。

## 证据

- [PracticeController.java 第 56–82 行](../../backend/src/main/java/cn/shiinapple/promptpractice/practice/PracticeController.java#L56)在两个方法上都用 `@RequestParam(defaultValue = "zh-CN")`。静态检查本地 Spring Web 7.0.11 的 `AbstractNamedValueMethodArgumentResolver` 字节码，确认缺参和空字符串都会取默认值。
- [PracticeController.java 第 168–171 行](../../backend/src/main/java/cn/shiinapple/promptpractice/practice/PracticeController.java#L168)的 `localized()` 用原样字符串查题包的翻译表，键不存在就抛出 `400 INVALID_LOCALE`，没有语言回退。题包只加载 [zh-CN 和 en](../../backend/src/main/java/cn/shiinapple/promptpractice/problem/ProblemCatalog.java#L113)。[ApiErrors.java 第 34–44 行](../../backend/src/main/java/cn/shiinapple/promptpractice/practice/ApiErrors.java#L34)把异常的状态和业务码写入响应。
- 列表在遍历题包时才查语言。[发布清单读取](../../backend/src/main/java/cn/shiinapple/promptpractice/problem/ProblemCatalog.java#L87)允许空对象，所以清单为空时，`locale=fr` 也会返回 `200 {"items":[]}`。当前清单有题，这是静态分析的边界条件。详情先解析版本和读取题包，随后查语言，叠加未知题目或非法版本时可能先返回相应的错误。
- [PracticeIntegrationTest.java 第 72–79、105–113 行](../../backend/src/test/java/cn/shiinapple/promptpractice/practice/PracticeIntegrationTest.java#L72)覆盖了英文标题和省略参数的请求，缺少两个端点对非法语言值的断言。

## 期望

按 M5 文档字面约定，只有缺参时使用中文。两个端点在题包投影前校验显式提供的语言，空字符串和不受支持的语言均返回 `400 INVALID_LOCALE`，包括发布清单为空时。需要后端调整，并用集成测试同时断言 HTTP 状态与业务码。组合多个无效参数时的错误优先级可在契约中明确。

## 修复验证（2026-11-27）

两个 GET 在访问题库前校验 `locale`。省略参数使用 `zh-CN`，显式空值和非法值返回 `400 INVALID_LOCALE`。列表返回空题库时也执行相同的校验。

在 `backend/` 使用 JDK 21 执行 `./mvnw -B -ntp clean verify`。允许本地回环连接后重跑，退出码 `0`，全量 134 项，失败 0、错误 0、跳过 0。`PracticeIntegrationTest` 为 12 项，全部通过，覆盖两个端点的空值和非法值、默认中文、英文和空题库列表。首次沙箱运行退出码 `1`，134 项中 37 项报测试环境错误（`java.net.SocketException: Operation not permitted`、Mockito 无法附加 JVM）。
