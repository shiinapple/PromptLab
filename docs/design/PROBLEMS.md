# 样题与题库维护

> 三道样题为设计示例，视觉资产尚未制作；正式收录待确认。

## 5.2 样题 001：月港酒馆的守夜人 / The Night Watcher of Moon Harbor

**分类：** 酒馆/角色扮演；难度入门；能力 text。
**题目目标：** 编写角色 Prompt，使模型以酒馆守夜人「艾琳 / Eileen」的身份回应旅人的问题，并返回约定的 JSON 对象。人设表现是练习目的，不是自动判定能充分验证的事实。

题目人设（固定说明）：艾琳在月港经营一家深夜酒馆，外表冷淡、说话克制，但愿意为迷路的旅人提供帮助；不知道的事情不编造。英文题目说明提供同等要求；此样例角色输出语言可由用户选择，`name` 不强制等于特定语言写法。

**初始模板与编辑规则：**

| 消息 | 初始内容 | 可编辑性 |
|---|---|---|
| system | `你是艾琳，月港酒馆的守夜人。请以角色身份回答旅人的问题。` | 用户编辑（主编辑区） |
| user | `{{question}}` | 题目固定，不可修改 |
| 历史消息 | 无 | 不显示 |

Case 1 固定输入：`外面下了很大的雨，我可以在这里待到天亮吗？`
英文变体可作为独立公开 case：`It's pouring outside. May I stay here until dawn?`

用户需要自行在 system Prompt 中加入输出契约、语气要求等，不由平台偷偷追加。例如改为「你是艾琳……只输出一个 JSON 对象，name 和 reply 为非空字符串；可以给出自评 in_character」。

**预期结果示例（非唯一正确文本）：**

```json
{
  "name": "艾琳",
  "reply": "门没锁。去炉火旁坐着吧，等雨停了再走。",
  "in_character": true
}
```

**Schema（设计示例）：**

```json
{
  "type": "object",
  "required": ["name", "reply"],
  "properties": {
    "name": {"type": "string", "minLength": 1},
    "reply": {"type": "string", "minLength": 1},
    "in_character": {}
  },
  "additionalProperties": false
}
```

自动判定规则：整段 JSON 对象合法；`name`、`reply` 必须为非空、非纯空白字符串；`in_character` 是可选参考字段，不验证其真伪、布尔类型或是否存在（展示时可说明建议填写布尔值）；额外未知字段违反此题明确约定。`name` 不做与「艾琳」字面相等的答案检查，`reply` 不对人设、语义或内容质量打分。通过文案必须是「JSON 格式通过；人设表现未自动评价」，不是「角色扮演正确」。`{"name":"艾琳","reply":"...","in_character":false}` 也可通过结构判定。

**页面操作路径：** 左侧先看到人设、固定旅人问题和字段说明；右侧只展示一个可编辑 system 区，固定 user 消息显示为只读或折叠预览。选择模型，点击运行测试，下方看原始回复及两个格式字段的检查；参考字段单独展示。修改 system Prompt 后重跑，确认喜欢的版本再提交。后续多轮角色题可在同一 messages 配置内增加固定 user/assistant 历史，不需要新页面。

失败示例：模型回复 `好的，你可以留下。` → JSON 格式错误；`{"name":"艾琳"}` → 缺少 `/reply`；`{"name":"艾琳","reply":123}` → `/reply` 类型错误；缺少 `in_character` 不影响结果。

## 5.3 样题 002：需求转测试用例 / Requirements to Test Cases

**分类：** 开发 Prompt；难度入门；能力 text。
**题目目标：** 把自然语言需求转成固定 JSON 结构的边界测试用例，不依赖代码运行。为了可确定性判题，明确测试覆盖要求和判定标准。

**固定测试输入 Case 1：** `密码长度必须为 6 至 12，包含边界；仅检查长度，不检查字符种类。请覆盖边界前、边界及边界后：5、6、7、11、12、13。` 英文版为等价需求，必要时使用独立 caseId。输出固定为 `cases` 数组；每条包含整数 `length` 和布尔 `expectedValid`；每个指定长度恰好出现一次，不要求数组顺序。测试数据长度本身作为抽象输入，不要求模型生成真实密码字符串。

**初始模板（用户可编辑的单一 user slot）：**

```text
根据以下需求，生成测试用例：
{{requirement}}
```

`requirement` 由样例固定注入，必须保留占位符；允许用户编辑其周围全部指令。用户可自行加入「只返回 JSON 对象；cases 为数组；每项包含…」等具体约束。系统消息无，历史无。

**预期结果（顺序可变）：**

```json
{
  "cases": [
    {"length": 5, "expectedValid": false},
    {"length": 6, "expectedValid": true},
    {"length": 7, "expectedValid": true},
    {"length": 11, "expectedValid": true},
    {"length": 12, "expectedValid": true},
    {"length": 13, "expectedValid": false}
  ]
}
```

**Schema（设计示例）：**

