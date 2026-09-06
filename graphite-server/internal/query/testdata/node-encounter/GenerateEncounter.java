import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
/** Actual main writer, with an explicitly interleaved Graph.nodes source. */
public class GenerateEncounter {
 public static void main(String[] args)throws Exception {
  List<Node> nodes=new ArrayList<>();DefaultGraph.Builder builder=new DefaultGraph.Builder();
  int[] ids={90,2,17,41,4,77,22,6,103};
  MethodDescriptor method=new MethodDescriptor(new TypeDescriptor("Example",List.of()),"run",List.of(),new TypeDescriptor("void",List.of()));
  for(int i=0;i<ids.length;i++) {
   Node n=switch(i%3) {
    case 0 -> (Node)IntConstant.class.getConstructors()[0].newInstance(ids[i],i,null);
    case 1 -> (Node)StringConstant.class.getConstructors()[0].newInstance(ids[i],"value"+i,null);
    default -> (Node)CallSiteNode.class.getConstructors()[0].newInstance(ids[i],method,method,Integer.valueOf(i),null,List.of(),null);
   }; nodes.add(n);builder.addNode(n);
  }
  Graph base=builder.build();
  Graph ordered=(Graph)Proxy.newProxyInstance(Graph.class.getClassLoader(),new Class[]{Graph.class},(p,m,a)-> {
   if(m.getName().equals("nodes")) {Class<?> type=(Class<?>)a[0];return kotlin.collections.CollectionsKt.asSequence(nodes.stream().filter(type::isInstance).toList());}
   return m.invoke(base,a);
  });
  GraphStore.INSTANCE.save(ordered,Path.of(args[0]),3,true);
 }
}
