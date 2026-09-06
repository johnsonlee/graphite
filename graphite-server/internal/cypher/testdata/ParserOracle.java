import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.johnsonlee.graphite.cypher.CypherDslAdapter;
import io.johnsonlee.graphite.cypher.CypherExecutor;
import io.johnsonlee.graphite.graph.Graph;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Source-only correctness oracle; no network, persisted graph, or timings. */
public class ParserOracle {
    public static void main(String[] args) throws Exception {
        Gson json = new GsonBuilder().serializeNulls().create();
        Graph empty = (Graph) Proxy.newProxyInstance(Graph.class.getClassLoader(), new Class<?>[]{Graph.class}, (proxy, method, arguments) -> {
            Class<?> type = method.getReturnType();
            if (type == Long.class) return 0L;
            if (type == List.class) return List.of();
            if (type == Set.class) return Set.of();
            if (type == Map.class) return Map.of();
            if (type == kotlin.sequences.Sequence.class) return kotlin.sequences.SequencesKt.emptySequence();
            throw new UnsupportedOperationException("Unexpected empty-graph access: " + method.getName());
        });
        CypherExecutor executor = new CypherExecutor(empty);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            String query = json.fromJson(line, String.class);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("query",query);
            try {
                var clauses = CypherDslAdapter.INSTANCE.parse(query);
                result.put("ast",clauses.toString());
                result.put("clauseTypes",clauses.stream().map(c -> c.getClass().getSimpleName()).toList());
            } catch (Throwable error) {
                result.put("parseError",error.getClass().getSimpleName());
                result.put("parseMessage",error.getMessage());
            }
            if (!result.containsKey("parseError")) {
                try {
                    var rows = executor.execute(query);
                    result.put("columns",rows.getColumns());
                    result.put("rows",rows.getRows());
                } catch (Throwable error) {
                    result.put("executionError",error.getClass().getSimpleName());
                    result.put("executionMessage",error.getMessage());
                }
            }
            System.out.println(json.toJson(result));
        }
    }
}
