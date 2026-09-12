import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Offline existing-recording export; never loads measured application classes. */
public final class RawFrameSensitivity {
    static final String RAW = "io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection";
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args[0]), output=Path.of(args[1]);
        if(Files.exists(output)) throw new IllegalArgumentException("Refuse overwrite");
        var events=new ArrayList<Object>();var traces=new ArrayList<Object>();
        long cpu=0;
        try(var recording=new RecordingFile(input)) {
            while(recording.hasMoreEvents()) {
                var e=recording.readEvent(); var type=e.getEventType().getName();
                if(type.equals("jdk.MethodTrace")) {
                    RecordedMethod method=e.hasField("method")?e.getValue("method"):null;
                    if(method==null && e.getStackTrace()!=null && !e.getStackTrace().getFrames().isEmpty()) method=e.getStackTrace().getFrames().get(0).getMethod();
                    traces.add(ProfileWindows.map("start",e.getStartTime(),"end",e.getEndTime(),"durationNanos",e.getDuration().toNanos(),"thread",ProfileWindows.threadKey(e),"method",ProfileWindows.methodName(method),"exactOuterExecute",ProfileWindows.queryTrace(e)));
                }
                if(!type.equals("jdk.ExecutionSample")) continue;
                cpu++;var trace=e.getStackTrace();
                if(trace==null || trace.getFrames().stream().noneMatch(f->ProfileWindows.methodName(f.getMethod()).startsWith(RAW))) continue;
                var frames=new ArrayList<Object>();
                for(var f:trace.getFrames()) frames.add(ProfileWindows.map("method",ProfileWindows.methodName(f.getMethod()),"lineNumber",f.getLineNumber(),"bytecodeIndex",f.getBytecodeIndex(),"frameType",f.getType(),"javaFrame",f.isJavaFrame()));
                events.add(ProfileWindows.map("timestamp",e.getStartTime(),"thread",ProfileWindows.threadKey(e),"truncated",trace.isTruncated(),"framesLeafFirst",frames));
            }
        }
        Files.writeString(output,ProfileWindows.json(ProfileWindows.map("input",input.toAbsolutePath(),"cpuEventsAllRecording",cpu,"methodTraces",traces,"rawFamilyCpuEvents",events.size(),"events",events))+"\n");
        System.out.println("Exported "+events.size()+" raw CPU events of "+cpu+" and "+traces.size()+" traces");
    }
}
