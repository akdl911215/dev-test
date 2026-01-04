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
    // 총 작업량 = itersPerThread × threadCount
    // CPU-bound 작업을 충분히 오래 돌려서 스케줄링·캐시·병렬화 패턴이 ‘노이즈 없이’ 드러나게 하기
    // 각 스레드에게 이만큼 일하고 나와 라고 시키는 ‘업무량’이다

    static int WAIT_SECONDS = 3;                           // Experiment 3 waiting duration
    // 3초 이유: 대기 방식(busy / sleep / blocking)의 CPU 사용 차이를 사람 눈으로도 확실히 체감할 수 있게 만들기

//    static String THREAD_LIST = "1,2,4,8,16,32";           // Thread counts to test
    static String THREAD_LIST = "1, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32";           // Thread counts to test
    // 스레드 수 증가에 따른 성능 곡선을 ‘점’이 아니라 ‘선’으로 보기

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
        System.out.println("Threads: " + Arrays.toString(threadsToTest));

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
        System.out.println("Goal: 스레드 수 증가에 따른 처리량 증가/체감/하락 패턴 관찰");
        System.out.println("Metric: wall(ms), throughput(M ops/s), processCpu(ms), cpuUtilApprox(%), efficiency(throughput/threads)");
        System.out.println();

        System.out.printf("%8s | %10s | %14s | %14s | %14s | %14s%n",
                "threads", "wall(ms)", "throughput", "cpuTime(ms)", "cpuUtil~(%)", "eff(Mops/t)");
        System.out.println("--------------------------------------------------------------------------------------------------------------");

        // 결과 저장용
        class Row {
            final int threads;
            final Result r;
            final double eff; // throughput per thread (M ops/s per thread)
            Row(int threads, Result r) {
                this.threads = threads;
                this.r = r;
                this.eff = r.throughputMOpsPerSec / Math.max(1, threads);
            }
        }

        java.util.List<Row> rows = new java.util.ArrayList<>();

        for (int t : threadCounts) {
            Result r = runCpuBound(t, CPU_WORK_ITERS_PER_THREAD, WorkMode.PLAIN, 0);
            Row row = new Row(t, r);
            rows.add(row);

            System.out.printf("%8d | %10d | %14.2f | %14.0f | %14.1f | %14.2f%n",
                    t, r.wallMs, r.throughputMOpsPerSec, r.cpuMs, r.cpuUtilApprox, row.eff);
        }

        // ---------------------------
        // 자동 요약(데이터 기반)
        // ---------------------------
        Row peakThroughput = rows.get(0);
        Row bestEfficiency = rows.get(0);

        for (Row row : rows) {
            if (row.r.throughputMOpsPerSec > peakThroughput.r.throughputMOpsPerSec) {
                peakThroughput = row;
            }
            if (row.eff > bestEfficiency.eff) {
                bestEfficiency = row;
            }
        }

        double target90 = peakThroughput.r.throughputMOpsPerSec * 0.90;
        Row minThreadsFor90 = null;
        for (Row row : rows) {
            if (row.r.throughputMOpsPerSec >= target90) {
                minThreadsFor90 = row;
                break;
            }
        }

        System.out.println();
        System.out.println("요약(자동 계산):");
        System.out.printf("- Peak throughput: threads=%d, throughput=%.2f M ops/s (wall=%dms, cpuUtil~%.1f%%)%n",
                peakThroughput.threads, peakThroughput.r.throughputMOpsPerSec, peakThroughput.r.wallMs, peakThroughput.r.cpuUtilApprox);

        System.out.printf("- Best efficiency(throughput/threads): threads=%d, eff=%.2f M ops/s/thread (throughput=%.2f)%n",
                bestEfficiency.threads, bestEfficiency.eff, bestEfficiency.r.throughputMOpsPerSec);

        if (minThreadsFor90 != null) {
            System.out.printf("- 90%% of peak(>= %.2f): 최소 threads=%d (throughput=%.2f)%n",
                    target90, minThreadsFor90.threads, minThreadsFor90.r.throughputMOpsPerSec);
        } else {
            System.out.printf("- 90%% of peak(>= %.2f): 만족하는 threads 없음 (측정 범위/환경을 확인)%n", target90);
        }

        System.out.println();
        System.out.println("해석 가이드:");
        System.out.println("- Throughput = 단위 시간당 완료한 작업 수(성과)");
        System.out.println("- cpuUtil~(%) = CPU가 바빴던 정도(성과와 동일하지 않음)");
        System.out.println("- Efficiency(throughput/threads) = 스레드 1개당 기여도(가성비)");
        System.out.println("- Peak throughput은 환경/타이밍에 따라 흔들릴 수 있으니 3~5회 반복 후 평균/분산을 같이 보길 권장");
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

        // 총 작업량(모든 모드에서 동일) -> 파생 지표 계산에 사용
        long totalOps = iters * (long) t;
        double totalMOps = totalOps / 1_000_000.0;

        System.out.println("Using threads=" + t + " (closest to cores=" + core + "), itersPerThread=" + iters
                + " (total=" + String.format("%.2f", totalMOps) + " M ops)");
        System.out.println();

        System.out.printf("%18s | %10s | %14s | %14s | %14s | %14s | %14s | %12s%n",
                "mode(yieldEvery)", "wall(ms)", "throughput", "cpuTime(ms)", "cpuUtil~(%)",
                "cpuMsPerMOps", "mopsPerCpuSec", "slowVsNONE");
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------------------------");

        // Baseline: yield 없음
        Result r0 = runCpuBound(t, iters, WorkMode.PLAIN, 0);
        long baseWall = Math.max(1, r0.wallMs);

        // 출력 helper
        java.util.function.BiConsumer<String, Result> printRow = (name, r) -> {
            // 비용 지표: 1M ops 처리당 CPU ms (낮을수록 좋음)
            double cpuMsPerMOps = (r.cpuMs > 0) ? (r.cpuMs / totalMOps) : Double.NaN;

            // 효율 지표: CPU 1초당 처리한 M ops (높을수록 좋음)
            double mopsPerCpuSec = (r.cpuMs > 0) ? (totalMOps / (r.cpuMs / 1000.0)) : Double.NaN;

            // 체감 비용: NONE 대비 wall 배수 (1.0이 baseline)
            double slowVsNone = r.wallMs / (double) baseWall;

            System.out.printf("%18s | %10d | %14.2f | %14.0f | %14.1f | %14.2f | %14.2f | %12.2f%n",
                    name, r.wallMs, r.throughputMOpsPerSec, r.cpuMs, r.cpuUtilApprox,
                    cpuMsPerMOps, mopsPerCpuSec, slowVsNone);
        };

        // Print baseline row
        printRow.accept("NONE", r0);

        // yieldEvery small => frequent yield (bad)
        Result r1 = runCpuBound(t, iters, WorkMode.YIELD, 1_000);
        printRow.accept("YIELD/1k", r1);

        Result r2 = runCpuBound(t, iters, WorkMode.YIELD, 10_000);
        printRow.accept("YIELD/10k", r2);

        Result r3 = runCpuBound(t, iters, WorkMode.YIELD, 100_000);
        printRow.accept("YIELD/100k", r3);

        System.out.println();
        System.out.println("해석 가이드(비용/효율 관점 추가):");
        System.out.println("- throughput(M ops/s): 초당 완료한 작업량(성과). 높을수록 좋음");
        System.out.println("- cpuTime(ms): 같은 총 작업량을 처리하는데 CPU가 실제로 쓴 시간(비용). 전환/오버헤드가 늘면 커질 수 있음");
        System.out.println("- cpuUtil~(%): CPU가 바빴던 정도(바쁨=성과 아님). 높아도 throughput이 낮을 수 있음");
        System.out.println("- cpuMsPerMOps: 1M ops당 CPU ms(비용). 낮을수록 효율적");
        System.out.println("- mopsPerCpuSec: CPU 1초당 처리한 M ops(효율). 높을수록 효율적");
        System.out.println("- slowVsNONE: NONE 대비 체감 시간 배수(벽시계). 1.00보다 크면 느려짐");
        System.out.println();
        System.out.println("배울 점:");
        System.out.println("- yield를 자주 할수록: slowVsNONE↑, cpuMsPerMOps↑, mopsPerCpuSec↓ 같은 패턴이 나오기 쉽다");
        System.out.println("- 즉, CPU는 더 바쁜데(비용↑) 실제 완료한 일은 줄어드는(성과↓) '스케줄링/전환 오버헤드'를 눈으로 확인할 수 있다");
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
