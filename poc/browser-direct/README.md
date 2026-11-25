# POC-01 本机浏览器直连

PoC 启动两个随机端口，页面和模型 Mock 均绑定到 `127.0.0.1`。Chrome 原生 Fetch 对跨源请求执行 OPTIONS 预检。Mock 验证固定的请求内容，并生成普通 JSON、SSE 或指定故障。页面只展示测试结果。

## 运行

在 PromptLab 仓库根目录运行以下命令。需要 Node.js 18+、npm 和本机 Chrome。实际验证版本见 [执行记录](../../docs/TESTING.md#poc-01-执行记录)。

```sh
npm --prefix poc/browser-direct ci --ignore-scripts --no-audit --no-fund
npm --prefix poc/browser-direct test
```

macOS 默认查找 `/Applications/Google Chrome.app`，其他平台查找 PATH 中的 `google-chrome`。可用 `POC_CHROME` 指定 Chrome 可执行文件。测试启动临时 Chrome 配置目录，结束后清理。命令在有权限绑定本机 TCP 端口的终端执行。

想手动查看时运行 `npm --prefix poc/browser-direct start`，在浏览器打开终端输出的 **Page** 地址，点击「运行测试」。运行结束后在终端按 Ctrl+C 停止。

## 实验约定

- 普通 JSON 接受第一条 choice 的非空 assistant 文本及 `finish_reason: "stop"`。SSE 候选规则要求先收到 `stop`，再收到完整的 `[DONE]` 事件。真实网关的结束方式待逐一验证。
- Fetch 层用 `redirect: 'error'` 和 `credentials: 'omit'`，Authorization 仅含固定的虚构 Key。页面与 Mock 分端口，Mock 仅允许实验页 Origin。
- 响应按实际读取的字节累计。SSE 单事件上限在解析到完整事件时检查，未结束事件受整个响应的字节上限约束。测试缩短部分时间和字节阈值以触发故障。
- 取消、超时、断流等只返回失败状态及已收到的片段。浏览器收到的不透明 Fetch 或流读取错误使用通用错误码，定位时结合预检和 Mock 计数。
