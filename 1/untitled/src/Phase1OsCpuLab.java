import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase1 OS/CPU experiments (1~3) in a single Java file.
 *
 * How to run:
 *   javac Phase1OsCpuLab.java
 *   java Phase1OsCpuLab
 *
 * Optional:
 *   java Phase1OsCpuLab --iters=30000000 --seconds=3 --threads=1,2,4,8,16,32
 *
 * Notes:
 * - This is not a perfect scientific benchmark (JMH is for that),
 *   but it is VERY good for building CS intuition by observing trends.
 * - Run multiple times and compare patterns.
 */
public class Phase1OsCpuLab {

    // --- Defaults (tune if your machine is slow/fast) ---
    static long CPU_WORK_ITERS_PER_THREAD = 25_000_000L;   // Experiment 1 total work per thread
    static int WAIT_SECONDS = 3;                           // Experiment 3 waiting duration
    static String THREAD_LIST = "1,2,4,8,16,32";           // Thread counts to test

    // Make sure the JIT doesn't optimize away the loop entirely
    static volatile long BLACKHOLE = 0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        parseArgs(args);

        System.out.println("==== Phase 1: OS/CPU 감각 깨우기 (Java single-file lab) ====");
        System.out.println("Java: " + System.getProperty("java.version") + " | VM: " + System.getProperty("java.vm.name"));
        System.out.println("OS  : " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("CPU : availableProcessors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("Settings: itersPerThread=" + CPU_WORK_ITERS_PER_THREAD + ", waitSeconds=" + WAIT_SECONDS + ", threads=" + THREAD_LIST);
        System.out.println();

        int[] threadsToTest = Arrays.stream(THREAD_LIST.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();

        // Warm up (important for JIT stabilization)
        warmup();

        experiment1_threadsVsThroughput(threadsToTest);
        experiment2_chunkSizeAndYield(threadsToTest);
        experiment3_busyWaitVsSleepVsBlocking();

        System.out.println("\nDone. Tip: Run 3 times and compare patterns (특히 코어 수 근처에서).");
    }

    // ---------------------------
    // Experiment 1: threads vs performance (CPU-bound)
    // ---------------------------
    static void experiment1_threadsVsThroughput(int[] threadCounts) throws Exception {
        System.out.println("------------------------------------------------------------");
        System.out.println("[Experiment 1] 스레드 수 vs 성능 (CPU-bound)");
        System.out.println("Goal: 코어 수까지는 보통 성능↑, 그 이후는 context switch/cache 영향으로 정체/하락을 관찰");
        System.out.println("Metric: wall(ms), throughput(M ops/s), processCpu(ms), cpuUtilApprox(%)");
        System.out.println();

        System.out.printf("%8s | %10s | %14s | %14s | %14s%n",
                "threads", "wall(ms)", "throughput", "cpuTime(ms)", "cpuUtil~(%)");
        System.out.println("------------------------------------------------------------------------------------");

        for (int t : threadCounts) {
            Result r = runCpuBound(t, CPU_WORK_ITERS_PER_THREAD, WorkMode.PLAIN, 0);
            System.out.printf("%8d | %10d | %14.2f | %14.0f | %14.1f%n",
                    t, r.wallMs, r.throughputMOpsPerSec, r.cpuMs, r.cpuUtilApprox);
        }

        System.out.println("\n배울 점:");
        System.out.println("- CPU-bound 작업은 대개 스레드가 '코어 수' 근처에서 가장 효율적");
        System.out.println("- 코어 수를 크게 넘기면: context switch 증가 + cache thrash + 스케줄러 오버헤드로 throughput이 정체/하락");
        System.out.println();
    }

    // ---------------------------
    // Experiment 2: chunk size / yield frequency (scheduling overhead)
    // ---------------------------
    static void experiment2_chunkSizeAndYield(int[] threadCounts) throws Exception {
        System.out.println("------------------------------------------------------------");
        System.out.println("[Experiment 2] 작업 단위(Chunk) & 양보(yield) 빈도에 따른 오버헤드");
        System.out.println("Goal: 일을 너무 잘게 쪼개거나 자주 yield하면 스케줄링/전환 오버헤드로 throughput이 떨어짐을 관찰");
        System.out.println("We run SAME total iterations but different 'yieldEvery' settings.");
        System.out.println();

        // Choose a representative thread count near your core count for strongest effect
        int core = Runtime.getRuntime().availableProcessors();
        int t = pickClosest(threadCounts, core);
        long iters = CPU_WORK_ITERS_PER_THREAD;

        System.out.println("Using threads=" + t + " (closest to cores=" + core + "), itersPerThread=" + iters);
        System.out.printf("%18s | %10s | %14s | %14s | %14s%n",
                "mode(yieldEvery)", "wall(ms)", "throughput", "cpuTime(ms)", "cpuUtil~(%)");
        System.out.println("------------------------------------------------------------------------------------------------");

        // yieldEvery = 0  => no yield
        Result r0 = runCpuBound(t, iters, WorkMode.PLAIN, 0);
        System.out.printf("%18s | %10d | %14.2f | %14.0f | %14.1f%n",
                "NONE", r0.wallMs, r0.throughputMOpsPerSec, r0.cpuMs, r0.cpuUtilApprox);

        // yieldEvery small => frequent yield (bad)
        Result r1 = runCpuBound(t, iters, WorkMode.YIELD, 1_000);
        System.out.printf("%18s | %10d | %14.2f | %14.0f | %14.1f%n",
                "YIELD/1k", r1.wallMs, r1.throughputMOpsPerSec, r1.cpuMs, r1.cpuUtilApprox);

        Result r2 = runCpuBound(t, iters, WorkMode.YIELD, 10_000);
        System.out.printf("%18s | %10d | %14.2f | %14.0f | %14.1f%n",
                "YIELD/10k", r2.wallMs, r2.throughputMOpsPerSec, r2.cpuMs, r2.cpuUtilApprox);

        Result r3 = runCpuBound(t, iters, WorkMode.YIELD, 100_000);
        System.out.printf("%18s | %10d | %14.2f | %14.0f | %14.1f%n",
                "YIELD/100k", r3.wallMs, r3.throughputMOpsPerSec, r3.cpuMs, r3.cpuUtilApprox);

        System.out.println("\n배울 점:");
        System.out.println("- 작업 단위를 너무 잘게 쪼개거나 자주 양보하면 스케줄링/컨텍스트 스위치가 늘어 오버헤드가 커짐");
        System.out.println("- 실무에서도 '너무 작은 작업을 스레드풀에 던지기'는 성능을 망칠 수 있음");
        System.out.println();
    }

    // ---------------------------
    // Experiment 3: busy-wait vs sleep vs blocking
    // ---------------------------
    static void experiment3_busyWaitVsSleepVsBlocking() throws Exception {
        System.out.println("------------------------------------------------------------");
        System.out.println("[Experiment 3] Busy-wait vs Sleep vs Blocking (대기 방식 비교)");
        System.out.println("Goal: CPU를 태우며 기다리는 방식(busy-wait)이 얼마나 비효율적인지 + blocking이 왜 좋은지 체감");
        System.out.println("We wait for " + WAIT_SECONDS + " seconds using 3 different strategies.");
        System.out.println();

        System.out.printf("%14s | %10s | %14s | %14s%n",
                "waitType", "wall(ms)", "cpuTime(ms)", "cpuUtil~(%)");
        System.out.println("---------------------------------------------------------------------");

        WaitResult w1 = runWaitTest(WaitType.BUSY, WAIT_SECONDS);
        System.out.printf("%14s | %10d | %14.0f | %14.1f%n",
                "BUSY", w1.wallMs, w1.cpuMs, w1.cpuUtilApprox);

        WaitResult w2 = runWaitTest(WaitType.SLEEP_1MS, WAIT_SECONDS);
        System.out.printf("%14s | %10d | %14.0f | %14.1f%n",
                "SLEEP_1ms", w2.wallMs, w2.cpuMs, w2.cpuUtilApprox);

        WaitResult w3 = runWaitTest(WaitType.BLOCKING, WAIT_SECONDS);
        System.out.printf("%14s | %10d | %14.0f | %14.1f%n",
                "BLOCKING", w3.wallMs, w3.cpuMs, w3.cpuUtilApprox);

        System.out.println("\n배울 점:");
        System.out.println("- BUSY는 CPU를 100% 태우면서 '아무 것도 안 하는' 최악의 대기(폴링) 방식");
        System.out.println("- sleep은 CPU를 덜 쓰지만, 타이머/스케줄링 때문에 '정확히 1ms'처럼 동작하지 않을 수 있음");
        System.out.println("- blocking(세마포어/조건변수 등)은 CPU를 거의 안 쓰고도 즉시 깨어날 수 있어 가장 효율적");
        System.out.println();
    }

    // ============================================================
    // Core Runner: CPU-bound workload with optional yield frequency
    // ============================================================

    enum WorkMode { PLAIN, YIELD }

    static class Result {
        long wallMs;
        double cpuMs;
        double cpuUtilApprox;        // cpuMs / wallMs / cores * 100
        double throughputMOpsPerSec; // million-ops per second (approx)
    }

    static Result runCpuBound(int threads, long itersPerThread, WorkMode mode, int yieldEvery) throws Exception {
        // One "op" = one loop iteration in cpuWork()
        final long totalOps = itersPerThread * threads;

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean cpuTimeSupported = bean.isThreadCpuTimeSupported();
        if (cpuTimeSupported && !bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        final long[] threadCpuNanos = new long[threads];
        final Thread[] workers = new Thread[threads];

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    long tid = Thread.currentThread().getId();
                    long cpuStart = cpuTimeSupported ? bean.getThreadCpuTime(tid) : 0L;

                    long local = 0;
                    if (mode == WorkMode.PLAIN) {
                        local = cpuWork(itersPerThread);
                    } else {
                        local = cpuWorkWithYield(itersPerThread, yieldEvery);
                    }
                    BLACKHOLE ^= local; // prevent dead-code elimination

                    long cpuEnd = cpuTimeSupported ? bean.getThreadCpuTime(tid) : 0L;
                    threadCpuNanos[idx] = Math.max(0L, cpuEnd - cpuStart);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "cpu-worker-" + i);
            workers[i].start();
        }

        long wallStart = System.nanoTime();
        start.countDown();
        done.await();
        long wallEnd = System.nanoTime();

        long wallMs = TimeUnit.NANOSECONDS.toMillis(wallEnd - wallStart);

        double cpuMs = 0;
        for (long ns : threadCpuNanos) cpuMs += ns / 1_000_000.0;

        int cores = Runtime.getRuntime().availableProcessors();
        double cpuUtilApprox = (wallMs > 0)
                ? (cpuMs / (wallMs * 1.0) / cores) * 100.0
                : 0.0;

        double wallSec = Math.max(1e-9, (wallEnd - wallStart) / 1_000_000_000.0);
        double throughputMOps = (totalOps / wallSec) / 1_000_000.0;

        Result r = new Result();
        r.wallMs = wallMs;
        r.cpuMs = cpuMs;
        r.cpuUtilApprox = cpuUtilApprox;
        r.throughputMOpsPerSec = throughputMOps;
        return r;
    }

    // CPU-bound work: simple integer mixing (cheap but not trivial)
    static long cpuWork(long iters) {
        long x = 0x9E3779B97F4A7C15L;
        for (long i = 0; i < iters; i++) {
            x ^= (x << 13);
            x ^= (x >>> 7);
            x ^= (x << 17);
            x += i * 0xBF58476D1CE4E5B9L;
        }
        return x;
    }

    static long cpuWorkWithYield(long iters, int yieldEvery) {
        long x = 0x9E3779B97F4A7C15L;
        int ye = Math.max(1, yieldEvery);
        for (long i = 0; i < iters; i++) {
            x ^= (x << 13);
            x ^= (x >>> 7);
            x ^= (x << 17);
            x += i * 0xBF58476D1CE4E5B9L;

            if ((i % ye) == 0) {
                Thread.yield(); // encourage scheduler to switch
            }
        }
        return x;
    }

    // ============================================================
    // Wait tests
    // ============================================================

    enum WaitType { BUSY, SLEEP_1MS, BLOCKING }

    static class WaitResult {
        long wallMs;
        double cpuMs;
        double cpuUtilApprox;
    }

    static WaitResult runWaitTest(WaitType type, int seconds) throws Exception {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean cpuTimeSupported = bean.isThreadCpuTimeSupported();
        if (cpuTimeSupported && !bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);

        int cores = Runtime.getRuntime().availableProcessors();

        // A signal released after 'seconds'
        AtomicBoolean flag = new AtomicBoolean(false);
        Semaphore sem = new Semaphore(0);

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException ignored) {}
            flag.set(true);
            sem.release();
        }, "releaser");

        Thread waiter = new Thread(() -> {
            try {
                if (type == WaitType.BUSY) {
                    while (!flag.get()) {
                        // busy spin
                    }
                } else if (type == WaitType.SLEEP_1MS) {
                    while (!flag.get()) {
                        Thread.sleep(1);
                    }
                } else { // BLOCKING
                    // Wait without burning CPU
                    sem.acquire();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "waiter");

        long waiterId = -1;
        long cpuStart = 0;

        long wallStart = System.nanoTime();
        releaser.start();
        waiter.start();

        // capture after start (thread id exists)
        waiterId = waiter.getId();
        cpuStart = cpuTimeSupported ? bean.getThreadCpuTime(waiterId) : 0L;

        waiter.join();
        long wallEnd = System.nanoTime();
        long cpuEnd = cpuTimeSupported ? bean.getThreadCpuTime(waiterId) : 0L;

        long wallMs = TimeUnit.NANOSECONDS.toMillis(wallEnd - wallStart);
        double cpuMs = (cpuTimeSupported ? (cpuEnd - cpuStart) / 1_000_000.0 : Double.NaN);

        double cpuUtilApprox = (wallMs > 0 && cpuTimeSupported)
                ? (cpuMs / (wallMs * 1.0) / cores) * 100.0
                : Double.NaN;

        WaitResult r = new WaitResult();
        r.wallMs = wallMs;
        r.cpuMs = cpuMs;
        r.cpuUtilApprox = cpuUtilApprox;
        return r;
    }

    // ============================================================
    // Utils
    // ============================================================

    static void warmup() throws Exception {
        System.out.println("[Warmup] JIT 안정화를 위해 짧게 2회 워밍업합니다...");
        runCpuBound(1, 3_000_000L, WorkMode.PLAIN, 0);
        runCpuBound(Math.min(2, Runtime.getRuntime().availableProcessors()), 3_000_000L, WorkMode.PLAIN, 0);
        System.out.println();
    }

    static int pickClosest(int[] arr, int target) {
        int best = arr[0];
        int bestDist = Math.abs(best - target);
        for (int v : arr) {
            int d = Math.abs(v - target);
            if (d < bestDist) {
                best = v;
                bestDist = d;
            }
        }
        return best;
    }

    static void parseArgs(String[] args) {
        for (String a : args) {
            if (a.startsWith("--iters=")) {
                CPU_WORK_ITERS_PER_THREAD = Long.parseLong(a.substring("--iters=".length()).trim());
            } else if (a.startsWith("--seconds=")) {
                WAIT_SECONDS = Integer.parseInt(a.substring("--seconds=".length()).trim());
            } else if (a.startsWith("--threads=")) {
                THREAD_LIST = a.substring("--threads=".length()).trim();
            }
        }
    }
}
