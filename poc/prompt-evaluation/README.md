# POC-03 提示词练习与题目质量

验证 DeepSeek Flash 在不同提示词和消息结构下的表现。题目、样本和判定规则见[实验任务书](../../docs/validation/POC-03.md)。

## 离线检查

从 PromptLab 仓库根目录开始。使用 Python 3.12，依赖安装在实验目录的 `.venv/`。

```sh
cd poc/prompt-evaluation
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python runner.py dry-run
```

`dry-run` 重建 `results/preflight/` 内的清单、完整请求矩阵、逐格状态和汇总。测试在进程内模拟 HTTP，预检与离线测试均产生零次真实推理。

## 已授权的真实运行

从 `poc/prompt-evaluation/` 执行。先核对官方价格、模型、Key 权限和余额。所有真实命令需显式加 `--allow-live`。在 macOS zsh 中可通过隐藏输入把 Key 放进当前 shell 环境变量。

```zsh
read -rs 'DEEPSEEK_API_KEY?DeepSeek Key: '
export DEEPSEEK_API_KEY
.venv/bin/python runner.py preflight
.venv/bin/python runner.py all --allow-live --run-id flash-first
unset DEEPSEEK_API_KEY
```

`all` 先跑 2 次 smoke，确认返回内容、结束状态及用量，再执行 108 个实验格子。也可用 `smoke`、`run` 分开执行并使用相同的 `--run-id`。中断后用 `resume --allow-live --run-id flash-first`，通过配置、源码和工具版本校验后只补未尝试的格子。已经记录开始的格子即使没有结果也不会自动重发，汇总标记为 `UNKNOWN`。`summary --run-id flash-first` 可从日志重新生成汇总，不发送请求。

只复核一次真实 smoke 时，使用新 run ID 并加 `--single-smoke`。它只发送 A/F01，遇到限流或服务端错误也不重试。Smoke 通过后，`all` 继续执行原定的 108 个格子。该 run 后续执行 `run`、`resume`、`summary` 时也使用 `--single-smoke`。`--key-stdin` 可从标准输入读取隐藏输入的凭据，输入内容只包含 Key 本体。

结果写到 Git 忽略的 `results/live/<run-id>/`。`attempts.jsonl` 记录发送前的预算预留及响应完成事件。逐格结果见 `cells.json`，分组、输入和批次汇总见 `summary.csv`，T1 通过率差见 `comparisons.json`。预算按峰时费率保守估算，实际用量缺失时费用留空。原始响应按文本处理。
