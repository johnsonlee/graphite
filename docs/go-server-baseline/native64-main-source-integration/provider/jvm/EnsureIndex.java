import io.johnsonlee.graphite.webgraph.GraphStore;import java.nio.file.Path;
public class EnsureIndex {public static void main(String[] args){GraphStore.INSTANCE.ensureNodeIndex(Path.of(args[0]));}}
