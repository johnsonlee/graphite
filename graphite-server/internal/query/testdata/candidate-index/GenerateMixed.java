import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
public class GenerateMixed {
 public static void main(String[]args)throws Exception{
  Graph source=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.EAGER);
  for(boolean annotation:List.of(false,true)){
   var builder=new DefaultGraph.Builder();for(Node n:kotlin.sequences.SequencesKt.toList(source.nodes(Node.class)))builder.addNode(n);
   builder.addNode((Node)IntConstant.class.getConstructors()[0].newInstance(150,123,null));
   if(annotation){var constructor=AnnotationNode.class.getDeclaredConstructor(int.class,String.class,String.class,String.class,Map.class);constructor.setAccessible(true);var values=new LinkedHashMap<String,Object>();values.put("caller_class","example.Other");values.put("callee_name",12);values.put("caller_name",null);builder.addNode((Node)constructor.newInstance(101,"Audit","Owner","member",values));}
   GraphStore.INSTANCE.save(builder.build(),Path.of(args[1]).resolve(annotation?"annotation":"mixed"),3,true);
  }
 }
}
