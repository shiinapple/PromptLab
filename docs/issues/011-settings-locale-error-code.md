# 011 PATCH settings 的非法 locale 可能返回通用输入错误

**状态** `FIXED`

## 现象

M5 版 API.md 第 96 行将 PATCH 的 `locale` 只要不是 `zh-CN` 或 `en` 都归为 `400 INVALID_LOCALE`。当前合法 JSON 对象里传入 `"locale":"fr"` 或空字符串符合约定，传入数字、布尔值、对象、数组或显式 `null` 会返回 `400 INVALID_REQUEST`。超过 16 个 UTF-8 字节的语言字符串会返回 `413 PAYLOAD_TOO_LARGE`。

## 证据

- [WorkspaceController.java 第 38–46 行](../../backend/src/main/java/cn/shiinapple/promptpractice/workspace/WorkspaceController.java#L38)先用 `ApiInput.string(..., 16)` 读取字段，再检查枚举。[ApiInput.java 第 41–48 行](../../backend/src/main/java/cn/shiinapple/promptpractice/practice/ApiInput.java#L41)先检查 JSON 类型与长度，因此相关输入在枚举校验前已被拒绝。
- [WorkspaceStore.java 第 29–45 行](../../backend/src/main/java/cn/shiinapple/promptpractice/workspace/WorkspaceStore.java#L29)用 SQL 持久化。省略字段时，首次写入默认 `zh-CN`，已有记录保留原语言。[V4 迁移第 3 行](../../backend/src/main/resources/db/migration/V4__settings_and_drafts.sql#L3)的 CHECK 约束只允许两种值。
- [M4IntegrationTest.java 第 130–146 行](../../backend/src/test/java/cn/shiinapple/promptpractice/auth/M4IntegrationTest.java#L130)对 `fr` 只断言 HTTP 400，未断言 `code`，也未覆盖上述类型和长度分支。

## 期望

如果采用 M5 错误表当前的统一语义，需要调整 PATCH 的字段校验，使已解析请求对象中的非法 `locale` 返回 `400 INVALID_LOCALE`，并保留整个请求的体积限制。若保留现有的类型、长度错误分类，则由契约写明各分支。

## 修复验证（2026-11-27）

PATCH 对空字符串、非法语言、非字符串和显式 `null` 返回 `400 INVALID_LOCALE`。请求体超过 2048 字节或 `locale` 字符串超过 16 个 UTF-8 字节仍返回 `413 PAYLOAD_TOO_LARGE`。设置读写、偏好合并和省略 `locale` 的行为由既有集成测试覆盖。

在 `backend/` 使用 JDK 21 执行 `./mvnw -B -ntp clean verify`。允许本地回环连接后重跑，退出码 `0`，全量 134 项，失败 0、错误 0、跳过 0。`M4IntegrationTest` 为 20 项，全部通过。首次沙箱运行退出码 `1`，134 项中 37 项报测试环境错误（`java.net.SocketException: Operation not permitted`、Mockito 无法附加 JVM）。
