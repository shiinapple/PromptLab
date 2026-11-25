# Prompt Practice 项目状态

## 当前优先任务

**POC-02 本机阶段待开始。** 用户确认继续后，从 [POC-02 验证标准](validation/POC-02.md)和[凭据与渲染设计](design/MODEL_INTEGRATION.md#浏览器凭据与渲染安全)入手。

用虚构 Key 和受控 Mock 验证模型与平台请求的边界、Key 清理、地址切换、原文渲染和 CSP。优先检查本机可复现的行为，真实 HTTPS 的验证结果另列。证据写入对应的验证文件，同步 [TESTING 总览](TESTING.md) 的状态，再交付独立报告。

## 待恢复

**POC-01 真实 HTTPS 阶段待环境和授权。** [本机 Mock 已通过](validation/POC-01.md#poc-01-执行记录)。取得测试站 Origin、首个网关的 Base URL 与版本、目标浏览器及短请求授权后，从真实站点验证 CORS、本地网络限制、模型列表、普通响应、SSE 和取消。[POC-01 验证标准](validation/POC-01.md)

其他任务或术语通过 [文档索引](INDEX.md)定位。
