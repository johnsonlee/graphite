import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Reads only frozen benchmark workload constructors and the fixture manifest; never loads a graph or executes Cypher. */
public final class ExportOriginalCatalog {
    static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> owner = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target instanceof Class<?> ? null : target, args);
    }
    static Object get(Object target, String name) throws Exception { return invoke(target, name, new Class<?>[0]); }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> values) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            values.forEach((k,v) -> out.add(json(k.toString()) + ":" + json(v)));
            return out.toString();
        }
        if (value instanceof Collection<?> values) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            values.forEach(v -> out.add(json(v)));
            return out.toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) switch (c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }
        return out.append('"').toString();
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("graphite.broad.pressure.graphs", args[0]);
        Class<?> owner = Class.forName("io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmarkKt");
        Object sources = invoke(owner, "broadQueryGraphSources", new Class<?>[]{int.class}, 64);
        Object distributions = invoke(owner, "broadQueryFixtureDistributions", new Class<?>[]{List.class}, sources);
        List<?> all = (List<?>) invoke(owner, "broadQueryCoverageWorkload", new Class<?>[]{List.class, Map.class}, sources, distributions);
        List<Object> selected = new ArrayList<>();
        for (Object entry : all) {
            if (!"global-wide".equals(get(get(entry, "getFamily"), "getId"))) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("ordinal", selected.size() + 1);
            row.put("id", get(entry, "getId")); row.put("query", get(entry, "getQuery"));
            row.put("parameters", get(entry, "getParameters"));
            row.put("requestGraphIds", get(entry, "getRequestGraphIds"));
            selected.add(row);
        }
        if (selected.size() != 34) throw new IllegalStateException("Expected exactly 34 global-wide cases");
        Files.writeString(Path.of(args[1]), json(selected) + "\n");
        System.out.println("Exported 34 original workload cases without loading graphs or executing queries");
    }
}
