import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public final class ExportBenchmarkCases {
    static Object invoke(Method method, Object owner, Object... args) throws Exception {
        method.setAccessible(true);
        return method.invoke(owner, args);
    }
    static Object plain(Object value) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?>) {
            try { return invoke(value.getClass().getDeclaredMethod("getId"), value); }
            catch (NoSuchMethodException error) { return ((Enum<?>) value).name(); }
        }
        if (value instanceof Map<?, ?>) {
            Map<String,Object> result = new LinkedHashMap<>();
            for (Map.Entry<?,?> item : ((Map<?,?>) value).entrySet()) result.put(item.getKey().toString(), plain(item.getValue()));
            return result;
        }
        if (value.getClass().getName().equals("kotlin.ranges.LongRange")) {
            Map<String,Object> range = new LinkedHashMap<>();
            range.put("first", invoke(value.getClass().getMethod("getFirst"), value));
            range.put("last", invoke(value.getClass().getMethod("getLast"), value));
            return range;
        }
        if (value instanceof Iterable<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Iterable<?>) value) result.add(plain(item));
            return result;
        }
        Map<String,Object> result = new LinkedHashMap<>();
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            field.setAccessible(true); result.put(field.getName(), plain(field.get(value)));
        }
        return result;
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder s = new StringBuilder("\"");
            for (char c : ((String)value).toCharArray()) {
                if (c == '"' || c == '\\') s.append('\\').append(c);
                else if (c < 32 || Character.isSurrogate(c)) s.append(String.format("\\u%04x", (int)c));
                else s.append(c);
            }
            return s.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?>) {
            List<String> items = new ArrayList<>();
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) items.add(json(e.getKey()) + ":" + json(e.getValue()));
            return "{" + String.join(",",items) + "}";
        }
        if (value instanceof Iterable<?>) {
            List<String> items = new ArrayList<>();
            for (Object e : (Iterable<?>)value) items.add(json(e));
            return "[" + String.join(",",items) + "]";
        }
        throw new IllegalArgumentException(value.getClass().getName());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("manifest output");
        System.setProperty("graphite.broad.pressure.graphs", args[0]);
        Class<?> owner = Class.forName("io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmarkKt");
        Object sources = invoke(owner.getDeclaredMethod("broadQueryGraphSources", int.class), null, 64);
        Object distributions = invoke(owner.getDeclaredMethod("broadQueryFixtureDistributions", List.class), null, sources);
        Object cases = invoke(owner.getDeclaredMethod("broadQueryCoverageWorkload", List.class, Map.class), null, sources, distributions);
        Map<String,Object> output = new LinkedHashMap<>();
        output.put("mainRevision", "4e328b0109e13c896b74004823fb049fcb19251a");
        output.put("scope", "Exact main workload construction only: no GraphStore.loadMapped, query execution, or timing");
        output.put("sourceOrder", ((List<?>)sources).stream().map(s -> { try { Field f=s.getClass().getDeclaredField("id");f.setAccessible(true);return f.get(s); } catch(Exception e) {throw new RuntimeException(e);} }).toList());
        output.put("cases", plain(cases));
        Files.writeString(Path.of(args[1]), json(output) + "\n");
        System.out.println("Exported " + ((List<?>) cases).size() + " actual pinned-main cases without graph loading/query execution");
    }
}
