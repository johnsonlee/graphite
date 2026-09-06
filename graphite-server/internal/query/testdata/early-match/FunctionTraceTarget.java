import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
public class FunctionTraceTarget {
 public static void main(String[] args)throws Exception {
  Graph graph=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.valueOf(args[1]));
  String query="MATCH (n:IntConstant) RETURN n.id AS id LIMIT "+args[2]+"()*0+$l";
  CypherResult result=Boolean.parseBoolean(args[3])?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query,Map.of("l",1)):new CypherExecutor(graph).execute(query,Map.of("l",1));
  System.out.println(new GsonBuilder().serializeNulls().create().toJson(Map.of("query",query,"columns",result.getColumns(),"rows",result.getRows())));
  if(graph instanceof java.io.Closeable)((java.io.Closeable)graph).close();
 }
}
