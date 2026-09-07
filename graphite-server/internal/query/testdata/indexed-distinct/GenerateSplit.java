import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
/** 4096 records are required to exercise a semantic storage branch, not timing. */
public class GenerateSplit {
 public static void main(String[]args)throws Exception {
  Graph source=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.EAGER);
  List<CallSiteNode> nodes=kotlin.sequences.SequencesKt.toList(source.nodes(CallSiteNode.class));
  var b=new DefaultGraph.Builder();var constructor=CallSiteNode.class.getConstructors()[0];
  for(int i=0;i<4096;i++) {var node=nodes.get(i%nodes.size());b.addNode((Node)constructor.newInstance(i,node.getCaller(),node.getCallee(),node.getLineNumber(),null,List.of(),null));}
  GraphStore.INSTANCE.save(b.build(),Path.of(args[1]),3,true);
 }
}
