package cn.shiinapple.promptpractice.pocsqlite;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadTest {
    private static final int TRANSACTIONS_PER_CLIENT = 100;

    @TempDir
    Path directory;

    @Test
    void onlyOneWriterCanFinalizeTheSamePendingRun() throws Exception {
        try (var source = SqlitePoc.openPool(directory.resolve("race.sqlite"), SqlitePoc.BUSY_TIMEOUT_MS);
             var workers = Executors.newFixedThreadPool(2)) {
            DatabaseTest.migrate(source);
            var jdbc = JdbcClient.create(source);
            jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
            jdbc.sql("INSERT INTO runs (id, user_id, state) VALUES (1, 1, 'PENDING')").update();
            var start = new CountDownLatch(1);
            var a = workers.submit(() -> {
                start.await();
                return finalizeRun(jdbc, 1, "a");
            });
            var b = workers.submit(() -> {
                start.await();
                return finalizeRun(jdbc, 1, "b");
            });
            start.countDown();
            int aRows = a.get(10, TimeUnit.SECONDS);
            int bRows = b.get(10, TimeUnit.SECONDS);
            assertEquals(1, aRows + bRows);
            var row = jdbc.sql("SELECT state, revision, result FROM runs WHERE id=1").query().singleRow();
            assertEquals("DONE", row.get("state"));
            assertEquals(1, row.get("revision"));
            assertEquals(aRows == 1 ? "a" : "b", row.get("result"));
        }
    }

    @Test
    void measuresShortTransactionsWithOneFiveAndTenConcurrentClients() throws Exception {
        System.out.println("POC_LOAD clients,transactions,errors,p50_ms,p95_ms,elapsed_ms,cpu_ms,"
                + "jvm_rss_kib,jvm_peak_rss_kib,disk_growth_bytes");
        for (int clients : List.of(1, 5, 10)) {
            measure(clients);
        }
    }

    private void measure(int clients) throws Exception {
        var database = directory.resolve("load-" + clients + ".sqlite");
        try (var source = SqlitePoc.openPool(database, SqlitePoc.BUSY_TIMEOUT_MS);
             var workers = Executors.newFixedThreadPool(clients)) {
            DatabaseTest.migrate(source);
            var jdbc = JdbcClient.create(source);
            var tx = new TransactionTemplate(new JdbcTransactionManager(source));
            jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
            long initialBytes = diskBytes(database);
            var ready = new CountDownLatch(clients);
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<List<Long>>>();
            for (int client = 0; client < clients; client++) {
                int firstId = client * TRANSACTIONS_PER_CLIENT + 1;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    var latencies = new ArrayList<Long>();
                    for (int id = firstId; id < firstId + TRANSACTIONS_PER_CLIENT; id++) {
                        long before = System.nanoTime();
                        insertFinalizeAndRead(jdbc, tx, id);
                        latencies.add(System.nanoTime() - before);
                    }
                    return latencies;
                }));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long began = System.nanoTime();
            long cpuBefore = os.getProcessCpuTime();
            start.countDown();
            assertTrue(allReady);
            var latencies = new ArrayList<Long>();
            var errors = new ArrayList<Throwable>();
            for (var task : tasks) {
                try {
                    latencies.addAll(task.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    errors.add(failure.getCause());
                }
            }
            long cpuTime = os.getProcessCpuTime() - cpuBefore;
            long elapsed = System.nanoTime() - began;
            latencies.sort(Long::compareTo);
            System.out.printf(Locale.ROOT, "POC_LOAD %d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d%n",
                    clients, clients * TRANSACTIONS_PER_CLIENT, errors.size(),
                    percentile(latencies, 0.50), percentile(latencies, 0.95), millis(elapsed), millis(cpuTime),
                    procMemoryKiB("VmRSS:"), procMemoryKiB("VmHWM:"), diskBytes(database) - initialBytes);
            assertTrue(errors.isEmpty(), errors::toString);
            assertEquals(clients * TRANSACTIONS_PER_CLIENT, latencies.size());
            assertEquals(latencies.size(), DatabaseTest.countRuns(jdbc));
            assertEquals(latencies.size(), jdbc.sql("SELECT count(*) FROM runs WHERE state='DONE' AND revision=1")
                    .query(Integer.class).single());
        }
    }

    private static void insertFinalizeAndRead(JdbcClient jdbc, TransactionTemplate tx, int id) {
        String value = "result-" + id;
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("INSERT INTO runs (id, user_id, state) VALUES (?, 1, 'PENDING')").param(id).update();
            assertEquals(1, finalizeRun(jdbc, id, value));
        });
        assertEquals(value, jdbc.sql("SELECT result FROM runs WHERE id=?").param(id).query(String.class).single());
    }

    private static int finalizeRun(JdbcClient jdbc, int id, String value) {
        return jdbc.sql("""
                UPDATE runs SET state='DONE', result=?, revision=revision+1
                WHERE id=? AND state='PENDING' AND revision=0
                """).param(value).param(id).update();
    }

    private static double percentile(List<Long> sorted, double fraction) {
        return sorted.isEmpty() ? Double.NaN : millis(sorted.get((int) Math.ceil(fraction * sorted.size()) - 1));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long diskBytes(Path base) throws Exception {
        long bytes = 0;
        for (String suffix : List.of("", "-wal", "-shm")) {
            var file = base.resolveSibling(base.getFileName() + suffix);
            if (Files.exists(file)) {
                bytes += Files.size(file);
            }
        }
        return bytes;
    }

    private static long procMemoryKiB(String field) throws Exception {
        var status = Path.of("/proc/self/status");
        if (!Files.exists(status)) {
            return -1;
        }
        try (var lines = Files.lines(status)) {
            return lines.filter(line -> line.startsWith(field)).findFirst()
                    .map(line -> Long.parseLong(line.strip().split("\\s+")[1])).orElse(-1L);
        }
    }
}
