package io.johnsonlee.graphite.diagnostic;

import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor;
import io.johnsonlee.graphite.cypher.CypherResult;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Offline benchmark-only hook. Never installed as an agent or a production-class transform. */
public final class QueryExecutionMarker {
    private static final AtomicLong ORDINAL = new AtomicLong();
    private static final AtomicLong MARKER_FAILURES = new AtomicLong();
    private QueryExecutionMarker() {}

    @Name("graphite.diagnostic.QueryExecution")
    @Label("Frozen benchmark query execute window")
    @Category({"Graphite", "Offline diagnostic"})
    @Enabled(true)
    @StackTrace(false)
    @Threshold("0 ns")
    public static final class QueryWindow extends Event {
        public long ordinal;
        public String query;
        public String parametersJson;
        public String parameterEncoding;
        public long javaThreadId;
        public String javaThreadName;
        public boolean success;
        public String exceptionClass;
        public long markerFailuresBefore;
    }

    public static CypherResult execute(CrossGraphCypherExecutor executor, String query,
                                       Map<String, ?> parameters) throws Throwable {
        QueryWindow event = prepare(query, parameters);
        Throwable original = null;
        try {
            return executor.execute(query, parameters);
        } catch (Throwable failure) {
            original = failure;
            throw failure;
        } finally {
            finish(event, original);
        }
    }

    private static QueryWindow prepare(String query, Map<String, ?> parameters) {
        long ordinal = ORDINAL.incrementAndGet();
        try {
            QueryWindow event = new QueryWindow();
            event.ordinal = ordinal;
            event.query = query;
            event.parametersJson = parametersJson(parameters);
            event.parameterEncoding = "sorted-string-null-json-v1";
            Thread thread = Thread.currentThread();
            event.javaThreadId = thread.getId();
            event.javaThreadName = thread.getName();
            event.markerFailuresBefore = MARKER_FAILURES.get();
            event.begin();
            return event;
        } catch (Throwable markerFailure) {
            MARKER_FAILURES.incrementAndGet();
            return null;
        }
    }

    private static void finish(QueryWindow event, Throwable original) {
        if (event == null) return;
        try {
            event.end();
            event.success = original == null;
            event.exceptionClass = original == null ? "" : original.getClass().getName();
            event.commit();
        } catch (Throwable markerFailure) {
            // Preserve the original result/Throwable, never replace it with marker cleanup failure.
            // A missing event, ordinal gap or nonzero failure field invalidates the diagnostic run.
            MARKER_FAILURES.incrementAndGet();
        }
    }

    public static long markerFailureCount() { return MARKER_FAILURES.get(); }

    /** Exact supported input values, sorted keys, standard ASCII JSON (no Map.toString ambiguity). */
    public static String parametersJson(Map<String, ?> parameters) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : parameters.entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || (entry.getValue() != null && !(entry.getValue() instanceof String))) {
                throw new IllegalArgumentException("Marker supports only String keys and String/null values");
            }
            sorted.put((String) entry.getKey(), entry.getValue());
        }
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) out.append(',');
            first = false;
            quote(out, entry.getKey());
            out.append(':');
            if (entry.getValue() == null) out.append("null");
            else quote(out, (String) entry.getValue());
        }
        return out.append('}').toString();
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32 || c >= 127) {
                        out.append("\\u").append(hex[(c >>> 12) & 15]).append(hex[(c >>> 8) & 15])
                                .append(hex[(c >>> 4) & 15]).append(hex[c & 15]);
                    } else out.append(c);
            }
        }
        out.append('"');
    }
}
