import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** JDK primitive conversions only. Does not load a graph or query implementation. */
public final class JvmPrimitiveFacts {
    static void check(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] block = new byte[65536]; int count;
            while ((count = input.read(block)) != -1) digest.update(block, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Map<String, String> ref(Path path) throws Exception {
        return Map.of("path", path.toRealPath().toString(), "sha256", sha(path));
    }
    static void keys(JsonObject value, String... names) {
        check(value.keySet().equals(Set.of(names)), "unexpected object fields");
    }
    static String text(JsonObject value, String key) {
        var field = value.get(key);
        check(field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString(), "expected text " + key);
        return field.getAsString();
    }
    static void verifyRef(JsonObject expected, Path actual) throws Exception {
        keys(expected, "path", "sha256");
        check(text(expected, "path").equals(actual.toRealPath().toString()) &&
              text(expected, "sha256").equals(sha(actual)), "actual primitive helper/JDK binding");
    }
    static void verifyIdentity(JsonObject packet, Path source, Path helperClass) throws Exception {
        verifyRef(packet.getAsJsonObject("helperSource"), source);
        verifyRef(packet.getAsJsonObject("helperClass"), helperClass);
        var jdk = packet.getAsJsonObject("jdkImage"); keys(jdk, "home", "files");
        Path home = Path.of(System.getProperty("java.home")).toRealPath();
        check(text(jdk, "home").equals(home.toString()), "actual java.home");
        var files = jdk.getAsJsonObject("files"); keys(files, "bin/java", "release", "lib/modules");
        for (String name : files.keySet()) verifyRef(files.getAsJsonObject(name), home.resolve(name));
    }
    static String convert(JsonObject operation) {
        keys(operation, "operation", "input");
        String kind = text(operation, "operation"), value = text(operation, "input");
        return switch (kind) {
            case "float32" -> {
                check(value.matches("[0-9a-f]{8}"), "exact Float32 bits");
                yield Float.toString(Float.intBitsToFloat(Integer.parseUnsignedInt(value, 16)));
            }
            case "double" -> {
                check(value.matches("[0-9a-f]{16}"), "exact Double bits");
                yield Double.toString(Double.longBitsToDouble(Long.parseUnsignedLong(value, 16)));
            }
            case "lowercaseRoot" -> value.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("unknown primitive conversion");
        };
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 3, "expected requests output helperSource");
        Path input = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath().normalize();
        Path source = Path.of(args[2]).toRealPath();
        Path helperClass = Path.of(JvmPrimitiveFacts.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("JvmPrimitiveFacts.class").toRealPath();
        check(!Files.exists(output), "output already exists");
        byte[] raw = Files.readAllBytes(input); String requestSha = sha(raw);
        JsonObject packet = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        keys(packet, "schema", "scope", "sourceGraphs", "operations", "helperSource", "helperClass", "jdkImage");
        check(text(packet, "schema").equals("graphite.jvm-primitive-requests.v1") &&
              text(packet, "scope").equals("RAW_INPUT_CONVERSIONS_NOT_QUERY_RESULTS"), "primitive request scope");
        check(packet.getAsJsonArray("sourceGraphs").size() == 64, "complete request graph scope");
        verifyIdentity(packet, source, helperClass);
        var operations = packet.getAsJsonArray("operations"); var results = new ArrayList<Map<String, Object>>();
        var unique = new HashSet<List<String>>();
        for (var item : operations) {
            var operation = item.getAsJsonObject(); String value = convert(operation);
            String kind = text(operation, "operation"), original = text(operation, "input");
            check(unique.add(List.of(kind, original)), "duplicate primitive request");
            results.add(Map.of("index", results.size(), "operation", kind, "input", original, "output", value));
        }
        verifyIdentity(packet, source, helperClass);
        check(requestSha.equals(sha(input)), "primitive request changed during execution");
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "graphite.jvm-primitive-facts.v1");
        result.put("scope", "JDK_PRIMITIVE_CONVERSIONS_NOT_GRAPH_ORACLE");
        result.put("request", Map.of("path", input.toString(), "sha256", requestSha));
        result.put("helperSource", ref(source)); result.put("helperClass", ref(helperClass));
        result.put("jdkImage", packet.get("jdkImage")); result.put("graphCount", 64);
        result.put("operationCount", results.size()); result.put("results", results);
        result.put("sourceGraphBytesVerified", false); result.put("queryImplementationUsed", false);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
