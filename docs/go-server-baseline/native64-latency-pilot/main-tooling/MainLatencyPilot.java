import io.johnsonlee.graphite.cypher.CypherGraph;
import io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark;
import io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureCounters;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original benchmark timing, without executor replacement or per-query observer. */
public final class MainLatencyPilot {
    static Object read(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    static void write(Path path, Object value) throws Exception {
        Files.writeString(path, ExportBenchmarkCases.json(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Throwable {
        if (args.length != 3) throw new IllegalArgumentException("graphs.tsv cold|startup-prepared new-output-directory");
        String state = args[1];
        if (!Set.of("cold", "startup-prepared").contains(state)) throw new IllegalArgumentException(state);
        Class.forName("io.johnsonlee.graphite.webgraph.QueryCorrectnessManifest");
        Path out = Path.of(args[2]);
        Files.createDirectory(out);
        System.setProperty("graphite.broad.pressure.graphs", args[0]);
        System.setProperty("graphite.broad.pressure.correctness.mode", "record");
        System.setProperty("graphite.broad.pressure.output", out.resolve("main-correctness.tsv").toString());
        System.setProperty("graphite.broad.pressure.observations.output", out.resolve("main-observations.tsv").toString());
        var benchmark = new LargeBroadQueryPressureBenchmark();
        benchmark.setGraphCount(64);
        benchmark.setTimeoutMillis(60000L);
        benchmark.setCoverageFamily("all");
        benchmark.setIndexState(state);
        var status = new LinkedHashMap<String, Object>();
        status.put("originalGatePassed", false);
        status.put("stage", "setupTrial");
        Object executor = null;
        Throwable failure = null;
        try {
            benchmark.setupTrial();
            executor = read(benchmark, "queryExecutor");
            List<?> workload = (List<?>) read(benchmark, "workload");
            List<?> sources = (List<?>) read(benchmark, "sources");
            if (workload.size() != 1267 || sources.size() != 64) throw new IllegalStateException("incomplete workload");
            List<String> sourceOrder = new ArrayList<>();
            for (Object source : sources) sourceOrder.add(((CypherGraph) source).getId());
            write(out.resolve("actual-cases.json"), ExportBenchmarkCases.plain(workload));
            var header = new LinkedHashMap<String, Object>();
            header.put("mainRevision", "4e328b0109e13c896b74004823fb049fcb19251a");
            header.put("state", state);
            header.put("caseCount", workload.size());
            header.put("graphCount", sources.size());
            header.put("sourceOrder", sourceOrder);
            header.put("timeoutMillis", 60000);
            header.put("coverageFamily", "all");
            header.put("performanceMeasurement", true);
            header.put("diagnosticOnly", true);
            header.put("samplesPerCase", 1);
            header.put("queryExecutorReplaced", false);
            header.put("queryExecutorClass", executor.getClass().getName());
            header.put("timingBoundary", "Original replay: context/source selection, persistent executor dispatch, execution, Future.get; excludes canonicalization and graph observations");
            header.put("javaVersion", System.getProperty("java.version"));
            header.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
            write(out.resolve("header.json"), header);
            status.put("stage", "setupInvocation");
            benchmark.setupInvocation();
            status.put("stage", "replayBroadQueries");
            long consumed = benchmark.replayBroadQueries(new LargeBroadQueryPressureCounters());
            status.put("originalGatePassed", true);
            status.put("consumedBytesAndRows", consumed);
            status.put("stage", "complete");
        } catch (Throwable error) {
            failure = error;
            status.put("exceptionClass", error.getClass().getName());
            status.put("exceptionMessage", error.getMessage());
        } finally {
            try {
                if (executor != null) {
                    boolean unchanged = executor == read(benchmark, "queryExecutor");
                    status.put("queryExecutorIdentityUnchanged", unchanged);
                    if (!unchanged) throw new IllegalStateException("original executor identity changed");
                }
            } catch (Throwable error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            try { benchmark.tearDownTrial(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            status.put("correctnessManifestWritten", Files.isRegularFile(out.resolve("main-correctness.tsv")));
            status.put("observationsWritten", Files.isRegularFile(out.resolve("main-observations.tsv")));
            status.put("launcherSucceeded", failure == null);
            try { write(out.resolve("completion.json"), status); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }
}
