# POC-04 Java 与 SQLite

独立技术实验。Spring Boot MVC 只提供一个 loopback 测试端点，数据库使用 JUnit 创建的临时文件和两张模拟表。验收见 [POC-04 验证记录](../../docs/validation/POC-04.md)。

## 本机复跑

在 PromptLab 仓库根目录进入实验目录，选择 Java 21 和 Maven 3.9。macOS 可以用已安装的 JDK 设置 `JAVA_HOME`。

```sh
cd poc/java-sqlite
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B -ntp clean verify dependency:tree -DoutputFile=results/dependency-tree.txt
```

`clean` 清理本实验的 `target/`。首次运行从 Maven Central 解析依赖；测试绑定随机 loopback 端口。JUnit 提供独立临时库。

## Linux 资源限额复跑

从 PromptLab 仓库根目录运行。先用同一 Maven 缓存暖起依赖，再让容器在无外网的情况下执行。命令用下面的缓存和镜像复现本轮环境。结果输出到终端，历史日志保存在 [results/linux-container.log](results/linux-container.log)。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
poc_cache=$(mktemp -d /private/tmp/promptlab-poc04-m2.XXXXXX)
mvn -B -ntp -f poc/java-sqlite/pom.xml -Dmaven.repo.local="$poc_cache" clean verify
docker pull maven:3.9.11-eclipse-temurin-21
docker run --rm --network none --cpus 2 --memory 2g --memory-swap 2g \
  --mount "type=bind,source=$PWD/poc/java-sqlite,target=/work" \
  --mount "type=bind,source=$poc_cache,target=/root/.m2/repository,readonly" \
  --workdir /work maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 \
  sh -c 'for limit in cpu.max memory.max memory.swap.max; do printf "%s=" "$limit"; cat "/sys/fs/cgroup/$limit" || exit; done; mvn -o -B -ntp -Dstyle.color=never clean verify; result=$?; cat /sys/fs/cgroup/memory.events; exit "$result"'
```

`POC_LOAD` 每个客户端做 100 次短写事务和一次提交后读取。P50/P95 包含线程调度、连接池、SQL 和回读等开销，JVM 峰值 RSS 为进程运行至该测点的累计峰值。磁盘增量包含打开状态下的 SQLite 文件、WAL、SHM。`POC_BUSY` 单独测试可解释的写锁等待。Flyway 的故障迁移日志由测试夹具触发，最终状态由 JUnit 断言核验。