```json
{
  "type": "object",
  "required": ["cases"],
  "additionalProperties": false,
  "properties": {
    "cases": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["length", "expectedValid"],
        "additionalProperties": false,
        "properties": {
          "length": {"type": "integer"},
          "expectedValid": {"type": "boolean"}
        }
      }
    }
  }
}
```

业务规则：数组恰好 6 项；按 length 唯一，长度集合精确等于 `{5,6,7,11,12,13}`；对每项检查 `expectedValid == (6 <= length <= 12)`。允许顺序变化，但重复长度、缺少边界、附加未知长度或任何布尔值错误均不通过。另一个公开 Case 2 可把规则改为 3 至 8（包含边界），要求 2、3、4、7、8、9，预期分别 false、true、true、true、true、false；由题目配置分别绑定变量与断言。题库不把 Case 2 自动提交给模型。

**反馈示例：** 如果返回 `length: 5, expectedValid: true`，显示「长度 5 应为 false，实际为 true」；若缺少长度 13，显示「缺少必需边界用例 13」；若 `expectedValid` 为字符串，显示 Schema 类型错误。输出是含 Markdown 代码围栏的完整正文时直接格式错误。全部强制规则正确才通过。

**页面操作路径：** 左侧看需求和六个边界值、固定字段说明；右侧只有一个 Prompt 编辑区，变量 chip 表示 `requirement`，请求预览确认变量已被真实替换；运行测试，在下方查看原文和逐边界检查。切换 Case 2 时保留同一 Prompt 草稿，但运行创建新快照和新 caseId。结果与对应样例绑定，不能把 Case 1 的通过显示成 Case 2 的通过。

## 5.4 样题 003：票据字段提取 / Receipt Field Extraction

**分类：** Agent/业务任务；难度基础；能力 text + image。首版定位为视觉结构化提取，并非已具备实际业务工具调用的 Agent。

**题目目标：** 根据指定票据图片提取商户名称、票据编号、日期、含税总金额和币种，返回严格 JSON。避免从 Prompt 或辅助说明直接泄露整张票据文本；图片是事实来源。

**固定样例资产规范（待正式制图，并非真实票据扫描件）：** 由出题者制作一张清晰、虚构、无个人信息的 PNG/JPEG，文件 `receipt-001-v1.png`。图上应清晰印有如下内容（这是出题和验收说明，正式用户页面仅展示图片和字段要求，不将此文本作为隐藏模型输入）。可保留预期示例用于教学，若想让模型真正从图提取，不要把答案放入发送的 messages。

```text
        MOON HARBOR CAFE
        月港咖啡
        收据 / RECEIPT
编号 / No.          MH-20261125-001
日期 / Date         2026-11-25
拿铁 / Latte                 28.00
蛋糕 / Cake                  40.50
实付合计 / TOTAL         CNY 68.50
```

字段约定：`merchant` 取英文商户名（大小写按票面）；`receiptNo` 保留原编号；`date` 格式 YYYY-MM-DD；`total` 是两位小数的字符串（不含货币符号）；`currency` 为三字母 ISO 货币代码。金额用字符串是本题显式决定，避免 JSON 数字 68.5 与 68.50 在序列化后丢失显示精度。不要猜缺失字段；此样例所有必需信息均清楚显示。

**初始模板与编辑区：** 一个可编辑 user slot：`请读取图片中的票据并提取所需字段。` 固定消息由题目组装：同一个 user message 的 text part 来自编辑区，image part 来自样例 assetId；用户不能删除或替换图片。高级题可开放用户上传图片作为不计入正式用例的练习模式，但用户上传任意票据没有已知预期答案，首版不做自动判定该类开放输入。

**预期输出：**

```json
{
  "merchant": "MOON HARBOR CAFE",
  "receiptNo": "MH-20261125-001",
  "date": "2026-11-25",
  "total": "68.50",
  "currency": "CNY"
}
```

**Schema（设计示例）：**

```json
{
  "type": "object",
  "required": ["merchant", "receiptNo", "date", "total", "currency"],
  "additionalProperties": false,
  "properties": {
    "merchant": {"type": "string", "minLength": 1},
    "receiptNo": {"type": "string", "minLength": 1},
    "date": {"type": "string", "pattern": "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
    "total": {"type": "string", "pattern": "^[0-9]+\\.[0-9]{2}$"},
    "currency": {"type": "string", "pattern": "^[A-Z]{3}$"}
  }
}
```

判定规则：结构和类型正确后按样例预期逐字段精确比较；日期还可由严格 LocalDate 解析验证有效日历日期，货币限定为本例 CNY。`"68.5"` 为格式错误，`"68.00"` 为答案错误；只输出拿铁单价 28.00 则提示合计字段实际值与预期差异；日期与票面不符时指出 `/date`。允许字段顺序变化，不进行 OCR 容错或擅自归一化商户名，若未来想接受别名应由题目定义。题目编辑器允许用户明确格式和选择英文商户名，不帮用户生成答案。

**实际 Chat Completions 消息结构（图片内容为示意占位符，非可调用 payload）：**

```json
[
  {"role": "user", "content": [
    {"type": "text", "text": "请读取票据，按字段要求仅输出JSON。"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,<由受信题图生成>"}}
  ]}
]
```

