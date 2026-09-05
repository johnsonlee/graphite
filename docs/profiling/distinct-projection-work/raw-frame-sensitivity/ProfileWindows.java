import jdk.jfr.*;
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.io.*;
import java.security.*;

/** Offline Java 17 analyzer: java ProfileWindows input.jfr observations.tsv output-directory */
public final class ProfileWindows {
    static final String EXECUTOR = "io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor";
    static final String DESCRIPTOR = "(Ljava/lang/String;Ljava/util/Map;)Lio/johnsonlee/graphite/cypher/CypherResult;";
    static final int EXPECTED = 34, TOP = 15;
    static final long TOLERANCE_NS = 1_000_000;
    static final Set<String> BLOCKING = Set.of("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark", "profiler.NativeLock");
    static Map<String,Object> map(Object... entries) {
        var m = new LinkedHashMap<String,Object>();
        for (int i=0;i<entries.length;i+=2) m.put((String)entries[i], entries[i+1]);
        return m;
    }
    static final class Window {
        final RecordedEvent event; final Map<String,String> row; final Instant start, end;
        final Map<String,Metric> metrics = new TreeMap<>();
        Window(RecordedEvent e, Map<String,String> r) {event=e;row=r;start=e.getStartTime();end=e.getEndTime();}
        Metric metric(String name, String unit) {return metrics.computeIfAbsent(name,k->new Metric(unit));}
    }
    static final class Metric {
        final String unit;
        long events, weight, missingStackEvents, truncatedStackEvents, boundaryBatchEvents;
        final Map<String,Long> inclusive = new HashMap<>(), leaf = new HashMap<>(), stacks = new HashMap<>();
        final Map<String,Long> eventTypes = new TreeMap<>(), states = new TreeMap<>(), allocationClasses = new HashMap<>();
        final Map<String,Metric> threads = new TreeMap<>();
        Metric(String u) {unit=u;}
        void add(RecordedEvent e, long w, boolean batchCrosses, boolean withThreads) {
            if(w<0) throw new IllegalArgumentException("Negative weight for " + e.getEventType().getName());
            events++;weight=Math.addExact(weight,w);
            eventTypes.merge(e.getEventType().getName(),1L,Long::sum);
            String state = str(e,"state"); if(state!=null) states.merge(state,w,Long::sum);
            if(batchCrosses) boundaryBatchEvents++;
            if(e.hasField("objectClass")) {
                RecordedClass c=e.getValue("objectClass");
                if(c!=null) allocationClasses.merge(c.getName(),w,Long::sum);
            }
            var frames = frames(e);
            var stack=e.getStackTrace();
            if(stack==null || frames.isEmpty()) missingStackEvents++;
            if(stack!=null && stack.isTruncated()) truncatedStackEvents++;
            if(frames.isEmpty()) frames=List.of("[no stack]");
            // Each method is charged at most once per event, even with recursion.
            for(String f:new HashSet<>(frames)) inclusive.merge(f,w,Long::sum);
            leaf.merge(frames.get(0),w,Long::sum);
            var reverse = new ArrayList<>(frames); Collections.reverse(reverse);
            stacks.merge(String.join(";",reverse),w,Long::sum);
            if(withThreads) threads.computeIfAbsent(threadKey(e),k->new Metric(unit)).add(e,w,batchCrosses,false);
        }
        Object json() {
            var byThread=new LinkedHashMap<String,Object>(); threads.forEach((k,v)->byThread.put(k,v.json()));
            return map("unit",unit,"eventCount",events,"weight",weight,"eventTypes",eventTypes,
                "missingStackEvents",missingStackEvents,"truncatedStackEvents",truncatedStackEvents,
                "wallBatchCrossingBoundaryEvents",boundaryBatchEvents,"stateWeights",states,
                "topInclusiveFrames",top(inclusive),"topLeafFrames",top(leaf),"topStacks",top(stacks),
                "topAllocationClasses",top(allocationClasses),"threads",byThread);
        }
    }
    static List<Object> top(Map<String,Long> values) {
        return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(TOP).map(e->(Object)map("name",e.getKey(),"weight",e.getValue())).toList();
    }
    static String str(RecordedObject e,String field) {return e.hasField(field)?e.getString(field):null;}
    static long number(RecordedObject e,String field,long fallback) {
        if(!e.hasField(field))return fallback;
        Object n=e.getValue(field); return n instanceof Number ? ((Number)n).longValue():fallback;
    }
    static RecordedThread thread(RecordedEvent e) {
        if(e.hasField("sampledThread")) return e.getThread("sampledThread");
        return e.hasField("eventThread")?e.getThread():null;
    }
    static String threadKey(RecordedEvent e) {
        var t=thread(e); return t==null?"[unknown thread]":String.valueOf(t.getJavaName()!=null?t.getJavaName():t.getOSName())+" [java="+t.getJavaThreadId()+",os="+t.getOSThreadId()+"]";
    }
    static String methodName(RecordedMethod m) {
        if(m==null)return "[unknown method]";
        String owner=m.getType()==null?"":m.getType().getName().replace('/','.');
        return (owner.isEmpty()?"":owner+".")+m.getName()+(m.getDescriptor()==null?"":m.getDescriptor());
    }
    static List<String> frames(RecordedEvent e) {
        var st=e.getStackTrace(); if(st==null)return List.of();
        return st.getFrames().stream().map(f->methodName(f.getMethod()).replace(';',':').replace('\n',' ')).toList();
    }
    static boolean exactMethod(RecordedMethod m) {
        return m!=null && m.getType()!=null && m.getType().getName().replace('/','.').equals(EXECUTOR)
            && m.getName().equals("execute") && DESCRIPTOR.equals(m.getDescriptor());
    }
    static boolean queryTrace(RecordedEvent e) {
        if(!e.getEventType().getName().equals("jdk.MethodTrace"))return false;
        var t=thread(e); if(t==null || !"broad-query-pressure-worker".equals(t.getJavaName()))return false;
        RecordedMethod m=e.hasField("method")?e.getValue("method"):null;
        if(m!=null)return exactMethod(m);
        var st=e.getStackTrace();return st!=null && !st.getFrames().isEmpty() && exactMethod(st.getFrames().get(0).getMethod());
    }
    static List<Map<String,String>> readTsv(Path path, int expected) throws IOException {
        var lines=Files.readAllLines(path); if(lines.isEmpty())throw new IllegalArgumentException("Empty TSV");
        String[] header=lines.get(0).split("\t",-1);
        if(new HashSet<>(List.of(header)).size()!=header.length)throw new IllegalArgumentException("Duplicate TSV column");
        var rows=new ArrayList<Map<String,String>>();var ids=new HashSet<String>();
        for(int i=1;i<lines.size();i++) {
            if(lines.get(i).isBlank())continue;
            String[] cols=lines.get(i).split("\t",-1);
            if(cols.length!=header.length)throw new IllegalArgumentException("TSV width at line "+(i+1));
            var row=new LinkedHashMap<String,String>();for(int j=0;j<header.length;j++)row.put(header[j],cols[j]);
            if(row.get("id")==null || !row.get("id").matches("[A-Za-z0-9_-]+") || !ids.add(row.get("id")))throw new IllegalArgumentException("Missing, unsafe, or duplicate TSV ID");
            if(!"success".equals(row.get("outcome")))throw new IllegalArgumentException("Non-success TSV row "+row.get("id"));
            if(Long.parseLong(row.get("latencyNanos"))<=0)throw new IllegalArgumentException("Nonpositive TSV latency");
            if(Long.parseLong(row.get("rowCount"))<0)throw new IllegalArgumentException("Negative TSV rowCount");
            rows.add(row);
        }
        if(rows.size()!=expected)throw new IllegalArgumentException("Expected "+expected+" TSV rows, found "+rows.size());
        return rows;
    }
    static String sha(Path p) throws Exception {
        var d=MessageDigest.getInstance("SHA-256"); try(var in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        return HexFormat.of().formatHex(d.digest());
    }
    static String json(Object v) {
        if(v==null)return "null";
        if(v instanceof Number || v instanceof Boolean)return v.toString();
        if(v instanceof Map<?,?> m) {var j=new StringJoiner(",","{","}");m.forEach((k,x)->j.add(json(k.toString())+":"+json(x)));return j.toString();}
        if(v instanceof Collection<?> a) {var j=new StringJoiner(",","[","]");a.forEach(x->j.add(json(x)));return j.toString();}
        String s=v.toString();var b=new StringBuilder("\"");for(char c:s.toCharArray())switch(c) {
            case '"' -> b.append("\\\"");case '\\' -> b.append("\\\\");case '\n' -> b.append("\\n");case '\r' -> b.append("\\r");case '\t' -> b.append("\\t");
            default -> {if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}
        }return b.append('"').toString();
    }
    static boolean inside(Instant point,Window w) {return !point.isBefore(w.start) && point.isBefore(w.end);}
    static long overlap(RecordedEvent e, Window w) {
        Instant a=e.getStartTime().isAfter(w.start)?e.getStartTime():w.start;
        Instant b=e.getEndTime().isBefore(w.end)?e.getEndTime():w.end;
        return b.isAfter(a)?Duration.between(a,b).toNanos():0;
    }
    static final class Scan {
        final List<RecordedEvent> traces=new ArrayList<>();
        final Map<String,Long> eventCounts=new TreeMap<>();
        final Map<String,Object> schemas=new TreeMap<>();
        final List<Object> settings=new ArrayList<>();
        final Set<String> sampleModes=new HashSet<>(), requestThreads=new HashSet<>();
        final Map<String,List<RecordedEvent>> requestEvents=new HashMap<>();
        RecordedEvent jvmInfo; Instant first,last;
        Scan(Path path) throws IOException {
            try(var recording=new RecordingFile(path)) {
                for(var et:recording.readEventTypes())schemas.put(et.getName(),et.getFields().stream().map(f->map("name",f.getName(),"type",f.getTypeName())).toList());
                while(recording.hasMoreEvents()) {
                    var e=recording.readEvent();String type=e.getEventType().getName();eventCounts.merge(type,1L,Long::sum);
                    if(first==null || e.getStartTime().isBefore(first))first=e.getStartTime();
                    if(last==null || e.getEndTime().isAfter(last))last=e.getEndTime();
                    if(queryTrace(e))traces.add(e);
                    if(type.equals("jdk.JVMInformation")) {
                        if(jvmInfo!=null && (number(jvmInfo,"pid",-1)!=number(e,"pid",-1) || !Objects.equals(str(jvmInfo,"jvmArguments"),str(e,"jvmArguments")) || !Objects.equals(str(jvmInfo,"javaArguments"),str(e,"javaArguments"))))throw new IllegalArgumentException("Inconsistent JVM identity in one recording");
                        jvmInfo=e;
                    }
                    var t=thread(e);
                    if(t!=null && "broad-query-pressure-worker".equals(t.getJavaName())) {
                        requestThreads.add(threadKey(e));requestEvents.computeIfAbsent(threadKey(e),k->new ArrayList<>()).add(e);
                    }
                    if(type.equals("jdk.ActiveSetting")) {
                        String name=str(e,"name"),value=str(e,"value");
                        settings.add(map("id",number(e,"id",-1),"name",name,"value",value));
                        if("event".equals(name) && value!=null)sampleModes.add(value);
                    }
                }
            }
        }
    }
    static List<String> catalogIds(Path path) throws IOException {
        var lines=Files.readAllLines(path).stream().filter(l->!l.isBlank()).toList();
        if(lines.isEmpty())throw new IllegalArgumentException("Empty catalog");
        List<String> ids;
        var header=List.of(lines.get(0).split("\t",-1));int idIndex=header.indexOf("id");
        if(idIndex>=0) {
            var values=new ArrayList<String>();
            for(String line:lines.subList(1,lines.size())) {
                String[] cells=line.split("\t",-1);
                if(cells.length!=header.size())throw new IllegalArgumentException("Catalog TSV width mismatch");
                values.add(cells[idIndex]);
            }
            ids=values;
        } else ids=lines;
        if(ids.isEmpty() || new HashSet<>(ids).size()!=ids.size() || ids.stream().anyMatch(id->!id.matches("[A-Za-z0-9_-]+")))throw new IllegalArgumentException("Invalid or duplicate catalog IDs");
        return ids;
    }
    static Object validateCompanion(Scan input,Scan source,List<Window> windows) {
        if(input.jvmInfo==null || source.jvmInfo==null)throw new IllegalArgumentException("Companion alignment requires JVMInformation in both recordings");
        long pid=number(input.jvmInfo,"pid",-1);
        if(pid<=0 || pid!=number(source.jvmInfo,"pid",-1))throw new IllegalArgumentException("Companion JVM PID mismatch");
        for(String f:List.of("jvmArguments","javaArguments")) {
            if(str(input.jvmInfo,f)==null || !Objects.equals(str(input.jvmInfo,f),str(source.jvmInfo,f)))throw new IllegalArgumentException("Companion JVM arguments mismatch: "+f);
        }
        Instant inputBoot=input.jvmInfo.getInstant("jvmStartTime"),sourceBoot=source.jvmInfo.getInstant("jvmStartTime");
        long bootDelta=Duration.between(sourceBoot,inputBoot).toNanos();
        if(Math.abs(bootDelta)>1_000_000_000L)throw new IllegalArgumentException("Companion JVM start times differ by more than 1 second");
        if(input.first==null || input.first.isAfter(windows.get(0).start) || input.last.isBefore(windows.get(windows.size()-1).end))throw new IllegalArgumentException("Companion recording timestamps do not cover all query windows");
        var matched=new TreeSet<String>();
        for(var w:windows) {
            String key=threadKey(w.event);
            if(!input.requestThreads.contains(key))throw new IllegalArgumentException("Companion request thread identity missing: "+key);
            matched.add(key);
        }
        for(String key:matched) {
            boolean contemporaneous=input.requestEvents.get(key).stream().anyMatch(e->windows.stream().anyMatch(w->overlap(e,w)>0 || inside(e.getStartTime(),w)));
            if(!contemporaneous)throw new IllegalArgumentException("Companion request thread has no event contemporaneous with any query window: "+key);
        }
        return map("passed",true,"pid",pid,"jvmArgumentsExactMatch",true,"javaArgumentsExactMatch",true,
            "jvmStartTimeDeltaNanos",bootDelta,"jvmStartTimeToleranceNanos",1_000_000_000L,"inputFirstEvent",input.first,"inputLastEvent",input.last,
            "matchedRequestThreads",matched,"clockAdjustmentNanos",0,
            "note","Exact PID/arguments/request Java+OS thread identity; overlapping absolute timestamps. JVM start-time definitions can differ between producers; no clock correction applied.");
    }
    static boolean jvmActivity(String type) {
        return type.startsWith("jdk.GCPhase") || type.startsWith("jdk.Safepoint") || Set.of("jdk.GarbageCollection","jdk.YoungGarbageCollection","jdk.OldGarbageCollection",
            "jdk.Compilation","jdk.CompilerPhase","jdk.CompilerInlining","jdk.Deoptimization","jdk.CompilationFailure","jdk.CodeCacheFull","jdk.ExecuteVMOperation").contains(type);
    }
    public static void main(String[] args) throws Exception {
        if(args.length<3)throw new IllegalArgumentException("Usage: java ProfileWindows input.jfr observations.tsv output-directory [--expected-count N] [--catalog ids.txt|catalog.tsv] [--window-source native.jfr]");
        Path jfr=Path.of(args[0]).toAbsolutePath(),tsv=Path.of(args[1]).toAbsolutePath(),out=Path.of(args[2]).toAbsolutePath();
        Path windowSource=jfr,catalog=null;int expected=EXPECTED;var options=new HashSet<String>();
        for(int i=3;i<args.length;i+=2) {
            if(i+1>=args.length || !options.add(args[i]))throw new IllegalArgumentException("Missing value or duplicate option: "+args[i]);
            switch(args[i]) {
                case "--expected-count" -> {expected=Integer.parseInt(args[i+1]);if(expected<=0)throw new IllegalArgumentException("Expected count must be positive");}
                case "--catalog" -> catalog=Path.of(args[i+1]).toAbsolutePath();
                case "--window-source" -> windowSource=Path.of(args[i+1]).toAbsolutePath();
                default -> throw new IllegalArgumentException("Unknown option: "+args[i]);
            }
        }
        if(Files.exists(out))throw new IllegalArgumentException("Output already exists: "+out);
        var rows=readTsv(tsv,expected);
        if(catalog!=null && !catalogIds(catalog).equals(rows.stream().map(r->r.get("id")).toList()))throw new IllegalArgumentException("Catalog IDs/count/order differ from observation TSV");
        var input=new Scan(jfr);boolean companion=!Files.isSameFile(jfr,windowSource);
        var source=companion?new Scan(windowSource):input;
        var traces=source.traces;var eventCounts=input.eventCounts;var schemas=input.schemas;var settings=input.settings;var sampleModes=input.sampleModes;
        if(traces.size()!=expected)throw new IllegalArgumentException("Expected "+expected+" exact execute MethodTrace windows, found "+traces.size());
        traces.sort(Comparator.comparing(RecordedEvent::getStartTime));
        var windows=new ArrayList<Window>();long maxGap=Long.MIN_VALUE,minGap=Long.MAX_VALUE,totalGap=0;
        for(int i=0;i<expected;i++) {
            var w=new Window(traces.get(i),rows.get(i));long duration=w.event.getDuration().toNanos(),latency=Long.parseLong(w.row.get("latencyNanos")),gap=latency-duration;
            if(duration<=0)throw new IllegalArgumentException("Nonpositive trace duration at "+i);
            if(i>0 && windows.get(i-1).end.isAfter(w.start))throw new IllegalArgumentException("Overlapping trace windows at "+i);
            if(gap < -TOLERANCE_NS)throw new IllegalArgumentException("Trace exceeds TSV by "+(-gap)+" ns for "+w.row.get("id"));
            minGap=Math.min(minGap,gap);maxGap=Math.max(maxGap,gap);totalGap+=gap;windows.add(w);
        }
        Object alignment=companion?validateCompanion(input,source,windows):map("mode","same input recording");
        // async-profiler's engine=wall may implement event=cpu on macOS. Event setting determines semantics.
        String executionMetric=companion && !schemas.containsKey("profiler.WallClockSample")?"jdkCpuSamples":sampleModes.equals(Set.of("cpu"))?"cpuSamples":sampleModes.equals(Set.of("wall"))?"wallExecutionSamples":"executionSamplesUnclassified";
        var attributedCounts=new TreeMap<String,Long>();
        try(var recording=new RecordingFile(jfr)) {
            while(recording.hasMoreEvents()) {
                var e=recording.readEvent();String type=e.getEventType().getName();
                boolean blocking=BLOCKING.contains(type),activity=jvmActivity(type);
                boolean durationEvent=blocking || (activity && !e.getDuration().isZero());
                if(!blocking && !activity && !Set.of("jdk.ExecutionSample","jdk.NativeMethodSample","profiler.WallClockSample","jdk.ObjectAllocationInNewTLAB","jdk.ObjectAllocationOutsideTLAB","jdk.ObjectAllocationSample").contains(type))continue;
                boolean attributed=false;
                for(var w:windows) {
                    long overlap=durationEvent?overlap(e,w):0;
                    if(durationEvent?overlap==0:!inside(e.getStartTime(),w))continue;
                    attributed=true;
                    if(activity)w.metric("jvmActivity."+type+(durationEvent?".overlapNanos":".events"),durationEvent?"overlapped-event-nanoseconds":"events").add(e,durationEvent?overlap:1,false,true);
                    else if(blocking)w.metric("blockingNanos","overlapped-thread-nanoseconds").add(e,overlap,false,true);
                    else if(type.equals("jdk.ExecutionSample"))w.metric(executionMetric,"samples").add(e,1,false,true);
                    else if(type.equals("jdk.NativeMethodSample"))w.metric("jdkNativeSamples","samples").add(e,1,false,true);
                    else if(type.equals("jdk.ObjectAllocationSample")) {
                        long bytes=number(e,"weight",-1);if(bytes<0)throw new IllegalArgumentException("Missing JDK allocation sample weight");
                        w.metric("jdkAllocationSampledBytes","JDK-recorded-allocation-sample-weight-bytes").add(e,bytes,false,true);
                    }
                    else if(type.equals("profiler.WallClockSample")) {
                        long count=number(e,"samples",1);
                        long span=e.hasField("timeSpan")?e.getDuration("timeSpan").toNanos():0;
                        // A batch timestamp is attributable; exact constituent sample times are unavailable.
                        boolean crosses=span>0 && (e.getStartTime().minusNanos(span).isBefore(w.start) || e.getStartTime().plusNanos(span).isAfter(w.end));
                        w.metric("wallSamples","represented-samples").add(e,count,crosses,true);
                        w.metric("wallRepresentedSpanNanos","recorded-batch-span-nanoseconds").add(e,span,crosses,true);
                    } else {
                        String field=type.equals("jdk.ObjectAllocationInNewTLAB")?"tlabSize":"allocationSize";
                        long bytes=number(e,field,-1);if(bytes<0)throw new IllegalArgumentException("Missing allocation weight "+field);
                        w.metric("allocationSampledBytes","sampled-TLAB-or-outside-TLAB-bytes").add(e,bytes,false,true);
                    }
                }
                if(attributed)attributedCounts.merge(type,1L,Long::sum);
            }
        }
        var queryJson=new ArrayList<Object>();Files.createDirectories(out.resolve("collapsed"));
        for(int i=0;i<windows.size();i++) {
            var w=windows.get(i);var metrics=new TreeMap<String,Object>();var collapsed=new TreeMap<String,String>();
            for(var entry:w.metrics.entrySet()) {
                metrics.put(entry.getKey(),entry.getValue().json());
                Path file=out.resolve("collapsed").resolve(String.format("%02d-%s.%s.collapsed",i+1,w.row.get("id"),entry.getKey()));
                try(var writer=Files.newBufferedWriter(file)) {
                    for(var te:entry.getValue().threads.entrySet())for(var stack:new TreeMap<>(te.getValue().stacks).entrySet()) {
                        writer.write(te.getKey().replace(';',':').replace('\n',' ')+";"+stack.getKey()+" "+stack.getValue()+"\n");
                    }
                }
                collapsed.put(entry.getKey(),out.relativize(file).toString());
            }
            long latency=Long.parseLong(w.row.get("latencyNanos")),duration=w.event.getDuration().toNanos();
            queryJson.add(map("ordinal",i+1,"id",w.row.get("id"),"shape",w.row.get("shape"),"projection",w.row.get("projection"),
                "workloadIdentity",w.row.get("workloadIdentity"),"outcome",w.row.get("outcome"),"rowCount",Long.parseLong(w.row.get("rowCount")),"digest",w.row.get("digest"),
                "start",w.start,"end",w.end,"requestThread",threadKey(w.event),"traceDurationNanos",duration,"tsvLatencyNanos",latency,
                "latencyGapNanos",latency-duration,"latencyGapFraction",(double)(latency-duration)/latency,"metrics",metrics,"collapsed",collapsed));
        }
        var result=map("schemaVersion",2,"jfr",jfr,"jfrSha256",sha(jfr),"tsv",tsv,"tsvSha256",sha(tsv),
            "windowSource",windowSource,"windowSourceSha256",sha(windowSource),"companionAlignment",alignment,
            "catalog",catalog,"catalogSha256",catalog==null?null:sha(catalog),
            "validation",map("passed",true,"queryCount",expected,"expectedCount",expected,"catalogOrderChecked",catalog!=null,"allTsvOutcomesSuccess",true,"nonoverlapping",true,
                "binding","chronological exact-signature MethodTrace windows paired ordinally with sequential replay TSV; JFR does not contain query IDs",
                "durationToleranceNanos",TOLERANCE_NS,"minLatencyGapNanos",minGap,"maxLatencyGapNanos",maxGap,"totalLatencyGapNanos",totalGap),
            "semantics",List.of("Point events use [query start, query end); all sampled threads are retained, including unrelated JVM activity, so time co-occurrence is not causality.",
                "Blocking events use duration intersection with each window; summed thread nanoseconds can exceed query wall time. A spanning event may count in multiple query windows.",
                "Allocation weights are recorded tlabSize for NewTLAB and allocationSize for OutsideTLAB. These are sampled weighted bytes, not actual whole-query allocations or exact object attribution.",
                "ExecutionSample classification uses ActiveSetting event, not engine. Missing or ambiguous mode remains unclassified; CPU samples are not wall samples or exact CPU nanoseconds.",
                "WallClockSample uses samples as count and records timeSpan separately. Batches are bound by timestamp; exact constituent sample times/direction are unknown, so possible crossing of either window boundary is conservatively flagged.",
                "Inclusive methods deduplicate recursive occurrences per event; collapsed stacks retain recursion. Inclusive weights across different methods must not be summed.",
                "Each JVM GC/safepoint/JIT event type is reported separately. Nested/parallel phases overlap and must not be summed into total pause time. Companion input events are never merged with native-source samples.",
                "No events may mean no samples, an absent event stream, or disabled/thresholded recording; inspect eventCounts and activeSettings."),
            "executionSampleMetric",executionMetric,"activeSettings",settings,"eventCounts",eventCounts,"attributedEventCounts",attributedCounts,"eventSchemas",schemas,"queries",queryJson);
        Files.writeString(out.resolve("analysis.json"),json(result)+"\n",StandardCharsets.UTF_8);
        System.out.println(json(map("output",out.resolve("analysis.json"),"queryCount",windows.size(),"executionSampleMetric",executionMetric,"attributedEventCounts",attributedCounts,"minGapNanos",minGap,"maxGapNanos",maxGap)));
    }
}
