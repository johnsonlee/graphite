import com.google.gson.JsonParser;
import io.johnsonlee.graphite.cypher.CypherGraph;
import io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark;
import io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureCounters;
import io.johnsonlee.graphite.webgraph.QueryCorrectnessManifest;
import io.johnsonlee.graphite.webgraph.QueryCorrectnessRecord;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A separate diagnostic lifecycle; the original executor, sampler and timers are unchanged. */
public final class MainLatencyCapture {
    static final String WARM = "warm-after-failed-prewarm";
    static final String GATE_MESSAGE = "A correctness oracle requires every query to succeed; incomplete results: four-or-graph-id-targeted=failed";
    static final String OUTPUT = "graphite.broad.pressure.output";
    static final String OBSERVATIONS = "graphite.broad.pressure.observations.output";

    static Object read(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    static Object call(Object owner, Class<?> type, String name, Class<?>[] signature, Object... args) throws Throwable {
        Method method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    static Object call(Object owner, String name) throws Throwable {
        return call(owner, owner.getClass(), name, new Class<?>[0]);
    }
    static void clearGraph(Object graph) throws Throwable {
        List<Method> methods = new ArrayList<>();
        for (Method method : graph.getClass().getDeclaredMethods()) {
            if (method.getName().startsWith("clearStringPropertyIndexes") && method.getParameterCount() == 0) methods.add(method);
        }
        if (methods.size() != 1) throw new IllegalStateException("ambiguous original index clear method");
        call(graph, graph.getClass(), methods.get(0).getName(), new Class<?>[0]);
    }
    static void write(Path path, Object value) throws Exception {
        Files.writeString(path, ExportBenchmarkCases.json(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
    static Map<String,Object> exception(Throwable error) {
        var record = new LinkedHashMap<String,Object>();
        record.put("class", error.getClass().getName());
        record.put("message", error.getMessage());
        record.put("stack", java.util.Arrays.stream(error.getStackTrace()).map(Object::toString).toList());
        return record;
    }
    static void checkGateFailure(Throwable error) {
        if (error.getClass() != IllegalStateException.class || !GATE_MESSAGE.equals(error.getMessage())) {
            throw new IllegalStateException("Unexpected original gate failure", error);
        }
    }
    static void unchanged(LargeBroadQueryPressureBenchmark benchmark, Object executor, Object sampler) throws Exception {
        if (executor != read(benchmark,"queryExecutor") || sampler != read(benchmark,"sampler")) {
            throw new IllegalStateException("original worker or sampler identity changed");
        }
    }
    static void outputs(Path out, String prefix) {
        System.setProperty(OUTPUT, out.resolve(prefix + "-correctness.tsv").toString());
        System.setProperty(OBSERVATIONS, out.resolve(prefix + "-observations.tsv").toString());
    }
    static void validateRecords(List<QueryCorrectnessRecord> records, List<QueryCorrectnessRecord> reference) {
        if (records.size() != 1267 || reference.size() != 1267) throw new IllegalStateException("incomplete diagnostic records");
        for (int i=0;i<1267;i++) {
            QueryCorrectnessRecord record=records.get(i);
            if (!record.equals(reference.get(i))) throw new IllegalStateException("diagnostic signature mismatch at " + i + "/" + record.getId());
            if (i==821) {
                if (!record.getId().equals("four-or-graph-id-targeted") || !record.getOutcome().equals("failed") ||
                    !record.getDigest().equals("java.lang.IllegalStateException") || record.getRowCount()!=0 || record.getResponseBytes()!=0) {
                    throw new IllegalStateException("known original failure changed");
                }
            } else if (!record.getOutcome().equals("success")) throw new IllegalStateException("additional original failure at " + i);
        }
    }
    static void prepareDiagnosticWarm(LargeBroadQueryPressureBenchmark benchmark, Object executor, Object sampler,
                                      Path out, List<QueryCorrectnessRecord> reference, Map<String,Object> status) throws Throwable {
        status.put("stage","diagnostic-prewarm");
        // Original setupInvocation warm body, split only to retain its local samples.
        for (Object graph : (List<?>) read(benchmark,"graphs")) clearGraph(graph);
        call(benchmark,"resetCallSiteScanMetrics");
        List<?> samples=(List<?>) call(benchmark, benchmark.getClass(), "replay", new Class<?>[]{boolean.class}, true);
        outputs(out,"warmup");
        call(benchmark,benchmark.getClass(),"writeCorrectnessManifest",new Class<?>[]{List.class},samples);
        call(benchmark,benchmark.getClass(),"writeObservations",new Class<?>[]{List.class},samples);
        List<QueryCorrectnessRecord> records=QueryCorrectnessManifest.INSTANCE.read(out.resolve("warmup-correctness.tsv"));
        write(out.resolve("warmup-records.json"),ExportBenchmarkCases.plain(records));
        Throwable gateFailure=null;
        try { call(benchmark,benchmark.getClass(),"enforceCorrectness",new Class<?>[]{List.class},samples); }
        catch (Throwable error) { gateFailure=error; status.put("prewarmOriginalGateFailure",exception(error)); }
        status.put("warmOriginalGatePassed",gateFailure==null);
        status.put("prewarmCapturedCaseCount",records.size());
        if (gateFailure==null) throw new IllegalStateException("original warm gate unexpectedly passed");
        checkGateFailure(gateFailure);
        validateRecords(records,reference);
        status.put("prewarmSignaturesMatchArchivedMain",true);
        unchanged(benchmark,executor,sampler);
    }
    public static void main(String[] args) throws Throwable {
        if (args.length!=4) throw new IllegalArgumentException("graphs.tsv cold|startup-prepared|warm-after-failed-prewarm new-output-directory reference-directory");
        String state=args[1];
        if (!Set.of("cold","startup-prepared",WARM).contains(state)) throw new IllegalArgumentException(state);
        Path out=Path.of(args[2]),referenceDir=Path.of(args[3]);
        Files.createDirectory(out);
        List<QueryCorrectnessRecord> reference=QueryCorrectnessManifest.INSTANCE.read(referenceDir.resolve("diagnostic-reference.tsv"));
        validateRecords(reference,reference);
        var expected=JsonParser.parseString(Files.readString(referenceDir.resolve("expected-workload.json"))).getAsJsonObject();
        System.setProperty("graphite.broad.pressure.graphs",args[0]);
        System.setProperty("graphite.broad.pressure.correctness.mode","record");
        outputs(out,"main");
        var benchmark=new LargeBroadQueryPressureBenchmark();
        benchmark.setGraphCount(64); benchmark.setTimeoutMillis(60000L); benchmark.setCoverageFamily("all");
        benchmark.setIndexState(state.equals(WARM)?"warm":state);
        var status=new LinkedHashMap<String,Object>();
        status.put("originalGatePassed",false); status.put("diagnosticReplayComplete",false);
        status.put("diagnosticWarmupContinued",false);
        status.put("formalWarmPrepared",false); status.put("warmOriginalGatePassed",state.equals(WARM)?false:null);
        status.put("stage","setupTrial");
        Object executor=null,sampler=null; Throwable failure=null;
        try {
            benchmark.setupTrial();
            executor=read(benchmark,"queryExecutor"); sampler=read(benchmark,"sampler");
            List<?> workload=(List<?>)read(benchmark,"workload"),sources=(List<?>)read(benchmark,"sources");
            if (workload.size()!=1267 || sources.size()!=64) throw new IllegalStateException("incomplete workload");
            List<String> sourceOrder=new ArrayList<>();
            for(Object source:sources) sourceOrder.add(((CypherGraph)source).getId());
            Object actualCases=ExportBenchmarkCases.plain(workload);
            if (!JsonParser.parseString(ExportBenchmarkCases.json(actualCases)).equals(expected.get("cases")) ||
                !JsonParser.parseString(ExportBenchmarkCases.json(sourceOrder)).equals(expected.get("sourceOrder"))) {
                throw new IllegalStateException("actual full workload/source order differs from frozen main");
            }
            write(out.resolve("actual-cases.json"),actualCases);
            var header=new LinkedHashMap<String,Object>();
            header.put("protocol","real64-original-timer-diagnostic-v2");
            header.put("mainRevision","4e328b0109e13c896b74004823fb049fcb19251a");
            header.put("state",state); header.put("originalBenchmarkIndexState",benchmark.getIndexState());
            header.put("caseCount",1267); header.put("graphCount",64); header.put("sourceOrder",sourceOrder);
            header.put("timeoutMillis",60000); header.put("coverageFamily","all");
            header.put("performanceMeasurement",true); header.put("diagnosticOnly",true); header.put("samplesPerCase",1);
            header.put("diagnosticWarmupContinued",state.equals(WARM));
            header.put("originalAllSuccessGateRequired",true); header.put("formalWarmPrepared",false);
            header.put("warmOriginalGatePassed",state.equals(WARM)?false:null);
            header.put("warmPreparation",state.equals(WARM)?"Original private warm replay and original gate, followed by explicitly separate diagnostic continuation":"Original setupInvocation");
            header.put("queryExecutorReplaced",false); header.put("queryExecutorClass",executor.getClass().getName());
            header.put("samplerDisabled",false); header.put("newQueryTimer",false);
            header.put("timingBoundary","Original replay: context/source selection, persistent executor dispatch, execution, Future.get; excludes canonicalization and graph observations");
            header.put("failedSampleMeaning","time-to-failure; excluded from successful latency percentiles");
            header.put("errorMessageSource","Archived full canonical main capture; current original samples record error class only");
            header.put("javaVersion",System.getProperty("java.version")); header.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
            write(out.resolve("header.json"),header);
            if (state.equals(WARM)) {
                prepareDiagnosticWarm(benchmark,executor,sampler,out,reference,status);
                // Original setupInvocation tail. Prewarm sample locals have left the
                // capture frame before the original GC boundary is entered.
                call(benchmark,"resetCallSiteScanMetrics");
                call(null,Class.forName("io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmarkKt"),
                     "forcePressureGc",new Class<?>[0]);
                call(sampler,"start");
                status.put("diagnosticWarmContinuationPrepared",true);
                status.put("diagnosticWarmupContinued",true);
            } else { status.put("stage","setupInvocation"); benchmark.setupInvocation(); }
            unchanged(benchmark,executor,sampler);
            outputs(out,"main"); status.put("stage","replayBroadQueries");
            Throwable formalFailure=null;
            var counters=new LargeBroadQueryPressureCounters();
            try {
                long consumed=benchmark.replayBroadQueries(counters);
                status.put("originalGatePassed",true); status.put("consumedBytesAndRows",consumed);
            } catch(Throwable error) { formalFailure=error; status.put("formalOriginalGateFailure",exception(error)); }
            write(out.resolve("original-counters.json"),ExportBenchmarkCases.plain(counters));
            if (formalFailure==null) throw new IllegalStateException("original formal gate unexpectedly passed");
            checkGateFailure(formalFailure);
            List<QueryCorrectnessRecord> records=QueryCorrectnessManifest.INSTANCE.read(out.resolve("main-correctness.tsv"));
            validateRecords(records,reference);
            status.put("formalCapturedCaseCount",records.size()); status.put("formalSignaturesMatchArchivedMain",true);
            status.put("diagnosticReplayComplete",true); status.put("stage","complete-original-gate-failed");
            throw formalFailure;
        } catch(Throwable error) {
            failure=error; status.put("terminalFailure",exception(error));
            status.put("exceptionClass",error.getClass().getName()); status.put("exceptionMessage",error.getMessage());
        }
        finally {
            try { if(executor!=null) { unchanged(benchmark,executor,sampler); status.put("queryExecutorIdentityUnchanged",true);status.put("samplerIdentityUnchanged",true); } }
            catch(Throwable error) { if(failure==null)failure=error;else failure.addSuppressed(error); }
            try { benchmark.tearDownTrial(); }
            catch(Throwable error) { if(failure==null)failure=error;else failure.addSuppressed(error); }
            status.put("correctnessManifestWritten",Files.isRegularFile(out.resolve("main-correctness.tsv")));
            status.put("observationsWritten",Files.isRegularFile(out.resolve("main-observations.tsv")));
            status.put("launcherSucceeded",failure==null);
            try { write(out.resolve("completion.json"),status); }
            catch(Throwable error) { if(failure==null)failure=error;else failure.addSuppressed(error); }
        }
        if(failure!=null)throw failure;
    }
}