**页面操作路径：** 左侧先看到票据缩略图（可放大）和字段约束；右侧编辑一个用户 Prompt；顶部模型选择器显示视觉能力。当前连接若明确不支持图片，提示切换视觉模型或使用能够接收图片的外部工具；能力未知时提醒先验证。点击运行，结果区先流式显示正文，正常完成后列出五个字段的规则结果。D0 导出可包含图片 data URL 或由本地资产生成完整 request JSON，手动聊天工具则由用户单独下载/上传同一张图。

### 视觉提取与真实 Agent 的边界

| 能力 | 仅视觉结构化提取（验证通过后发布） | 实际工具调用（后续） |
|---|---|---|
| 模型要求 | 接口支持图片 content parts 和文本输出 | 支持 tool/function calling 或相应 Agent 协议；若还要图则同时支持视觉 |
| 平台要求 | 图片资产加载、大小校验、普通模型调用、JSON 判题 | 工具声明与参数 Schema、tool_call 解析、白名单分发、工具结果回填、循环上限、超时、权限与审计 |
| 业务执行 | 无；只提取 JSON | 例如查询虚拟账本、创建报销草稿；首选沙箱/确定性 mock，真实写操作须单独授权和确认 |
| 评价证据 | 完整正文与固定票面事实 | 最终 JSON 之外，还应验证服务端观察到的调用轨迹、参数和模拟状态，不能信任模型自报「已完成」 |

首版不让模型读取真实财务系统、执行付款、访问任意 MCP Server 或执行用户提供的代码。后续先做固定只读工具或 mock 业务工具，再考虑真实连接器和有副作用的操作。样题保留 Agent/业务分类是业务语境，不宣称模型已执行报销流程。

## 5.5 题库维护与验收样例

题目作者新增题目时提交版本化配置、zh/en 描述、默认 slot、固定消息、公开 case 变量/题图、Schema、规则及至少一个应通过和若干应失败的输出 fixture。启动或 CI 进行 lint：ID 唯一、版本不可覆盖、翻译文件存在、变量可解析、固定角色顺序合法、图片存在且符合限制、规则类型和路径有效、预期答案通过自身 Schema、参考字段不计分。禁止 YAML 任意类型反序列化、远程 Schema 和动态脚本。旧版本题目继续可用于查看历史；新运行默认最新发布版本，草稿若版本不一致提示迁移/复制而非覆盖。

用户后续提供 Claude 讲座原题后，先用同一规范描述其输入、可编辑 Prompt、判定范围，检查是否能无需新增专用页面和任意代码判题完成。如果无法确定性判题，明确属于不适合首版的题，而非强行引入 AI 评审。题目正式收录、版权和翻译由项目所有者确认；这里的三道题仅供方案演示。

## 正式题目包与版本契约

架构评审建议。第一份正式资源在 M1 以样题 002、一个公开用例和判题 fixture 落地。现有两份候选教学笔记继续独立保留，正式打包只读取发布清单。

```text
content/problems/
  releases.json                 # 题目 ID 到当前发布版本
  002/v1/
    problem.json                # 槽定义、默认值、固定消息、版本、参数白名单
    i18n/zh-CN.json
    i18n/en.json
    schema.json                 # 输出结构，固定 2020-12 候选
    cases/case-1.json           # 公开输入、可选资产引用、期望和业务断言
    fixtures/case-1.json        # 原文与期望 verdict、检查 ID
    assets/                     # 需要时添加受信资产
```

- 每个 case 包含稳定 `caseId`、固定输入或变量映射、期望值/合法集合、白名单断言及相关教学标签。多个用例在不同运行中执行。示范答案和 judge fixture 分开；fixture 可包含错误输出及原文边界，不作为用户运行输入。
- 消息优先由有序、显式的 fixedText、caseVariable、slot 引用组成。需要行内嵌入时使用统一的单次替换语义，字面括号、引号、换行与缺失变量由共享 golden fixture 锁定。内容注入后不重复解析成模板。Java 返回实际快照，TS 只负责编辑预览和协议适配。
- 同一版本可有两种展示文案；输入不同则建独立 caseId 并标记输入语言。发布后题面、输入、模板、规则、预期答案或资产变化都创建新版本，旧版本随发行保留；翻译修订也维护明确的版本记录。摘要基于固定文件顺序和相对路径/文件字节计算，重建必须可复现。
- 构建时校验清单与资源路径、ID、双语资源、用例引用、未解析变量、角色顺序、规则操作、Schema 注册和 fixture 期望。后端校验运行用例、双语内容中实际使用的输入和资源摘要。浏览器只得到展示和组装需要的字段，所有判题规则由服务器选择。
- 002 先验证 JSON 结构及边界值映射；001 验证通用题库页面及参考字段；003 需要图文适配和真实模型验证。通过率按公开用例统计，单次通过不代表整题或 Prompt 通用性。[POC-03 教学转化](../validation/POC-03.md#归档与教学转化) · [POC-07](../validation/POC-07.md)
