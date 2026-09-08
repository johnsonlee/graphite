import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.nio.file.*;
import java.util.*;

/** Actual public constructors and bounded execute; no substituted engine. */
public final class ConstructorOracle {
    public static void main(String[] args) throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad", "lazy");
        if (!System.getProperty("java.specification.version").equals("17")) throw new IllegalStateException("Java17 required");
        Gson json = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
        JsonArray cases = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        List<Object> records = new ArrayList<>();
        Graph graph = GraphStore.INSTANCE.loadMapped(Path.of(args[1], "locals"));
        try {
            for (JsonElement input : cases) {
                JsonObject spec = input.getAsJsonObject();
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("spec", spec);
                var signal = new CypherCancellationSignal();
                var reason = new CypherQueryCancelledException("constructor oracle cancellation");
                var context = new CypherExecutionContext(new CypherExecutionBudget(4), signal);
                record.put("cancelAccepted", signal.cancel(reason));
                record.put("columns", null);
                record.put("rows", null);
                String phase = "constructor";
                try {
                    String mode = spec.get("mode").getAsString();
                    String query = spec.get("query").getAsString();
                    int maxRows = spec.get("maxRows").getAsInt();
                    CypherResult result;
                    if (mode.equals("single-null")) {
                        var executor = new CypherExecutor((Graph) null, context);
                        phase = "execute";
                        result = executor.execute(query, Map.of(), maxRows);
                    } else {
                        List<CypherGraph> sources = new ArrayList<>();
                        if (mode.equals("cross-null")) {
                            phase = "source-constructor";
                            sources.add(new CypherGraph("g", null));
                        } else if (mode.equals("cross-duplicate")) {
                            sources.add(new CypherGraph("g", graph));
                            sources.add(new CypherGraph("g", graph));
                        } else if (!mode.equals("cross-empty")) {
                            throw new IllegalArgumentException(mode);
                        }
                        phase = "constructor";
                        var executor = new CrossGraphCypherExecutor(sources, context);
                        phase = "execute";
                        result = executor.execute(query, Map.of(), maxRows);
                    }
                    record.put("columns", result.getColumns());
                    record.put("rows", result.getRows());
                    record.put("outcome", "SUCCESS");
                } catch (Throwable failure) {
                    record.put("outcome", "FAILED");
                    record.put("error", failure.getClass().getSimpleName());
                    record.put("errorClass", failure.getClass().getName());
                    record.put("message", failure.getMessage());
                    record.put("stack", Arrays.stream(failure.getStackTrace()).map(Object::toString).toList());
                }
                record.put("phase", phase);
                record.put("diagnostics", context.getDiagnostics());
                record.put("cancelled", signal.isCancelled());
                record.put("reasonPreserved", signal.cancellationException() == reason);
                records.add(record);
            }
        } finally {
            ((java.io.Closeable) graph).close();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mainRevision", "4e328b0109e13c896b74004823fb049fcb19251a");
        output.put("javaVersion", System.getProperty("java.version"));
        output.put("performanceMeasurements", 0);
        output.put("cases", records);
        String result = json.toJson(output) + "\n";
        Files.writeString(Path.of(args[2]), result, StandardOpenOption.CREATE_NEW);
        System.out.print(result);
    }
}
