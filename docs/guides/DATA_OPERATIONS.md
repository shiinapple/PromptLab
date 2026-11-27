# SQLite 备份与恢复

从 PromptLab 仓库根目录执行。要求 Java 21 和支持 POSIX 权限的本地文件系统。可用 `JAVA` 指定 Java 21 的绝对路径，脚本默认使用 PATH 上的 Java。使用与数据库配套的 JAR 和题库版本。

```sh
JAR=backend/target/prompt-practice-0.1.0-SNAPSHOT.jar
DB=backend/data/prompt-practice.sqlite3
./deploy/sqlite/backup.sh "$JAR" "$DB" backend/data/backups
```

数据库须已存在，备份目录的父目录须已存在，备份目录仅限当前用户访问。每天低峰通过系统 cron 或 systemd timer 调用一次。脚本先使用 SQLite `VACUUM INTO` 写入暂存文件，校验后发布，再清理旧备份。同一目录的备份操作串行，默认保留最近 7 份。旧的 `prompt-practice.retention.backup` 仍可显式设置额外的最大文件年龄。每日备份成功时，恢复窗口为 7 天，最多丢失 1 天数据。

把第二个参数替换成备份命令输出的备份路径，第三个参数须为尚未存在的隔离目录。

```sh
./deploy/sqlite/restore.sh "$JAR" '<备份文件路径>' backend/data/restore-check
```

恢复成功输出各表行数。校验包含完整性、外键、原备份与恢复文件比对，以及设置、草稿和归档业务数据。恢复文件位于 `backend/data/restore-check/prompt-practice.sqlite3`。可用隔离实例与同版本题库抽查数据；正式切换前先停止旧服务，再核对实际数据库路径。

现有账号运维入口依然使用数据库环境变量 `PROMPT_PRACTICE_DB_PATH`。停用使用 `java -jar "$JAR" --ops disable-user '<邮箱>'`。确认目标账号后，才用 `java -jar "$JAR" --ops delete-user '<邮箱>'` 删除已停用账号及关联数据。
