// Saves the graph mapped from -Dfrom (default: -Ddir) into -Ddir with GraphStore.save, the
// same-directory and existing-directory cases of the data-safety row.
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.nio.file.*;
var dir = Path.of(System.getProperty("dir"));
var from = Path.of(System.getProperty("from", System.getProperty("dir")));
var graph = GraphStore.INSTANCE.loadMapped(from);
try {
    GraphStore.INSTANCE.save(graph, dir, 2);
    System.out.println("saved " + from + " into " + dir);
} catch (Exception e) {
    System.out.println("save failed: " + e);
}
/exit
