# Prompt Practice

面向 Prompt 初学者和开发者的中英文练习平台。用户自带模型；浏览器直连（P0）为主，D0 手动执行与导入为兜底，Java 后端负责严格 JSON 判题及学习记录。目标部署为 2 核 CPU / 2 GB RAM / 40 GB 系统盘。

## 本地开发

准备 JDK 21 和 Node.js 20.19+（20.x）或 22.12+。首次运行 Maven Wrapper 和 `npm ci` 需要访问 Maven Central 和 npm Registry。以下两组命令分别从仓库根目录执行。

### 终端一 · Spring Boot

macOS / Linux（Linux 上先将 `JAVA_HOME` 指向已安装的 JDK 21）：

```sh
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-practice
```

如果 macOS 默认 Java 版本不同，先运行 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`。

Windows PowerShell（先配置 JDK 21 的 `JAVA_HOME`）：

```powershell
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local-practice'
```

### 终端二 · Vue

```sh
cd frontend
npm ci
npm run dev
```

打开 Vite 打印的地址（默认 `http://127.0.0.1:5173/`）。在样题 002 编辑 Prompt、创建运行，再复制或下载请求。可以用自己的模型工具执行，也可以将[样例中的 `rawOutput`](content/problems/002/v1/fixtures/case-1.json)解码后粘贴到页面验证判题。导入前核对页面上的用例和运行编号。

也可打开「模型设置」，填写实际 API 前缀和 Model ID，按需输入 API Key。保存后返回练习，选择「浏览器直连」和「运行并判题」。设置中可切换普通或流式响应。连接失败时可切回「手动运行」，继续使用当前 Prompt。直连发给所填服务，测试连接及推理均可能使用该服务的额度。

在同一个地址访问 `/api/health`，预期返回 HTTP 200 和 `{"status":"UP"}`。访问 `/api/missing` 验证 HTTP 404 和 `application/problem+json` 响应。

| 配置 | 默认 | 本地调整方式 |
|---|---|---|
| 后端监听端口及 Vite 代理目标端口 | `18080` | 在两个终端设置相同的 `PROMPT_PRACTICE_API_PORT` |
| SQLite 文件路径 | 从后端工作目录解析 `./data/prompt-practice.sqlite3` | 在后端终端设置 `PROMPT_PRACTICE_DB_PATH`。启动时创建父目录并执行 Flyway 迁移 |

`local-practice` 显式启用本机练习主体和 D0 API，仅在本地开发时启用。后端与 Vite 默认都绑定 `127.0.0.1`。一个库文件由一个应用实例负责启动迁移。SQLite 文件及 WAL、SHM 会留在本地数据目录。

### 构建与测试

- 在 `backend/` 运行 `./mvnw clean verify`（Windows 使用 `.\mvnw.cmd clean verify`）。集成测试使用临时 SQLite 文件，可执行 JAR 输出到 `backend/target/`。
- 在 `frontend/` 运行 `npm ci`、`npm run typecheck` 和 `npm run build`。使用 Node.js 22.12+ 时可运行 `npm test`。前端静态产物输出到 `frontend/dist/`。
- 浏览器验收使用 Node.js 22.12+、Chrome 和已打包的前后端。在 `frontend/` 运行 `npm run test:e2e`。测试会启动独立临时数据库、本机端口和跨源模型 Mock，覆盖普通直连、SSE、文件和粘贴的统一判题。自定义 Chrome 路径用 `CHROME_BIN`。

## 文档入口

- [文档](docs/README.md)按目的和主题查找。
- [项目状态](docs/STATUS.md)记录当前优先任务。
- [项目协作约定](AGENTS.md)说明实施和交付要求。
