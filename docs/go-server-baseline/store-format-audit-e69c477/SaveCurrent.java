import io.johnsonlee.graphite.webgraph.GraphStore;import java.nio.file.*;
class SaveCurrent {public static void main(String[] args)throws Exception{var graph=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.EAGER);GraphStore.INSTANCE.save(graph,Path.of(args[1]),3,false);}}
