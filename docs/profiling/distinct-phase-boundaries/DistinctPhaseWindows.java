import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline refinement of already validated outer query windows; never changes the measured JAR. */
public final class DistinctPhaseWindows {
    static final String OWNER = "io.johnsonlee.graphite.cypher.QueryPipeline";
    static final String INITIAL = "executeIndexedDistinctStringProjection$projectSource";
    static final String PROVENANCE = "executeIndexedDistinctStringProjection$lambda$155$lambda$154";
    record Span(Instant start, Instant end) {}
    static String phase(RecordedEvent e) {
        if (!e.getEventType().getName().equals("jdk.MethodTrace")) return null;
        RecordedMethod m = e.hasField("method") ? e.getValue("method") : null;
        if (m == null && e.getStackTrace() != null && !e.getStackTrace().getFrames().isEmpty()) {
            m = e.getStackTrace().getFrames().get(0).getMethod();
        }
        if (m == null || !OWNER.equals(m.getType().getName().replace('/', '.'))) return null;
        return switch (m.getName()) { case INITIAL -> "initial"; case PROVENANCE -> "provenance"; default -> null; };
    }
    static boolean contains(Span s, Instant t) { return !t.isBefore(s.start) && t.isBefore(s.end); }
    static List<Span> union(List<Span> source) {
        var ordered = new ArrayList<>(source); ordered.sort(Comparator.comparing(Span::start));
        var result = new ArrayList<Span>();
        for (var s : ordered) {
            if (result.isEmpty() || s.start.isAfter(result.get(result.size()-1).end)) result.add(s);
            else {
                var previous = result.remove(result.size()-1);
                result.add(new Span(previous.start, previous.end.isAfter(s.end) ? previous.end : s.end));
            }
        }
        return result;
    }
    static long duration(List<Span> spans) { return spans.stream().mapToLong(s -> Duration.between(s.start,s.end).toNanos()).sum(); }
    static final class Query {
        final ProfileWindows.Window outer;
        final Map<String,List<Span>> spans = new LinkedHashMap<>();
        final Map<String,List<Object>> calls = new LinkedHashMap<>();
        final Map<String,Map<String,ProfileWindows.Metric>> metrics = new LinkedHashMap<>();
        Query(ProfileWindows.Window w) {
            outer=w;
            for (String p:List.of("initial","provenance","other")) { spans.put(p,new ArrayList<>()); calls.put(p,new ArrayList<>()); metrics.put(p,new LinkedHashMap<>()); }
        }
        boolean contains(Instant t) { return !t.isBefore(outer.start) && t.isBefore(outer.end); }
        String at(Instant t) {
            boolean a=spans.get("initial").stream().anyMatch(s->DistinctPhaseWindows.contains(s,t));
            boolean b=spans.get("provenance").stream().anyMatch(s->DistinctPhaseWindows.contains(s,t));
            if(a&&b)throw new IllegalArgumentException("Overlapping phases in "+outer.row.get("id"));
            return a?"initial":b?"provenance":"other";
        }
        void add(RecordedEvent e, String metric, long weight) {
            metrics.get(at(e.getStartTime())).computeIfAbsent(metric,k->new ProfileWindows.Metric(k.equals("cpuSamples")?"samples":"sampled-TLAB-or-outside-TLAB-bytes")).add(e,weight,false,true);
        }
        Object json() {
            long wall=Duration.between(outer.start,outer.end).toNanos();
            long initial=duration(spans.get("initial")), provenance=duration(spans.get("provenance"));
            if(initial+provenance>wall)throw new IllegalArgumentException("Phase duration conservation failure");
            var output=new LinkedHashMap<String,Object>();
            metrics.forEach((p,values)-> {var byMetric=new LinkedHashMap<String,Object>(); values.forEach((k,v)->byMetric.put(k,v.json()));output.put(p,byMetric);});
            return ProfileWindows.map("id",outer.row.get("id"),"outerDurationNanos",wall,"tsvLatencyNanos",Long.parseLong(outer.row.get("latencyNanos")),"untracedTsvNanos",Long.parseLong(outer.row.get("latencyNanos"))-wall,"initialUnionNanos",initial,"provenanceUnionNanos",provenance,"otherNanos",wall-initial-provenance,"calls",calls,"metrics",output);
        }
    }
    public static void main(String[] args) throws Exception {
        Path jfr=Path.of(args[0]),tsv=Path.of(args[1]),output=Path.of(args[2]);
        if(Files.exists(output))throw new IllegalArgumentException("Refuse overwrite");
        var scan=new ProfileWindows.Scan(jfr);
        if(!scan.sampleModes.equals(Set.of("cpu")))throw new IllegalArgumentException("Not exactly CPU mode");
        var rows=ProfileWindows.readTsv(tsv,34); var outer=scan.traces;
        if(outer.size()!=rows.size())throw new IllegalArgumentException("Outer trace count mismatch");
        outer.sort(Comparator.comparing(RecordedEvent::getStartTime)); var queries=new ArrayList<Query>();
        for(int i=0;i<outer.size();i++) {
            var w=new ProfileWindows.Window(outer.get(i),rows.get(i));
            long gap=Long.parseLong(w.row.get("latencyNanos"))-w.event.getDuration().toNanos();
            if(gap < -ProfileWindows.TOLERANCE_NS)throw new IllegalArgumentException("Outer trace exceeds TSV "+gap);
            if(i>0 && queries.get(i-1).outer.end.isAfter(w.start))throw new IllegalArgumentException("Outer overlap");
            queries.add(new Query(w));
        }
        int traceCount=0;
        try(var file=new RecordingFile(jfr)) {
            while(file.hasMoreEvents()) {
                var e=file.readEvent();String p=phase(e);if(p==null)continue;traceCount++;
                var q=queries.stream().filter(w->w.contains(e.getStartTime())).findFirst().orElseThrow(()->new IllegalArgumentException("Phase outside queries"));
                if(e.getDuration().isNegative() || e.getEndTime().isAfter(q.outer.end))throw new IllegalArgumentException("Phase crosses outer end");
                q.spans.get(p).add(new Span(e.getStartTime(),e.getEndTime()));
                q.calls.get(p).add(ProfileWindows.map("start",e.getStartTime(),"end",e.getEndTime(),"durationNanos",e.getDuration().toNanos(),"thread",ProfileWindows.threadKey(e)));
            }
        }
        if(traceCount==0)throw new IllegalArgumentException("No phase traces");
        for(var q:queries) {
            for(String p:List.of("initial","provenance"))q.spans.put(p,union(q.spans.get(p)));
            for(var a:q.spans.get("initial"))for(var b:q.spans.get("provenance")) {
                if(a.start.isBefore(b.end) && b.start.isBefore(a.end))throw new IllegalArgumentException("Initial/provenance overlap");
            }
        }
        try(var file=new RecordingFile(jfr)) {
            while(file.hasMoreEvents()) {
                var e=file.readEvent();var type=e.getEventType().getName();String metric;long weight;
                if(type.equals("jdk.ExecutionSample")){metric="cpuSamples";weight=1;}
                else if(type.equals("jdk.ObjectAllocationInNewTLAB")){metric="allocationSampledBytes";weight=e.getLong("tlabSize");}
                else if(type.equals("jdk.ObjectAllocationOutsideTLAB")){metric="allocationSampledBytes";weight=e.getLong("allocationSize");}
                else continue;
                for(var q:queries)if(q.contains(e.getStartTime())) {q.add(e,metric,weight);break;}
            }
        }
        Files.writeString(output,ProfileWindows.json(ProfileWindows.map("jfr",jfr.toAbsolutePath(),"queryCount",queries.size(),"phaseTraceCount",traceCount,"attribution","Union of traced phase call intervals across all threads; CPU/alloc assigned by sample timestamp, background activity co-occurs and is not attributed causally to a phase. Concurrent calls are not summed as wall time. Profiling-only timings are not acceptance evidence.","queries",queries.stream().map(Query::json).toList()))+"\n");
        System.out.println("Validated "+queries.size()+" queries and "+traceCount+" phase traces: "+output);
    }
}
