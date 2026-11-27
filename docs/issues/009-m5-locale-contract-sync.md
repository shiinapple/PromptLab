# 009 M5 locale 契约尚未同步到当前工作树

**状态** `FIXED`（M5 工作树）

## 现象

`codex/m4`（`8b3e9fa`）中的 API 文档仍是 M5 登记前的版本。M5 的登记位于 `claude/m5-codex-prompts-851505` 工作树中的未提交改动。

## 证据

- [API.md 第 10–11 行](../design/API.md#L10)的列表行未写本地化，详情行仅说明按两种语言返回展示文本。M5 版本同两行已登记可选参数、默认值和错误码，并注明列表标题按语言返回、详情响应含 `locale`。M5 第 10 行的「同上」指代不清，取值实际写在下一行。
- [API.md 第 18、28 行](../design/API.md#L18)说明设置读写、首次读取默认值和偏好合并。两份文档的设置端点行均未直接写明 PATCH 的非法值响应。M5 版本用第 96 行的错误表覆盖 PATCH。
- [API.md 第 96 行](../design/API.md#L96)的 400 行缺 `INVALID_LOCALE`。M5 版本同一行已把两个 GET 和 PATCH 纳入该错误码的适用范围。
- 当前 [M5.md 第 35 行](../tasks/M5.md#L35)把两个 GET 的非法值写为 `400 INVALID_REQUEST`。M5 工作树同一行已改为 `400 INVALID_LOCALE`。

## 期望

交付 M5 时将已登记的端点说明和错误表一并纳入目标分支。列表端点明确写出两种允许值，设置端点可直接定位到 PATCH 的错误定义。文档同步本身无需改后端。


## 修复验证（2026-11-27）

- M5 分支的 `b6a6145` 已把题库列表、题目详情和设置接口的 locale 约定及错误码提交到 [API.md](../design/API.md#端点)，并同步 [M5-01](../tasks/M5.md#m5-01双语机制语言切换与偏好同步)。列表行的「同上」指向同表紧邻的详情行，取值由详情行的 `zh-CN` / `en` 及错误表明确。
- `55697af` 提供后端校验，细节见 [010](010-problem-query-locale-validation.md) 与 [011](011-settings-locale-error-code.md)。
- 本机 JDK 21 下运行 `npm run test:m5` 的 A1，使用真实本地账号和打包后端。两个题目 GET 对空值和 `fr` 返回 `400 INVALID_LOCALE`，PATCH settings 对空值、`fr` 和数字返回同一状态码和业务码。页面切换时题目和题库列表更新，刷新后从 GET settings 获取已保存的语言。
- 以上结论适用于 M5 分支。契约提交进入其他分支有待所有者安排合并。
