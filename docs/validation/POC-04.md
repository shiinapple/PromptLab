# POC-04：SQLite 与 Java 栈

[验证总览](../TESTING.md)

- 最小实验：Java 21 + 候选受支持 Boot/MVC + SQLite JDBC + JDBC 访问层 + 迁移工具；只建立 users/runs 模拟表，不实现正式账户。
- 验证：记录 Java/Boot/Jackson/驱动/迁移器版本与依赖树；干净库迁移、再次启动、升级已有库、失败迁移行为、WAL 与 busy timeout（每个连接适用项）、事务回滚、外键、并发读写、条件更新、备份恢复。
- 样例负载（建议，不是 SLA）：1/5/10 并发客户端，分别执行短读写事务；记录错误、锁等待、延迟 P50/P95、JVM RSS、CPU 与磁盘增长；同规格目标机器或限制环境另行测量。
- 通过：迁移可复现，不发生丢写/无解释锁错误，备份可恢复并验证数据，2GB 环境在候选负载下无 OOM。数值门槛由实测及目标规模确认。
- 失败：先缩短事务、限制连接/并发并核对驱动；迁移工具不兼容时评估其他成熟工具；仍无法满足需求再评审独立 DB，非默认升级。

## 前置与范围

POC-04 可独立进行，技术方向来自 D06。M0-01、M0-03 依赖本验证。实验放在 `poc/java-sqlite`，使用临时库、两张模拟表和一个只监听 loopback 的 MVC 探针。版本、池大小与等待时间用于比较实验，正式依赖和资源预算待后续确认。[实验源码与复跑](../../poc/java-sqlite/README.md)

## POC-04 执行记录

2026-11-25，本机和 2 核、2 GiB Linux 容器 **PASS**，目标部署主机 **NOT_RUN**。本机为 macOS arm64、Oracle JDK 21.0.8、Maven 3.9.11。无外网容器为 Linux arm64、Temurin 21.0.9、Maven 3.9.11，cgroup 实测 `cpu.max=200000 100000`、`memory.max=2147483648`、`memory.swap.max=0`。[Linux 执行日志](../../poc/java-sqlite/results/linux-container.log)

| 检查 | 实测证据 |
|---|---|
| 应用栈 | Spring Boot MVC 4.1.3 本机 loopback HTTP 200，JdbcClient 读取 SQLite 3.54.1，并由 Jackson 3.1.8 序列化。依赖树记录 HikariCP 7.0.2、Flyway Core 12.4.0、Xerial JDBC 3.54.1.0、JUnit 6.0.3。 |
| 建库、重启与升级 | 空库执行 V1、V2，重新打开执行 0 次。已有 V1 数据保留，新增 revision 为 0。 |
| 迁移失败及应用回滚 | V3 先建表再触发不存在表错误，Flyway 回滚 DDL，历史无 V3，版本停留 V2，原行保留。另以 Spring `TransactionTemplate` 验证两次写入共同回滚。 |
| 连接和锁 | 同时借出 4 个连接，逐一验证 WAL、`foreign_keys=1`、`busy_timeout=1000`，孤儿记录被拒绝。故障夹具将超时设为 250 ms，持有第一条写事务时，读连接看到已提交状态；第二个写入约 285 ms 后返回 SQLite 错误码 5。释放写锁后，同一写入成功。 |
| 条件更新 | 两个写入同时争抢同一条 `PENDING` 记录，条件 UPDATE 只有一个返回影响 1 行，最终结果与胜出写入相同，revision 只增加一次。 |
| 备份和恢复 | 源库保持打开且 WAL 有内容时执行 `VACUUM INTO`，将备份恢复到独立文件。`integrity_check` 为 `ok`，无外键错误，恢复数据准确，源库之后的写入没有进入快照。 |

| 并发客户端 | 完成事务与回读 | 错误 | P50/P95（ms） | 实际耗时 / JVM CPU 时间（ms） | JVM 峰值 RSS（KiB） | 磁盘增量（字节） |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 0 | 0.488 / 1.514 | 67.535 / 50.000 | 213,924 | 824,000 |
| 5 | 500 | 0 | 0.336 / 3.646 | 226.524 / 130.000 | 215,464 | 4,086,800 |
| 10 | 1,000 | 0 | 0.380 / 23.097 | 723.208 / 440.000 | 217,320 | 4,123,760 |

三个负载各用一份空库，每客户端执行 100 次短写事务，每次提交后回读并核对结果，最后检查精确行数和 revision。P95 含连接池等待、线程调度、写入、提交和回读，本轮无法从该值拆分 SQLite 锁等待。锁等待另由受控冲突用例测得。磁盘增量包含打开状态的 DB、WAL 和 SHM，峰值 RSS 为同一测试 JVM 的累计峰值。容器 OOM 与 OOM kill 均为 0。[负载 CSV](../../poc/java-sqlite/results/workload.csv)

| 命令或检查 | 实际结果 |
|---|---|
| `mvn -B -DskipTests validate`，本机 Java 21，项目使用临时 Maven 缓存 | 退出 1，默认沙箱无法解析 Maven Central。 |
| `mvn -B -U -DskipTests package`，本机 Java 21，使用相同缓存并允许联网 | 退出 0，编译打包完成。 |
| `mvn -B -ntp test dependency:tree -DoutputFile=results/dependency-tree.txt`，本机 Java 21 | 退出 0，10/10。生成[完整依赖树](../../poc/java-sqlite/results/dependency-tree.txt)。 |
| `docker run --network none --cpus 2 --memory 2g --memory-swap 2g … mvn -o -B -ntp -Dstyle.color=never clean verify` | 退出 0，10/10，Linux cgroup OOM 0。完整可运行命令见实验 README。 |

选择 Flyway 管理版本、JDBC 与 HikariCP 管理 SQL 和连接、SQLite 原生机制生成备份。上游确认 SQLite 迁移需由单一实例执行。Spring Boot、Flyway Core 和 Xerial 驱动核对了 Apache-2.0 许可，SQLite 引擎为公有领域。生产分发前还需核对完整传递依赖。若长事务或写入争用成为问题，优先缩短事务或调小并发连接数。更大库可评估 SQLite Online Backup API；迁移兼容性发生变化时可比较 Liquibase；持续多写者或多实例需求明确后再评估独立数据库。[Spring Boot 发布信息](https://spring.io/projects/) · [Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html) · [Flyway SQLite](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite) · [Flyway 许可](https://github.com/flyway/flyway/blob/flyway-12.4.0/LICENSE.txt) · [Xerial 许可](https://github.com/xerial/sqlite-jdbc/blob/3.54.1.0/LICENSE) · [SQLite WAL](https://www.sqlite.org/wal.html) · [SQLite VACUUM](https://www.sqlite.org/lang_vacuum.html)

当前数据来自 Docker Desktop 内的 Linux cgroup 和短时小数据集。目标主机的真实磁盘、Nginx 与 JVM 共存、长读事务对 checkpoint 的影响、长时间多进程负载、耗尽磁盘或进程异常退出后的恢复仍待验证。时延数字用于建立实验基线，容量阈值仍待真实负载与目标规模确认。
