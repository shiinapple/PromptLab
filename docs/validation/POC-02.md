# POC-02：凭据与前端安全

[验证总览](../TESTING.md) · [凭据与渲染设计](../design/MODEL_INTEGRATION.md#浏览器凭据与渲染安全)

- 最小实验：同源业务 mock、一个受控跨源 mock、连接配置/切换和原文渲染；生产候选 CSP 在真实 HTTPS 站验证。
- 检查：用虚构 canary Key，检查平台请求、日志、导出、错误对象及非敏感持久化中没有该值；验证刷新、删除、退出、账号切换、不同标签页；地址更换须明确确认，redirect:error、credentials:omit 生效。
- 使用无害本地测试内容验证 HTML、Markdown 链接、图片和事件属性不执行脚本、不自动外连；检查 script-src/connect-src/object-src/frame-ancestors 等策略。对比仅允许已知来源与受限 HTTPS/loopback 方案。
- 通过：直连仅向选定目标发送凭据；默认无持久化 Key；正常模型/业务请求能执行；测试内容不能触发脚本/自动外连。注明 CSP 在允许任意 HTTPS 连接时不能阻止所有外传。
- 失败：默认纯文本渲染；缩小允许的地址范围或使用 D0；不要通过关闭安全策略解决。
