import io.johnsonlee.graphite.webgraph.GraphStore;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.core.Node;
import com.google.gson.GsonBuilder;
import java.nio.file.*;import java.util.*;import java.lang.reflect.*;
class Probe {
 interface Op {Object get() throws Exception;}
 static Object run(Op op){try{return op.get();}catch(Throwable t){while(t.getCause()!=null)t=t.getCause();return t.getClass().getSimpleName()+": "+t.getMessage();}}
 static int count(kotlin.sequences.Sequence<?> s){int n=0;for(var i=s.iterator();i.hasNext();){i.next();n++;}return n;}
 public static void main(String[] args)throws Exception{
  Map<String,Object> r=new LinkedHashMap<>();Graph g=null;
  try {g=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.valueOf(args[1]));r.put("load","ok");}catch(Throwable t){r.put("load",run(()->{throw new Exception(t);}));}
  if(g!=null){final Graph graph=g;r.put("nodes",run(()->graph.nodeCount(Node.class)));r.put("methods",run(graph::methodCount));
   List<Integer> ids=new ArrayList<>();r.put("decodedNodes",run(()->{for(var i=graph.nodes(Node.class).iterator();i.hasNext();)ids.add((Integer)Node.class.getMethods()[0].invoke(i.next()));return ids.size();}));
   r.put("outgoing",run(()->{int n=0;Method m=Graph.class.getMethod("outgoing-NKfh_u4",int.class);for(int id:ids)n+=count((kotlin.sequences.Sequence<?>)m.invoke(graph,id));return n;}));
   r.put("incoming",run(()->{int n=0;Method m=Graph.class.getMethod("incoming-NKfh_u4",int.class);for(int id:ids)n+=count((kotlin.sequences.Sequence<?>)m.invoke(graph,id));return n;}));
   r.put("classOrigins",run(()->graph.classOrigins().size()));r.put("resources",run(()->count(graph.getResources().list("**"))));r.put("classoverview",run(()->{var v=graph.classOverview(100);return v==null?null:v.getCallSiteCount();}));
  }
  System.out.println(new GsonBuilder().serializeNulls().create().toJson(r));
 }
}
