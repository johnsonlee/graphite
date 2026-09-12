import io.johnsonlee.graphite.core.CallSiteNode;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Independent, streaming fixture64 oracle input. Never executes Cypher. Compile both helpers
 * against the trusted frozen-main JMH JAR; run with -Xmx3g. Inputs are verified before scanning.
 * Required options: --manifest --provenance --output --receipt --expected-jar-sha256.
 * The caller must first authenticate the corpus with Fixture64GraphPreparation --verify.
 */
public final class ExportCallSites {
    static final String FROZEN_REVISION = "4e328b0109e13c896b74004823fb049fcb19251a";
    static final List<String> CORPORA = List.of("android", "tika", "hive", "kotlin-compiler");
    static final Map<String, String> SOURCE_JARS = Map.of(
        "android", "6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977",
        "tika", "87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da",
        "hive", "232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad",
        "kotlin-compiler", "9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81"
    );

    record Source(String id, Path path, long callSiteCount) {}
    record Inputs(Map<String, String> options, List<Source> sources, Map<String, Object> identity) {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static Map<String, String> options(String[] args) {
        Set<String> required = Set.of("manifest", "provenance", "output", "receipt", "expected-jar-sha256");
        Map<String, String> options = new LinkedHashMap<>();
        require(args.length == required.size() * 2, "Required options: " + required);
        for (int i = 0; i < args.length; i += 2) {
            require(args[i].startsWith("--"), "Expected named option");
            String key = args[i].substring(2);
            require(required.contains(key) && !options.containsKey(key), "Unknown/duplicate option: " + key);
            options.put(key, args[i + 1]);
        }
        return options;
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Inputs inputs(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path manifest = Path.of(options.get("manifest")).toRealPath();
        Path provenance = Path.of(options.get("provenance")).toRealPath();
        Path jar = Path.of(GraphStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        String jarDigest = sha256(jar);
        require(jarDigest.equals(options.get("expected-jar-sha256")), "Loaded GraphStore JAR identity differs");
        List<String> rows = Files.readAllLines(manifest).stream()
            .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        List<String> provenanceRows = Files.readAllLines(provenance);
        require(rows.size() == 64 && provenanceRows.size() == 65, "Expected exactly 64 real fixture graphs");
        require(provenanceRows.get(0).equals("graphId\tcorpus\tshard\tsourceJar\tsourceJarSha256\t"
            + "shardBytecodeSha256\tclassCount\tnodeCount\tcallSiteCount\tzeroTerm\ttargetedTerm\t"
            + "denseTerm\tquerySemanticSha256\tresourceCount\tresourceSemanticSha256\tworkloadIdentity\t"
            + "callSiteIndexBytes\tcallSiteIndexSha256\tgraphPath"), "Unexpected fixture provenance schema");
        List<Source> sources = new ArrayList<>();
        Set<Path> paths = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            String[] row = rows.get(i).split("\t", -1);
            String[] proof = provenanceRows.get(i + 1).split("\t", -1);
            String corpus = CORPORA.get(i / 16);
            String expectedId = "fixture-" + corpus + "-" + String.format("%02d", i % 16);
            require(row.length == 6 && proof.length == 19, "Malformed fixture row " + i);
            require(row[0].equals(expectedId) && proof[0].equals(expectedId), "Fixture order/identity differs");
            require(proof[1].equals(corpus) && Integer.parseInt(proof[2]) == i % 16
                && proof[4].equals(SOURCE_JARS.get(corpus)), "Fixture source JAR/shard is not pinned");
            require(row[5].matches("[0-9a-f]{64}") && row[5].equals(proof[15])
                && identities.add(row[5]), "Workload identities differ or repeat");
            require(row[2].equals(proof[9]) && row[3].equals(proof[10]) && row[4].equals(proof[11]),
                "Fixture terms differ from provenance");
            Path path = Path.of(row[1]).toRealPath();
            require(Files.isDirectory(path) && path.equals(Path.of(proof[18]).toRealPath()) && paths.add(path),
                "Persisted graph paths differ or repeat");
            Path index = path.resolve("graph.callsite-string-index");
            require(Files.size(index) == Long.parseLong(proof[16]) && sha256(index).equals(proof[17]),
                "Persisted CallSite index differs from authenticated fixture provenance");
            long callSites = Long.parseLong(proof[8]);
            require(callSites > 0, "Empty CallSite fixture");
            sources.add(new Source(expectedId, path, callSites));
        }
        Path output = Path.of(options.get("output")).toAbsolutePath().normalize();
        Path receipt = Path.of(options.get("receipt")).toAbsolutePath().normalize();
        require(!Files.exists(output) && !Files.exists(receipt), "Outputs must not exist; use a fresh directory");
        require(!output.equals(receipt), "Output and receipt paths must differ");
        Files.createDirectories(output.getParent());
        Files.createDirectories(receipt.getParent());
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schema", "graphite-wide-oracle-input-v1");
        identity.put("frozenRevision", FROZEN_REVISION);
        identity.put("manifestSha256", sha256(manifest));
        identity.put("provenanceSha256", sha256(provenance));
        identity.put("jarSha256", jarDigest);
        identity.put("graphIds", sources.stream().map(Source::id).toList());
        return new Inputs(options, sources, identity);
    }

    static void receipt(Inputs inputs, String kind, List<Long> counts) throws Exception {
        Map<String, Object> identity = inputs.identity();
        Path output = Path.of(inputs.options().get("output"));
        identity.put("kind", kind);
        identity.put("outputSha256", sha256(output));
        identity.put("outputBytes", Files.size(output));
        identity.put("perGraphNodeCounts", counts);
        identity.put("passed", true);
        Files.writeString(Path.of(inputs.options().get("receipt")), json(identity) + "\n");
    }

    static String json(Object value) {
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream()
            .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(ExportCallSites::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    public static void main(String[] args) throws Exception {
        Inputs inputs = inputs(args);
        List<Long> counts = new ArrayList<>();
        try (var out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
            Files.newOutputStream(Path.of(inputs.options().get("output")))), StandardCharsets.UTF_8))) {
            for (int gi = 0; gi < inputs.sources().size(); gi++) {
                Source source = inputs.sources().get(gi);
                Graph graph = GraphStore.INSTANCE.loadMapped(source.path());
                long count = 0;
                try {
                    var nodes = graph.nodes(CallSiteNode.class).iterator();
                    while (nodes.hasNext()) {
                        var node = nodes.next();
                        String[] values = {node.getCaller().getDeclaringClass().getClassName(), node.getCaller().getName(),
                            node.getCallee().getDeclaringClass().getClassName(), node.getCallee().getName()};
                        out.write(Integer.toString(gi));
                        for (String value : values) {
                            require(value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0,
                                "CallSite value cannot be represented losslessly in TSV");
                            out.write('\t');
                            out.write(value);
                        }
                        out.newLine();
                        count++;
                    }
                } finally {
                    ((Closeable) graph).close();
                }
                require(count == source.callSiteCount(), "Export CallSite count differs: " + source.id());
                counts.add(count);
                System.err.println(gi + "\t" + source.id() + "\t" + count);
            }
        }
        receipt(inputs, "ordered-callsite-tuples", counts);
    }
}
