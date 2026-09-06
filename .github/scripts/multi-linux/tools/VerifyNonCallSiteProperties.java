import io.johnsonlee.graphite.core.AnnotationNode;
import io.johnsonlee.graphite.webgraph.GraphStore;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Same named CLI options as ExportCallSites. Frozen-main AnnotationNode.values is the only
 * non-CallSite property accessor that can expose the four queried keys. Any such key fails closed.
 * Other sealed node branches return null; nonempty CONTAINS against coalesce(null, '') is false.
 */
public final class VerifyNonCallSiteProperties {
    public static void main(String[] args) throws Exception {
        var inputs = ExportCallSites.inputs(args);
        Set<String> fields = Set.of("caller_class", "caller_name", "callee_class", "callee_name");
        List<Long> counts = new ArrayList<>();
        long populated = 0;
        try (var out = Files.newBufferedWriter(Path.of(inputs.options().get("output")))) {
            out.write("graphId\tannotationNodes\tannotationsWithQueriedKeys\n");
            for (var source : inputs.sources()) {
                var graph = GraphStore.INSTANCE.loadMapped(source.path());
                long count = 0;
                long withKeys = 0;
                try {
                    var nodes = graph.nodes(AnnotationNode.class).iterator();
                    while (nodes.hasNext()) {
                        var node = nodes.next();
                        count++;
                        if (node.getValues().keySet().stream().anyMatch(fields::contains)) withKeys++;
                    }
                } finally {
                    ((Closeable) graph).close();
                }
                counts.add(count);
                populated += withKeys;
                out.write(source.id() + "\t" + count + "\t" + withKeys + "\n");
            }
        }
        ExportCallSites.require(populated == 0, "Non-CallSite properties require an expanded oracle census");
        ExportCallSites.receipt(inputs, "non-callsite-property-census", counts);
    }
}
