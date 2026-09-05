import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Mechanical offline export from existing recordings; no measured classes are loaded. */
public final class RawFrameDetails {
    static final String RAW = "io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection";
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Refuse overwrite");
        var events = new ArrayList<Object>();
        long cpuEvents = 0;
        try (var recording = new RecordingFile(input)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
                cpuEvents++;
                var trace = event.getStackTrace();
                if (trace == null || trace.getFrames().stream().noneMatch(frame -> ProfileWindows.methodName(frame.getMethod()).startsWith(RAW))) continue;
                var frames = new ArrayList<Object>();
                for (var frame : trace.getFrames()) {
                    frames.add(ProfileWindows.map("method", ProfileWindows.methodName(frame.getMethod()),
                        "lineNumber", frame.getLineNumber(), "bytecodeIndex", frame.getBytecodeIndex(),
                        "frameType", frame.getType(), "javaFrame", frame.isJavaFrame()));
                }
                events.add(ProfileWindows.map("timestamp", event.getStartTime(), "thread", ProfileWindows.threadKey(event),
                    "truncated", trace.isTruncated(), "framesLeafFirst", frames));
            }
        }
        Files.writeString(output, ProfileWindows.json(ProfileWindows.map("input", input.toAbsolutePath(),
            "cpuEventsAllRecording", cpuEvents, "rawFamilyCpuEvents", events.size(), "events", events)) + "\n");
        System.out.println("Exported " + events.size() + " raw CPU events of " + cpuEvents + " to " + output);
    }
}
