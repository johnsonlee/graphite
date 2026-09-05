import io.johnsonlee.graphite.diagnostic.QueryExecutionMarker;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VerifyMarkerClasses {
    public static void main(String[] args) throws Exception {
        Class<?> benchmark = Class.forName("io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark", false,
                VerifyMarkerClasses.class.getClassLoader());
        if (benchmark.getDeclaredMethods().length == 0) throw new AssertionError("No methods");
        if (!QueryExecutionMarker.parametersJson(Map.of()).equals("{}")) throw new AssertionError("Empty map");
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("z", "quote\" slash\\\n\t\u0000\u007f\u4e2d\ud83d\ude00");
        values.put("a", null);
        String actual = QueryExecutionMarker.parametersJson(values);
        String expected = "{\"a\":null,\"z\":\"quote\\\" slash\\\\\\n\\t\\u0000\\u007f\\u4e2d\\ud83d\\ude00\"}";
        if (!actual.equals(expected)) throw new AssertionError(actual);
        try {
            QueryExecutionMarker.parametersJson(Map.of("term", 42));
            throw new AssertionError("Unsupported type accepted");
        } catch (IllegalArgumentException expectedFailure) {
            // This public encoding check calls no executor and creates no JFR event.
        }
        if (QueryExecutionMarker.markerFailureCount() != 0L) throw new AssertionError("Marker failure");
        System.out.println("PASS JVM -Xverify:all class loading and empty/sorted/escaped/null/type-rejected parameter encoding; no query, graph or JFR recording");
    }
}
