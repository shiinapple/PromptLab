# Git 分支与工作树

本项目里 Claude 和 Codex 分工：Claude 负责 `frontend/` 和 `docs/`，Codex 负责后端。按 [AGENTS.md](../../AGENTS.md)，两者不同时改同一份工作区。工作树（worktree）就是为这件事准备的：同一个仓库开几个目录，每个目录检出各自的分支，互不干扰。

以下命令均在仓库根目录 `PromptLab` 执行，macOS 终端即可。

## 先分清三个概念

| 概念 | 是什么 | 例子 |
|---|---|---|
| 仓库 | 所有历史、分支、stash 都存在 `.git` 里，只有一份 | `PromptLab/.git` |
| 分支 | 仓库里的一条提交线 | `codex/m4`、`master` |
| 工作树 | 磁盘上的一个目录，加上它当前检出的分支 | `PromptLab/` 在 `codex/m4` |

普通 clone 只有一个工作树。`git worktree add` 是在同一个仓库上再开一个目录：

```text
~/code/personal/PromptLab/        工作树 A，检出 codex/m4
~/code/personal/PromptLab-m5-fe/  工作树 B，检出 claude/m5-fe
            │
            └── 共用同一个 .git：历史、分支、stash 都是共享的
```

所以两个目录之间可以互相看到分支和提交，但看不到对方**未提交**的文件改动。

## 什么时候用

- **不想 stash 就切换**：改到一半要看另一个分支，直接进另一个目录，原来的改动原地保留。
- **并行干活**：Claude 在一个目录改前端，Codex 在另一个目录改后端，文件互不覆盖。
- **临时验证**：想看 `master` 的行为，不打断当前工作。

## 常用命令

查看现有工作树：

```bash
git worktree list
```

从 `master` 新建分支，并放到仓库上一层的新目录（最常用）：

```bash
git worktree add -b claude/m5-fe ../PromptLab-m5-fe master
```

含义：创建分支 `claude/m5-fe`，以 `master` 为起点，目录是 `../PromptLab-m5-fe`。

检出一个已经存在的分支到新目录：

```bash
git worktree add ../PromptLab-m3-02 codex/m3-02
```

进入新目录后，就是普通仓库，照常 `status`、`add`、`commit`：

```bash
cd ../PromptLab-m5-fe
git status
```

用完删除目录和记录：

```bash
git worktree remove ../PromptLab-m5-fe
```

这一步会拒绝删除有未提交改动的目录。先提交或处理掉改动，再执行。确认分支已合并后，可以再删分支：

```bash
git branch -d claude/m5-fe
```

`-d` 只删已合并的分支；它会拒绝删除未合并的分支，这是有意的保护。

如果目录被手动删掉了，仓库里还留着记录，用下面这条清理：

```bash
git worktree prune
```

## 容易踩的坑

1. **同一分支只能在一个工作树里检出。** `master` 已经在某个目录检出时，再执行 `git worktree add ../x master` 会报错 `'master' is already used by worktree at ...`。要么改用新分支，要么回到那个目录操作。
2. **未提交的改动不会跟着走。** 工作树之间共享提交，不共享未提交的文件。要让另一边看到改动，先在当前目录 `commit`。
3. **新目录不会带上本地未跟踪的文件。** `docs/personal/`、`docs/agent/`、`backend/data/`、`node_modules/`、`frontend/dist/` 这类被 `.gitignore` 忽略的内容，新目录里都没有。需要的话从主目录复制，或者重新安装依赖。
4. **不要直接 `rm -rf` 工作树目录。** 仓库还会记着它，之后要 `git worktree prune`。统一用 `git worktree remove`。
5. **`/private/tmp/` 下的工作树是临时位置。** 系统清理后目录会消失，里面未提交的内容也会一起丢。那里的工作树用完就 `remove`，或者挪到 `~/code/` 下。
6. **提交和推送需要用户同意。** 工作树只是换了目录，不改变 [AGENTS.md](../../AGENTS.md) 的规则：不擅自提交或推送。

## 把改动合回去

在要接收改动的分支所在目录执行：

```bash
git merge codex/m4
```

合并前先确认当前目录的分支是对的：

```bash
git branch --show-current
```

合并冲突时，按 `git status` 列出的文件逐个解决，再 `git add` 和 `git commit`。

## 本机现状

`git worktree list` 显示的才是实时状态，本文不记录具体路径。以下是读文档时可以对照的几类目录：

- 主目录 `PromptLab/`：当前工作分支，一般是本轮正在做的任务。
- `.claude/worktrees/`：Claude 自动创建的临时工作树，路径已写进 `.git/info/exclude`，不进仓库。
- 名为 `PromptLab-<任务号>` 的同级目录：按任务手动创建的工作树。

清理前先用 `git worktree list` 看清楚，再逐个确认是否还有未合并或未提交的内容。
